package android.hardware.camera2;

// Signature stub. The generic Key mechanism mirrors the platform
// signature so the get(...) calls compile against the same contract.
public final class CameraCharacteristics {
    public static final class Key<T> {
        public Key(String name, Class<T> type) {}
    }
    public static final Key<int[]> CONTROL_AE_AVAILABLE_MODES = new Key<>(null, int[].class);
    public static final Key<Boolean> FLASH_INFO_AVAILABLE = new Key<>(null, Boolean.class);
    public static final Key<Integer> LENS_FACING = new Key<>(null, Integer.class);
    public static final int LENS_FACING_FRONT = 0;
    public static final int LENS_FACING_BACK = 1;
    public static final int LENS_FACING_EXTERNAL = 2;
    public static final Key<float[]> LENS_INFO_AVAILABLE_FOCAL_LENGTHS = new Key<>(null, float[].class);
    public static final Key<android.util.SizeF> SENSOR_INFO_PHYSICAL_SIZE = new Key<>(null, android.util.SizeF.class);
    public static final Key<int[]> REQUEST_AVAILABLE_CAPABILITIES = new Key<>(null, int[].class);
    public static final Key<android.hardware.camera2.params.StreamConfigurationMap> SCALER_STREAM_CONFIGURATION_MAP =
        new Key<>(null, android.hardware.camera2.params.StreamConfigurationMap.class);
    public <T> T get(Key<T> key) { return null; }
}
