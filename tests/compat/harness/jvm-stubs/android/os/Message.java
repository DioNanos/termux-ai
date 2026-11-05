package android.os;

// Signature stub.
public final class Message {
    public int what;
    public int arg1;
    public int arg2;
    public Object obj;
    public Messenger replyTo;
    public Bundle data = new Bundle();
    public static Message obtain() { return null; }
    public static Message obtain(Message m) { return null; }
    public static Message obtain(Handler h, int what) { return null; }
    public static Message obtain(Handler h, int what, Object obj) { return null; }
    public static Message obtain(Handler h, int what, int arg1, int arg2) { return null; }
    public static Message obtain(Handler h, int what, int arg1, int arg2, Object obj) { return null; }
    public void setData(Bundle b) {}
    public Bundle getData() { return data; }
    public void sendToTarget() throws RemoteException {}
}
