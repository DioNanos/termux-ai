package android.media;

// Executable double of the platform AudioTrack for the audiotrack-fallback
// JVM suite. The PUBLIC SURFACE is exactly the faithful
// signature stub's one (platform API 23 methods, nothing added); only the
// BEHAVIOUR differs: it is steered through SYSTEM PROPERTIES so a harness
// test can inject a recoverable measurement failure, a fatal invariant
// failure (AssertionError) or a normal measurement — without any test-only
// field, hook or factory on a framework class. The double
// also counts release() calls in a property for the exactly-once assertion.
// Android-faithful behaviour stays CI material.
public class AudioTrack {
    public static final int PERFORMANCE_MODE_LOW_LATENCY = 1;
    public static final int PERFORMANCE_MODE_POWER_SAVING = 2;

    public int getSampleRate() {
        failIfRequested("measure");
        return 48000;
    }

    public int getBufferSizeInFrames() {
        failIfRequested("measure");
        return 256;
    }

    public void release() {
        String key = "termuxai.harness.audiotrack.releases";
        int n = Integer.parseInt(System.getProperty(key, "0")) + 1;
        System.setProperty(key, String.valueOf(n));
    }

    public static final class Builder {
        public Builder() {}
        public Builder setAudioAttributes(AudioAttributes attributes) { return this; }
        public Builder setBufferSizeInBytes(int bufferSizeInBytes) { return this; }
        public Builder setPerformanceMode(int performanceMode) { return this; }
        public AudioTrack build() {
            failIfRequested("build");
            return new AudioTrack();
        }
    }

    /** "ok" (default) builds/measures normally; "recoverable" throws a
     *  recoverable measurement failure; "fatal" throws an AssertionError —
     *  the invariant violation the old catch(Throwable) used to swallow. */
    private static void failIfRequested(String phase) {
        String mode = System.getProperty("termuxai.harness.audiotrack." + phase, "ok");
        if (mode.equals("recoverable")) {
            throw new IllegalStateException("synthetic recoverable failure in " + phase);
        }
        if (mode.equals("fatal")) {
            throw new AssertionError("synthetic invariant failure in " + phase);
        }
    }
}
