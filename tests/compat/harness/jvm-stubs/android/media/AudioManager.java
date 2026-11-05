package android.media;

// Signature stub.
public abstract class AudioManager {
    public static final String PROPERTY_OUTPUT_SAMPLE_RATE = "android.media.property.OUTPUT_SAMPLE_RATE";
    public static final String PROPERTY_OUTPUT_FRAMES_PER_BUFFER = "android.media.property.OUTPUT_FRAMES_PER_BUFFER";
    public static final int STREAM_ALARM = 4;
    public static final int STREAM_MUSIC = 3;
    public static final int STREAM_NOTIFICATION = 5;
    public static final int STREAM_RING = 2;
    public static final int STREAM_SYSTEM = 1;
    public static final int STREAM_VOICE_CALL = 0;
    public abstract String getProperty(String key);
    public abstract boolean isBluetoothA2dpOn();
    public abstract boolean isWiredHeadsetOn();
    public abstract boolean isSpeakerphoneOn();
    public abstract boolean isMusicActive();
    public abstract int getStreamMaxVolume(int streamType);
    public abstract int getStreamVolume(int streamType);
    public abstract void setStreamVolume(int streamType, int index, int flags);
}
