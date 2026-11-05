package android.os;

// Signature stub.
public abstract class PowerManager {
    public static final int PARTIAL_WAKE_LOCK = 1;
    public abstract WakeLock newWakeLock(int levelAndFlags, String tag);
    public static final class WakeLock {
        public void acquire() {}
        public void acquire(long timeout) {}
        public void release() {}
        public boolean isHeld() { return false; }
    }
}
