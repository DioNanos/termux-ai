package com.termux.app.terminal.ai.litert;

import android.content.Context;
import android.os.Build;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.Arrays;

/** The app-wide {@link LitertEngine}: the Android values it needs, read once. */
public final class LitertApp {
    /** Under the Termux home, so a model is just a file the person put there. Never inside the APK. */
    public static final String MODELS_DIRECTORY = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.local/share/termux-ai/models";

    /** The settings of the local-model engine; read by the :litert process when it starts. */
    public static final String CONFIG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.config/termux-ai/litert.conf";

    private static LitertEngine instance;

    private LitertApp() {}

    public static LitertConfig config() { return new LitertConfig(new File(CONFIG_FILE)); }

    public static synchronized LitertEngine engine(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            String nativeDir = app.getApplicationInfo().nativeLibraryDir;
            LitertGuards guards = new LitertGuards(Build.VERSION.SDK_INT, Arrays.asList(Build.SUPPORTED_ABIS),
                name -> new File(nativeDir, name).isFile());
            instance = new LitertEngine(new LitertWorkerClient(app), new LitertModelCatalog(new File(MODELS_DIRECTORY)), guards,
                Build.VERSION.SDK_INT, nativeDir, new File(app.getCacheDir(), "litert").getPath(), LitertEngine.GENERATE_DEADLINE_MS);
        }
        return instance;
    }
}
