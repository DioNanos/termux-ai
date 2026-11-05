package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

// Overlay stub for the MCP harness: serves the shipped asset directory, so the
// installer is exercised on the real bytes. The platform class is final; this
// stand-in only exists in the harness classpath.
public class AssetManager {
    public static volatile File ROOT;
    /** Test hook: when set, supplies the stream instead of the asset directory. */
    public static volatile java.util.function.Function<String, InputStream> HOOK;
    public InputStream open(String fileName) throws IOException {
        java.util.function.Function<String, InputStream> h = HOOK;
        if (h != null) return h.apply(fileName);
        return new FileInputStream(new File(ROOT, fileName));
    }
}
