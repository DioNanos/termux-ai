package android.app;

// Signature stub.
public abstract class NotificationManager {
    public static final int IMPORTANCE_DEFAULT = 3;
    public abstract void createNotificationChannel(NotificationChannel channel);
    public abstract void deleteNotificationChannel(String channelId);
    public abstract void notify(int id, Notification notification);
    public abstract void cancel(int id);
}
