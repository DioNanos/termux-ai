package android.hardware;

// Signature stub.
public abstract class SensorManager {
    public static final int SENSOR_DELAY_NORMAL = 3;
    public abstract java.util.List<Sensor> getSensorList(int type);
    public abstract Sensor getDefaultSensor(int type);
    public abstract boolean registerListener(SensorEventListener listener, Sensor sensor, int samplingPeriodUs);
    public abstract void unregisterListener(SensorEventListener listener);
}
