package android.hardware.camera2;

import java.util.HashMap;
import java.util.Map;

// Executable double of the platform CameraCharacteristics for the
// camera-producer JVM suite. The PUBLIC SURFACE (constants
// and the generic get) is exactly the faithful signature stub's one; only
// the behaviour differs: get() answers from a per-instance value map keyed
// by Key IDENTITY, so a harness test can hand the REAL backend code
// controlled synthetic measurements (LENS_FACING, FLASH_INFO_AVAILABLE, …)
// without any hook on a framework class. The harness
// constructor below the platform surface exists ONLY in this double: the
// production code never constructs a CameraCharacteristics, it only reads
// one from CameraManager. Android-faithful behaviour stays CI material.
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

    private final Map<Key<?>, Object> values = new HashMap<>();

    /** Harness-only constructor: builds the synthetic measurement set the
     *  get() below will serve to the production code. */
    public CameraCharacteristics(Map<Key<?>, Object> syntheticValues) {
        if (syntheticValues != null) values.putAll(syntheticValues);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        return (T) values.get(key);
    }
}
