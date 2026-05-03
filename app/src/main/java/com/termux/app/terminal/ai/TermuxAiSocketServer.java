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
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

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
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class TermuxAiSocketServer {

    public static final String LOG_TAG = "TermuxAiSocketServer";
    public static final String TITLE = "TermuxAi";
    public static final String VERSION = "0.2.0";
    public static final String SOCKET_FILE_PATH =
        TermuxConstants.TERMUX_APP.APPS_DIR_PATH + "/termux-ai/ai.sock";
    private static final String CHANNEL_ID = "termux_ai_bridge";

    private static LocalSocketManager socketServer;
    private static TextToSpeech tts;
    private static boolean ttsReady;
    private static String ttsEngine;

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
                    case "api.list":
                        return ok(apiList());
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
                    case "notification.show":
                        return ok(notify(args));
                    case "notification.channel":
                        return ok(notificationChannel(args));
                    case "notification.remove":
                        return ok(notificationRemove(args));
                    case "ui.toast":
                        return ok(toast(args));
                    case "audio.info":
                        return ok(audioInfo());
                    case "audio.volume":
                        return ok(audioVolume(args));
                    case "audio.volume.list":
                        return ok(audioVolumeList());
                    case "display.brightness":
                        return ok(brightness(args));
                    case "camera.info":
                        return ok(cameraInfo());
                    case "camera.torch":
                        return ok(torch(args));
                    case "wifi.connection":
                        return ok(wifiConnection());
                    case "tts.engines":
                        return ok(ttsEngines());
                    case "tts.speak":
                        return ok(ttsSpeak(args));
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

        private JSONObject apiList() throws Exception {
            return new JSONObject()
                .put("version", VERSION)
                .put("commands", new JSONArray()
                    .put("ping")
                    .put("bridge.version")
                    .put("api.list")
                    .put("sys.info")
                    .put("sys.battery")
                    .put("sys.clipboard.get")
                    .put("sys.clipboard.set")
                    .put("sys.vibrate")
                    .put("sys.notify")
                    .put("notification.show")
                    .put("notification.channel")
                    .put("notification.remove")
                    .put("ui.toast")
                    .put("audio.info")
                    .put("audio.volume")
                    .put("audio.volume.list")
                    .put("display.brightness")
                    .put("camera.info")
                    .put("camera.torch")
                    .put("wifi.connection")
                    .put("tts.engines")
                    .put("tts.speak")
                    .put("sensor.list")
                    .put("sensor.read")
                    .put("storage.info")
                    .put("storage.list")
                    .put("storage.read")
                    .put("storage.write"));
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
            String body = args.optString("body", args.optString("text", ""));
            String channel = args.optString("channel", CHANNEL_ID);
            String id = args.optString("id", "1773");
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, channel)
                : new Notification.Builder(context);
            Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(args.optBoolean("ongoing", false))
                .build();
            nm.notify(id.hashCode(), notification);
            return new JSONObject().put("status", "posted").put("id", id).put("channel", channel);
        }

        private JSONObject notificationChannel(JSONObject args) throws Exception {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                return new JSONObject().put("status", "ignored").put("reason", "notification channels require Android 8+");
            String id = args.optString("id", CHANNEL_ID);
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (args.optBoolean("delete", false)) {
                nm.deleteNotificationChannel(id);
                return new JSONObject().put("status", "deleted").put("id", id);
            }
            String name = args.optString("name", id);
            nm.createNotificationChannel(new NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT));
            return new JSONObject().put("status", "created").put("id", id).put("name", name);
        }

        private JSONObject notificationRemove(JSONObject args) throws Exception {
            String id = args.optString("id", "1773");
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(id.hashCode());
            return new JSONObject().put("status", "removed").put("id", id);
        }

        private JSONObject toast(JSONObject args) throws Exception {
            String text = args.optString("text", "");
            boolean shortToast = args.optBoolean("short", false);
            Toast.makeText(context, text, shortToast ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            return new JSONObject().put("status", "shown").put("text", text);
        }

        private JSONObject audioInfo() throws Exception {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            return new JSONObject()
                .put("sample_rate", am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE))
                .put("frames_per_buffer", am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER))
                .put("bluetooth_a2dp_on", am.isBluetoothA2dpOn())
                .put("wired_headset_on", am.isWiredHeadsetOn())
                .put("speakerphone_on", am.isSpeakerphoneOn())
                .put("music_active", am.isMusicActive());
        }

        private JSONObject audioVolume(JSONObject args) throws Exception {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            String streamName = args.optString("stream", "music");
            int stream = audioStream(streamName);
            if (stream < 0) return new JSONObject().put("error", "Unknown audio stream: " + streamName);
            if (args.has("volume")) {
                int max = am.getStreamMaxVolume(stream);
                int volume = Math.max(0, Math.min(args.optInt("volume", am.getStreamVolume(stream)), max));
                am.setStreamVolume(stream, volume, 0);
            }
            return audioVolumeInfo(am, streamName, stream);
        }

        private JSONObject audioVolumeList() throws Exception {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            JSONArray streams = new JSONArray();
            String[] names = new String[]{"alarm", "music", "notification", "ring", "system", "call"};
            for (String name : names) streams.put(audioVolumeInfo(am, name, audioStream(name)));
            return new JSONObject().put("streams", streams);
        }

        private JSONObject brightness(JSONObject args) throws Exception {
            boolean auto = args.optBoolean("auto", false) || "auto".equals(args.optString("mode", ""));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                return new JSONObject().put("error", "WRITE_SETTINGS permission is not granted");
            }
            if (auto) {
                Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                return new JSONObject().put("mode", "auto");
            }
            int level = Math.max(0, Math.min(args.optInt("brightness", args.optInt("level", 0)), 255));
            Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, level);
            return new JSONObject().put("mode", "manual").put("brightness", level);
        }

        private JSONObject cameraInfo() throws Exception {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            JSONArray cameras = new JSONArray();
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                cameras.put(new JSONObject()
                    .put("id", id)
                    .put("facing", cameraFacingName(facing))
                    .put("flash_available", flash != null && flash));
            }
            return new JSONObject().put("cameras", cameras);
        }

        private JSONObject torch(JSONObject args) throws Exception {
            boolean enabled = args.optBoolean("enabled", false);
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    manager.setTorchMode(id, enabled);
                    return new JSONObject().put("status", enabled ? "on" : "off").put("camera", id);
                }
            }
            return new JSONObject().put("error", "No camera with torch available");
        }

        private JSONObject wifiConnection() throws Exception {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            return new JSONObject()
                .put("ssid", info == null ? "" : info.getSSID())
                .put("bssid", info == null ? "" : info.getBSSID())
                .put("rssi", info == null ? 0 : info.getRssi())
                .put("link_speed_mbps", info == null ? 0 : info.getLinkSpeed())
                .put("network_id", info == null ? -1 : info.getNetworkId())
                .put("supplicant_state", info == null ? "" : String.valueOf(info.getSupplicantState()));
        }

        private JSONObject ttsEngines() throws Exception {
            ensureTts(null);
            JSONArray engines = new JSONArray();
            for (TextToSpeech.EngineInfo engine : tts.getEngines()) {
                engines.put(new JSONObject()
                    .put("name", engine.name)
                    .put("label", engine.label)
                    .put("default", engine.name.equals(tts.getDefaultEngine())));
            }
            return new JSONObject().put("engines", engines);
        }

        private JSONObject ttsSpeak(JSONObject args) throws Exception {
            String text = args.optString("text", "");
            if (text.isEmpty()) return new JSONObject().put("error", "Missing text");
            ensureTts(args.optString("engine", null));
            tts.setPitch((float) args.optDouble("pitch", 1.0));
            tts.setSpeechRate((float) args.optDouble("rate", 1.0));
            String language = args.optString("language", "");
            if (!language.isEmpty()) {
                String region = args.optString("region", "");
                String variant = args.optString("variant", "");
                tts.setLanguage(new Locale(language, region, variant));
            }
            android.os.Bundle ttsParams = new android.os.Bundle();
            String stream = args.optString("stream", "");
            if (!stream.isEmpty()) {
                int audioStream = audioStream(stream.toLowerCase(Locale.ROOT));
                if (audioStream >= 0)
                    ttsParams.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, audioStream);
            }
            int result = tts.speak(text, TextToSpeech.QUEUE_ADD, ttsParams, "termux-ai-tts");
            return new JSONObject().put("queued", result == TextToSpeech.SUCCESS).put("chars", text.length());
        }

        private synchronized void ensureTts(String engine) throws Exception {
            String desired = (engine == null || engine.isEmpty()) ? null : engine;
            if (tts != null && ttsReady) {
                if (desired == null || desired.equals(ttsEngine)) return;
                tts.shutdown();
                tts = null;
                ttsReady = false;
                ttsEngine = null;
            }
            CountDownLatch latch = new CountDownLatch(1);
            ttsReady = false;
            tts = desired == null
                ? new TextToSpeech(context, status -> {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    latch.countDown();
                })
                : new TextToSpeech(context, status -> {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    latch.countDown();
                }, desired);
            if (!latch.await(10, TimeUnit.SECONDS) || !ttsReady) {
                if (tts != null) {
                    try { tts.shutdown(); } catch (Exception ignored) {}
                    tts = null;
                }
                ttsEngine = null;
                throw new IllegalStateException("TextToSpeech engine did not initialize");
            }
            ttsEngine = desired;
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

        private JSONObject audioVolumeInfo(AudioManager am, String name, int stream) throws Exception {
            return new JSONObject()
                .put("stream", name)
                .put("volume", am.getStreamVolume(stream))
                .put("max_volume", am.getStreamMaxVolume(stream));
        }

        private int audioStream(String stream) {
            switch (stream) {
                case "alarm": return AudioManager.STREAM_ALARM;
                case "call": return AudioManager.STREAM_VOICE_CALL;
                case "notification": return AudioManager.STREAM_NOTIFICATION;
                case "ring": return AudioManager.STREAM_RING;
                case "system": return AudioManager.STREAM_SYSTEM;
                case "music": return AudioManager.STREAM_MUSIC;
                default: return -1;
            }
        }

        private String cameraFacingName(Integer facing) {
            if (facing == null) return "unknown";
            switch (facing) {
                case CameraCharacteristics.LENS_FACING_BACK: return "back";
                case CameraCharacteristics.LENS_FACING_FRONT: return "front";
                case CameraCharacteristics.LENS_FACING_EXTERNAL: return "external";
                default: return "unknown";
            }
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
