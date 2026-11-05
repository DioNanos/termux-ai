package android.hardware;

// Signature stub.
public interface SensorEventListener {
    void onSensorChanged(SensorEvent event);
    void onAccuracyChanged(Sensor sensor, int accuracy);
}
