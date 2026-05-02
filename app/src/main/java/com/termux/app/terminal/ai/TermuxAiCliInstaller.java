package com.termux.app.terminal.ai;

import android.content.Context;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class TermuxAiCliInstaller {

    private static final String LOG_TAG = "TermuxAiCliInstaller";
    private static final String CLI_ASSET_NAME = "termux-ai";
    private static final String[] OLD_HELPERS = {
        "termux-ai-info",
        "termux-ai-battery",
        "termux-ai-clipboard",
        "termux-ai-vibrate",
        "termux-ai-notify",
        "termux-ai-sensor"
    };

    private TermuxAiCliInstaller() {}

    public static void installIfPossible(Context context) {
        File binDir = TermuxConstants.TERMUX_BIN_PREFIX_DIR;
        if (!binDir.isDirectory()) return;

        installCli(context);
        deleteOldHelpers();
    }

    private static void installCli(Context context) {
        File file = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, CLI_ASSET_NAME);
        try (InputStream in = context.getAssets().open(CLI_ASSET_NAME);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            file.setExecutable(true, false);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install " + CLI_ASSET_NAME, e);
        }
    }

    private static void deleteOldHelpers() {
        for (String helper : OLD_HELPERS) {
            File file = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, helper);
            if (file.exists() && !file.delete()) {
                Logger.logWarn(LOG_TAG, "Failed to delete old helper " + helper);
            }
        }
    }
}
