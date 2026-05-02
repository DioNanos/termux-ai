package com.termux.app.terminal.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.annotation.NonNull;

import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.socket.local.LocalClientSocket;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;
import com.termux.shared.net.socket.local.LocalSocketRunConfig;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class TermuxAiSocketServer {

    public static final String LOG_TAG = "TermuxAiSocketServer";
    public static final String TITLE = "TermuxAi";
    public static final String VERSION = "0.1.0";
    public static final String SOCKET_FILE_PATH =
        TermuxConstants.TERMUX_APP.APPS_DIR_PATH + "/termux-ai/ai.sock";
    private static final String CHANNEL_ID = "termux_ai_bridge";

    private static LocalSocketManager socketServer;

    private TermuxAiSocketServer() {}

    public static synchronized void setup(@NonNull Context context) {
        start(context.getApplicationContext());
    }

    public static synchronized void start(@NonNull Context context) {
        stop();
        File socketFile = new File(SOCKET_FILE_PATH);
        File parent = socketFile.getParentFile();
        if (parent != null) parent.mkdirs();
        if (socketFile.exists()) socketFile.delete();

        socketServer = new LocalSocketManager(context,
            new LocalSocketRunConfig(TITLE, SOCKET_FILE_PATH, new Client(context.getApplicationContext())));
        Error error = socketServer.start();
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.getErrorLogString());
            socketServer = null;
        }
    }

    public static synchronized void stop() {
        if (socketServer != null) {
            socketServer.stop();
            socketServer = null;
        }
    }

    public static String processRequest(@NonNull Context context, @NonNull String raw) {
        return new Client(context.getApplicationContext()).dispatch(raw);
    }

    private static final class Client extends LocalSocketManagerClientBase {
        private final Context context;

        Client(Context context) {
            this.context = context;
        }

        @Override
        protected String getLogTag() {
            return LOG_TAG;
        }

        @Override
        public void onClientAccepted(@NonNull LocalSocketManager manager,
                                     @NonNull LocalClientSocket clientSocket) {
            try {
                StringBuilder request = new StringBuilder();
                Error readError = clientSocket.readDataOnInputStream(request, false);
                String response = readError != null
                    ? error("Read failed: " + readError.getMinimalErrorString())
                    : dispatch(request.toString().trim());
                clientSocket.sendDataToOutputStream(response + "\n", false);
            } finally {
                clientSocket.closeClientSocket(false);
            }
        }

        String dispatch(String raw) {
            try {
                JSONObject request = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
                String cmd = request.optString("cmd", "ping");
                JSONObject args = request.optJSONObject("args");
                if (args == null) args = new JSONObject();

                switch (cmd) {
                    case "ping":
                        return ok(new JSONObject().put("message", "pong"));
                    case "bridge.version":
                        return ok(new JSONObject().put("version", VERSION).put("socket", SOCKET_FILE_PATH));
                    case "sys.info":
                        return ok(sysInfo());
                    case "sys.battery":
                        return ok(battery());
                    case "sys.clipboard.get":
                        return ok(clipboardGet());
                    case "sys.clipboard.set":
                        return ok(clipboardSet(args));
                    case "sys.vibrate":
                        return ok(vibrate(args));
                    case "sys.notify":
                        return ok(notify(args));
                    case "sensor.list":
                        return ok(sensorList());
                    case "sensor.read":
                        return ok(sensorRead(args));
                    case "storage.info":
                        return ok(storageInfo());
                    case "storage.list":
                        return ok(storageList(args));
                    case "storage.read":
                        return ok(storageRead(args));
                    case "storage.write":
                        return ok(storageWrite(args));
                    default:
                        return error("Unknown command: " + cmd);
                }
            } catch (Exception e) {
                return error("Invalid request: " + e.getMessage());
            }
        }

        private JSONObject sysInfo() throws Exception {
            return new JSONObject()
                .put("bridge_version", VERSION)
                .put("socket", SOCKET_FILE_PATH)
                .put("package", context.getPackageName())
                .put("device", Build.DEVICE)
                .put("model", Build.MODEL)
                .put("android_version", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("abis", new JSONArray(Build.SUPPORTED_ABIS));
        }

        private JSONObject battery() throws Exception {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            return new JSONObject()
                .put("level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("charging", bm.isCharging())
                .put("current_ua", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
                .put("charge_uah", bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER));
        }

        private JSONObject clipboardGet() throws Exception {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            CharSequence text = "";
            if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                text = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
            }
            return new JSONObject().put("text", text == null ? "" : text.toString());
        }

        private JSONObject clipboardSet(JSONObject args) throws Exception {
            String text = args.optString("text", "");
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("termux-ai", text));
            return new JSONObject().put("status", "ok");
        }

        private JSONObject vibrate(JSONObject args) throws Exception {
            int duration = Math.max(1, Math.min(args.optInt("duration_ms", 80), 5000));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                else
                    vibrator.vibrate(duration);
            }
            return new JSONObject().put("duration_ms", duration);
        }

        private JSONObject notify(JSONObject args) throws Exception {
            String title = args.optString("title", "termux-ai");
            String body = args.optString("body", "");
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Termux AI", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
            Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .build();
            nm.notify(1773, notification);
            return new JSONObject().put("status", "posted");
        }

        private JSONObject sensorList() throws Exception {
            SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            JSONArray sensors = new JSONArray();
            for (Sensor sensor : sm.getSensorList(Sensor.TYPE_ALL)) {
                sensors.put(new JSONObject()
                    .put("name", sensor.getName())
                    .put("type", sensorTypeName(sensor.getType()))
                    .put("type_id", sensor.getType())
                    .put("vendor", sensor.getVendor())
                    .put("max_range", sensor.getMaximumRange())
                    .put("resolution", sensor.getResolution())
                    .put("power_ma", sensor.getPower()));
            }
            return new JSONObject().put("count", sensors.length()).put("sensors", sensors);
        }

        private JSONObject sensorRead(JSONObject args) throws Exception {
            String type = args.optString("type", "accelerometer");
            int sensorType = sensorTypeId(type);
            if (sensorType == 0) return new JSONObject().put("error", "Unknown sensor type: " + type);

            SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            Sensor sensor = sm.getDefaultSensor(sensorType);
            if (sensor == null) return new JSONObject().put("error", "Sensor not available: " + type);

            CountDownLatch latch = new CountDownLatch(1);
            final float[][] values = new float[1][];
            final long[] timestamp = new long[1];
            SensorEventListener listener = new SensorEventListener() {
                @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
                @Override public void onSensorChanged(SensorEvent event) {
                    values[0] = event.values.clone();
                    timestamp[0] = event.timestamp;
                    sm.unregisterListener(this);
                    latch.countDown();
                }
            };
            if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL))
                return new JSONObject().put("error", "Failed to register sensor listener");
            if (!latch.await(5, TimeUnit.SECONDS)) {
                sm.unregisterListener(listener);
                return new JSONObject().put("error", "Sensor read timeout");
            }
            JSONArray out = new JSONArray();
            for (float value : values[0]) out.put(value);
            return new JSONObject().put("sensor", type).put("timestamp_ns", timestamp[0]).put("values", out);
        }

        private JSONObject storageInfo() throws Exception {
            JSONArray roots = new JSONArray();
            for (File root : storageRoots()) roots.put(root.getAbsolutePath());
            return new JSONObject().put("roots", roots);
        }

        private JSONObject storageList(JSONObject args) throws Exception {
            File file = guarded(args.optString("path", context.getFilesDir().getAbsolutePath()));
            if (file == null) return new JSONObject().put("error", "Path outside allowed roots");
            JSONArray entries = new JSONArray();
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    entries.put(new JSONObject()
                        .put("name", child.getName())
                        .put("path", child.getAbsolutePath())
                        .put("directory", child.isDirectory())
                        .put("size", child.length()));
                }
            }
            return new JSONObject().put("path", file.getAbsolutePath()).put("entries", entries);
        }

        private JSONObject storageRead(JSONObject args) throws Exception {
            File file = guarded(args.optString("path", ""));
            if (file == null || !file.isFile()) return new JSONObject().put("error", "File not readable in allowed roots");
            if (file.length() > 256 * 1024) return new JSONObject().put("error", "File too large");
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new JSONObject().put("path", file.getAbsolutePath()).put("text", new String(bytes, StandardCharsets.UTF_8));
        }

        private JSONObject storageWrite(JSONObject args) throws Exception {
            File file = guarded(args.optString("path", ""));
            if (file == null) return new JSONObject().put("error", "Path outside allowed roots");
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            String text = args.optString("text", "");
            java.nio.file.Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
            return new JSONObject().put("path", file.getAbsolutePath()).put("size", file.length());
        }

        private File guarded(String path) throws Exception {
            File candidate = new File(path).getCanonicalFile();
            for (File root : storageRoots()) {
                File canonicalRoot = root.getCanonicalFile();
                if (candidate.equals(canonicalRoot) || candidate.getPath().startsWith(canonicalRoot.getPath() + File.separator))
                    return candidate;
            }
            return null;
        }

        private File[] storageRoots() {
            File external = context.getExternalFilesDir(null);
            return external == null
                ? new File[]{context.getFilesDir(), context.getCacheDir()}
                : new File[]{context.getFilesDir(), context.getCacheDir(), external};
        }

        private String sensorTypeName(int type) {
            switch (type) {
                case Sensor.TYPE_ACCELEROMETER: return "accelerometer";
                case Sensor.TYPE_GYROSCOPE: return "gyroscope";
                case Sensor.TYPE_LIGHT: return "light";
                case Sensor.TYPE_PRESSURE: return "pressure";
                case Sensor.TYPE_PROXIMITY: return "proximity";
                case Sensor.TYPE_GRAVITY: return "gravity";
                case Sensor.TYPE_MAGNETIC_FIELD: return "magnetic_field";
                default: return "type_" + type;
            }
        }

        private int sensorTypeId(String type) {
            switch (type) {
                case "accelerometer": return Sensor.TYPE_ACCELEROMETER;
                case "gyroscope": return Sensor.TYPE_GYROSCOPE;
                case "light": return Sensor.TYPE_LIGHT;
                case "pressure": return Sensor.TYPE_PRESSURE;
                case "proximity": return Sensor.TYPE_PROXIMITY;
                case "gravity": return Sensor.TYPE_GRAVITY;
                case "magnetic_field": return Sensor.TYPE_MAGNETIC_FIELD;
                default: return 0;
            }
        }

        private String ok(JSONObject data) {
            try {
                return new JSONObject().put("ok", true).put("data", data).toString();
            } catch (Exception e) {
                return error(e.getMessage());
            }
        }

        private String error(String message) {
            try {
                return new JSONObject().put("ok", false).put("error", message).toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"internal error\"}";
            }
        }
    }
}
