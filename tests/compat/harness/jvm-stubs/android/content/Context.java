package android.content;

// Signature stub: the subset of android.content.Context the
// compiled main sources use. Behaviour-less: this tree only proves that the
// code COMPILES against faithful signatures, it is not an implementation.
public class Context {
    public static final String BATTERY_SERVICE = "battery";
    public static final String CLIPBOARD_SERVICE = "clipboard";
    public static final String NOTIFICATION_SERVICE = "notification";
    public static final String AUDIO_SERVICE = "audio";
    public static final String CAMERA_SERVICE = "camera";
    public static final String SENSOR_SERVICE = "sensor";
    public static final String VIBRATOR_SERVICE = "vibrator";
    public static final String VIBRATOR_MANAGER_SERVICE = "vibrator_manager";
    public static final String WIFI_SERVICE = "wifi";
    public static final String POWER_SERVICE = "power";
    public static final String WINDOW_SERVICE = "window";

    public Object getSystemService(String name) { return null; }
    public Context getApplicationContext() { return this; }
    public String getPackageName() { return null; }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) { return null; }
    public ContentResolver getContentResolver() { return null; }
    public java.io.File getFilesDir() { return null; }
    public java.io.File getCacheDir() { return null; }
    public java.io.File getExternalFilesDir(String type) { return null; }
    public void startActivity(Intent intent) {}
    public android.content.pm.ApplicationInfo getApplicationInfo() { return null; }
    public android.content.res.AssetManager getAssets() { return null; }
    public void unbindService(ServiceConnection connection) {}
    public static final int BIND_AUTO_CREATE = 0x00000001;
    public boolean bindService(Intent intent, ServiceConnection connection, int flags) { return false; }
}
