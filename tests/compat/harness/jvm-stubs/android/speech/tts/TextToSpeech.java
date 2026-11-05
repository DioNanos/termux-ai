package android.speech.tts;

// Signature stub.
public class TextToSpeech {
    public static final int SUCCESS = 0;
    public static final int QUEUE_ADD = 1;
    public interface OnInitListener {
        void onInit(int status);
    }
    public static final class Engine {
        public static final String KEY_PARAM_STREAM = "stream";
    }
    public static final class EngineInfo {
        public String name;
        public String label;
    }
    public TextToSpeech(android.content.Context context, OnInitListener listener) {}
    public TextToSpeech(android.content.Context context, OnInitListener listener, String engine) {}
    public java.util.List<EngineInfo> getEngines() { return null; }
    public String getDefaultEngine() { return null; }
    public int speak(CharSequence text, int queueMode, android.os.Bundle params, String utteranceId) { return 0; }
    public int setPitch(float pitch) { return 0; }
    public int setSpeechRate(float rate) { return 0; }
    public int setLanguage(java.util.Locale loc) { return 0; }
    public void shutdown() {}
}
