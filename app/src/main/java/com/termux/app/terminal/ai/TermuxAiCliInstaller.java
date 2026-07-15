package com.termux.app.terminal.ai;

import android.content.Context;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class TermuxAiCliInstaller {

    private static final String LOG_TAG = "TermuxAiCliInstaller";
    private static final String CLI_ASSET_NAME = "termux-ai";
    private static final String[] MANAGED_HELPERS = {
        "termux-ai",
        "termux-api",
        "termux-audio-info",
        "termux-battery-status",
        "termux-brightness",
        "termux-camera-info",
        "termux-clipboard-get",
        "termux-clipboard-set",
        "termux-notification",
        "termux-notification-channel",
        "termux-notification-remove",
        "termux-toast",
        "termux-torch",
        "termux-tts-engines",
        "termux-tts-speak",
        "termux-vibrate",
        "termux-volume",
        "termux-wifi-connectioninfo"
    };
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

        installManagedHelpers(context);
        installLibexecApi(context);
        installNamedAsset(context, "termux-ai-mcp",
            new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, "termux-ai-mcp"));
        writeManifest();
        deleteOldHelpers();
    }

    private static void installNamedAsset(Context context, String assetName, File file) {
        try (InputStream in = context.getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            file.setExecutable(true, false);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install " + assetName, e);
        }
    }

    private static void installManagedHelpers(Context context) {
        for (String helper : MANAGED_HELPERS) installCli(context, helper);
    }

    private static void installCli(Context context, String name) {
        File file = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, name);
        try (InputStream in = context.getAssets().open(CLI_ASSET_NAME);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            file.setExecutable(true, false);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install " + name, e);
        }
    }

    private static void installLibexecApi(Context context) {
        File libexecDir = TermuxConstants.TERMUX_LIBEXEC_PREFIX_DIR;
        if (!libexecDir.isDirectory() && !libexecDir.mkdirs()) return;
        installCliTo(context, new File(libexecDir, "termux-api"));
    }

    private static void installCliTo(Context context, File file) {
        try (InputStream in = context.getAssets().open(CLI_ASSET_NAME);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            file.setExecutable(true, false);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install " + file.getAbsolutePath(), e);
        }
    }

    private static void writeManifest() {
        File dir = new File(TermuxConstants.TERMUX_VAR_PREFIX_DIR, "lib/termux-ai");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File file = new File(dir, "api-tools.json");
        String manifest = "{\"version\":\"" + BuildConfig.VERSION_NAME + "\",\"managed\":[\"termux-ai\",\"termux-api\","
            + "\"termux-audio-info\",\"termux-battery-status\",\"termux-brightness\",\"termux-camera-info\","
            + "\"termux-clipboard-get\",\"termux-clipboard-set\",\"termux-notification\","
            + "\"termux-notification-channel\",\"termux-notification-remove\",\"termux-toast\","
            + "\"termux-torch\",\"termux-tts-engines\",\"termux-tts-speak\",\"termux-vibrate\","
            + "\"termux-volume\",\"termux-wifi-connectioninfo\"]}\n";
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(manifest.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write API tools manifest", e);
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
