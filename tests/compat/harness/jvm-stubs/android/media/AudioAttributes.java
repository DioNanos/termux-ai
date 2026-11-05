package android.media;

// Signature stub.
public final class AudioAttributes {
    public static final int USAGE_MEDIA = 1;
    public static final int CONTENT_TYPE_MUSIC = 2;
    public static final int FLAG_LOW_LATENCY = 256;
    public static final class Builder {
        public Builder() {}
        public Builder setUsage(int usage) { return this; }
        public Builder setContentType(int contentType) { return this; }
        public Builder setFlags(int flags) { return this; }
        public AudioAttributes build() { return null; }
    }
}
