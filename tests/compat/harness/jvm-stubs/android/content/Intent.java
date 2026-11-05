package android.content;

import android.os.Bundle;

// Signature stub.
public class Intent {
    public static final String ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public Intent() {}
    public Intent(String action) {}
    public Intent(String action, android.net.Uri uri) {}
    public Intent(Context packageContext, Class<?> cls) {}
    public Intent setPackage(String packageName) { return this; }
    public Intent addFlags(int flags) { return this; }
    public String getAction() { return null; }
    public android.net.Uri getData() { return null; }
    public Bundle getExtras() { return null; }
    public int getIntExtra(String name, int defaultValue) { return defaultValue; }
    public boolean getBooleanExtra(String name, boolean defaultValue) { return defaultValue; }
    public String getStringExtra(String name) { return null; }
}
