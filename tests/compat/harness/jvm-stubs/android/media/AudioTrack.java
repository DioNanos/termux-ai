package android.media;

// Signature stub. Builder and the frame getters are API 23 and
// the signatures here are the platform ones, so the compile check catches
// wrong names/types against THIS stub surface. It does not prove the SDK
// guard: the stub is available at any simulated SDK level, so the 21/22
// runtime guard and the real API stay CI/device material.
public class AudioTrack {
    public static final int PERFORMANCE_MODE_LOW_LATENCY = 1;
    public static final int PERFORMANCE_MODE_POWER_SAVING = 2;
    public int getSampleRate() { return 0; }
    public int getBufferSizeInFrames() { return 0; }
    public void release() {}
    public static final class Builder {
        public Builder() {}
        public Builder setAudioAttributes(AudioAttributes attributes) { return this; }
        public Builder setBufferSizeInBytes(int bufferSizeInBytes) { return this; }
        public Builder setPerformanceMode(int performanceMode) { return this; }
        public AudioTrack build() { return null; }
    }
}
