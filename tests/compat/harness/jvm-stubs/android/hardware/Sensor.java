package android.hardware;

// Signature stub.
public abstract class Sensor {
    public static final int TYPE_ALL = -1;
    public static final int TYPE_ACCELEROMETER = 1;
    public static final int TYPE_GYROSCOPE = 4;
    public static final int TYPE_LIGHT = 5;
    public static final int TYPE_PRESSURE = 6;
    public static final int TYPE_PROXIMITY = 8;
    public static final int TYPE_GRAVITY = 9;
    public static final int TYPE_MAGNETIC_FIELD = 2;
    public abstract String getName();
    public abstract int getType();
    public abstract String getVendor();
    public abstract float getMaximumRange();
    public abstract float getResolution();
    public abstract float getPower();
}
