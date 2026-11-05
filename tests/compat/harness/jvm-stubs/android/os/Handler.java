package android.os;

// Signature stub.
public class Handler {
    public Handler() {}
    public Handler(Looper looper) {}
    public Handler(Callback callback) {}
    public Handler(Looper looper, Callback callback) {}
    public void handleMessage(Message msg) {}
    public final boolean sendMessage(Message msg) { return true; }
    public final boolean post(Runnable r) { return true; }
    public final boolean postDelayed(Runnable r, long delayMillis) { return true; }
    public final void removeCallbacks(Runnable r) {}
    public final Message obtainMessage(int what, Object obj) { return null; }
    public final Message obtainMessage(int what) { return null; }
    public interface Callback {
        boolean handleMessage(Message msg);
    }
}
