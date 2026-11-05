package android.provider;

// Signature stub.
public final class Settings {
    public static final String ACTION_MANAGE_OVERLAY_PERMISSION = "android.settings.action.MANAGE_OVERLAY_PERMISSION";
    public static boolean canDrawOverlays(android.content.Context context) { return true; }
    public static final class System {
        public static final String SCREEN_BRIGHTNESS = "screen_brightness";
        public static final String SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode";
        public static final int SCREEN_BRIGHTNESS_MODE_AUTOMATIC = 1;
        public static final int SCREEN_BRIGHTNESS_MODE_MANUAL = 0;
        public static boolean canWrite(android.content.Context context) { return true; }
        public static boolean putInt(android.content.ContentResolver cr, String name, int value) { return true; }
        public static int getInt(android.content.ContentResolver cr, String name, int def) { return def; }
    }
    private Settings() {}
}
