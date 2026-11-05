package android.app;

// Signature stub.
public abstract class Service extends android.content.Context {
    public void onCreate() {}
    public abstract android.os.IBinder onBind(android.content.Intent intent);
    public int onStartCommand(android.content.Intent intent, int flags, int startId) { return 0; }
    public void onDestroy() {}
}
