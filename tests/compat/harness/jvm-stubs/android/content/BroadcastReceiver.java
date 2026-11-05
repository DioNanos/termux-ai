package android.content;

// Signature stub.
public abstract class BroadcastReceiver {
    public abstract void onReceive(Context context, Intent intent);
    public final void setResultCode(int code) {}
    public final void setResultData(String data) {}
    public final PendingResult goAsync() { return null; }
    public static class PendingResult {
        public void setResultCode(int code) {}
        public void setResultData(String data) {}
        public void finish() {}
    }
}
