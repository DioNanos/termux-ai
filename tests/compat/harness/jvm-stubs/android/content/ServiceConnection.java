package android.content;

// Signature stub: the two later default methods are defaults on
// the platform interface too.
public interface ServiceConnection {
    void onServiceConnected(ComponentName name, android.os.IBinder service);
    void onServiceDisconnected(ComponentName name);
    default void onBindingDied(ComponentName name) {}
    default void onNullBinding(ComponentName name) {}
}
