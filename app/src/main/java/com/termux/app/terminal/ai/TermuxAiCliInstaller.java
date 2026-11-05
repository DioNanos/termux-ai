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
        installNamedAssets(context, TermuxConstants.TERMUX_BIN_PREFIX_DIR);
        writeManifest();
        deleteOldHelpers();
    }

    /** The helper scripts the app ships next to the CLI, each installed under its own name. */
    static final String[] NAMED_ASSETS = {"termux-ai-mcp", "termux-ai-serve"};

    static void installNamedAssets(Context context, File binDir) {
        for (String assetName : NAMED_ASSETS) installNamedAsset(context, assetName, new File(binDir, assetName));
    }

    /**
     * Assets whose installed copy must be provably the shipped one. The MCP shim is
     * the client half of the server's token check: an older copy cannot present the
     * token and is refused by the server, so a copy that could not be updated is
     * removed instead of being left to fail confusingly.
     */
    static final String[] VERIFIED_ASSETS = {"termux-ai-mcp"};

    private static void installNamedAsset(Context context, String assetName, File file) {
        if (java.util.Arrays.asList(VERIFIED_ASSETS).contains(assetName)) {
            installVerifiedAsset(context, assetName, file);
            return;
        }
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

    /** How the verified install replaces the shim; a seam so a failing swap can be tested. */
    interface Swap {
        void swap(java.nio.file.Path from, java.nio.file.Path to) throws java.io.IOException;
    }

    private static final Swap ATOMIC_SWAP = new Swap() {
        @Override public void swap(java.nio.file.Path from, java.nio.file.Path to) throws java.io.IOException {
            java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    };

    private static final int NAME_ATTEMPTS = 4;

    private static final java.util.Set<java.nio.file.attribute.PosixFilePermission> SHIM_MODE =
        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");

    static boolean installVerifiedAsset(Context context, String assetName, File target) {
        return installVerifiedAsset(context, assetName, target, ATOMIC_SWAP);
    }

    /**
     * Installs the asset so that the file at {@code target} is provably the shipped bytes or is gone.
     *
     * <p>Only a regular file (or nothing) may be at {@code target}: a symlink, a directory or anything
     * else is refused and left exactly as found. The copy is written to a fresh, uniquely named
     * temporary file created exclusively without following links, so nothing that already exists
     * (a link, a foreign file, an earlier fixed-name leftover) is ever opened, truncated or
     * chmod-ed. The copy is qualified (regular file, exact mode, bytes equal to the asset), swapped
     * in with one atomic move and qualified again at {@code target}, still without following links.
     * A name that is already taken is retried a few times with a new name and never removed. Cleanup only
     * touches the temporary file this call created and, after a failure, the regular
     * file at {@code target}; a stale shim that cannot be removed is reported, never called absent.
     *
     * @return true when the installed file is executable and byte-identical to the asset
     */
    static boolean installVerifiedAsset(Context context, String assetName, File target, Swap swap) {
        java.nio.file.Path targetPath = target.toPath();
        // Set ONLY once this call has created the temporary exclusively: the cleanup below may delete
        // nothing else, so a name that was already taken (EEXIST) never loses its file.
        java.nio.file.Path aside = null;
        boolean ok = false;
        try {
            java.nio.file.LinkOption noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS;
            if (java.nio.file.Files.exists(targetPath, noFollow)
                && !java.nio.file.Files.readAttributes(targetPath, java.nio.file.attribute.BasicFileAttributes.class, noFollow).isRegularFile()) {
                Logger.logError(LOG_TAG, "install refused, target is not a regular file: " + assetName);
                return false;
            }
            for (int attempt = 0; aside == null; attempt++) {
                java.nio.file.Path candidate = targetPath.resolveSibling("." + assetName + ".new-" + randomSuffix());
                try {
                    java.nio.file.Files.createFile(candidate, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(SHIM_MODE));
                    aside = candidate;
                } catch (java.nio.file.FileAlreadyExistsException taken) {
                    // Whatever holds that name is not ours: never opened, never removed. A bounded retry
                    // with a new name, then give up.
                    if (attempt + 1 >= NAME_ATTEMPTS) throw taken;
                }
            }
            java.nio.file.Files.getFileAttributeView(aside, java.nio.file.attribute.PosixFileAttributeView.class, noFollow)
                .setPermissions(SHIM_MODE);
            try (InputStream in = context.getAssets().open(assetName);
                 java.nio.channels.FileChannel out = java.nio.channels.FileChannel.open(aside,
                     java.nio.file.StandardOpenOption.WRITE, noFollow)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buffer, 0, read);
                    while (bb.hasRemaining()) out.write(bb);
                }
                out.force(true);
            }
            if (!qualifiedCopy(context, assetName, aside)) throw new java.io.IOException();
            swap.swap(aside, targetPath);
            aside = null;
            ok = qualifiedCopy(context, assetName, targetPath);
        } catch (Exception e) {
            ok = false;
        } finally {
            if (aside != null) {
                try { java.nio.file.Files.deleteIfExists(aside); } catch (Exception ignored) { }
            }
        }
        if (!ok) {
            removeStaleShim(targetPath, assetName);
            Logger.logError(LOG_TAG, "install not verified: " + assetName);
        }
        return ok;
    }

    /** Removes a regular file left at the target after a failed install; says so if that is impossible. */
    private static void removeStaleShim(java.nio.file.Path target, String assetName) {
        try {
            java.nio.file.LinkOption noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS;
            if (!java.nio.file.Files.exists(target, noFollow)) return;
            if (!java.nio.file.Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class, noFollow).isRegularFile()) return;
            java.nio.file.Files.delete(target);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "stale shim not removed: " + assetName);
        }
    }

    /** A regular file (links not followed) with exactly the shim mode and the asset's bytes. */
    private static boolean qualifiedCopy(Context context, String assetName, java.nio.file.Path file) throws java.io.IOException {
        java.nio.file.LinkOption noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS;
        java.nio.file.attribute.BasicFileAttributes attrs =
            java.nio.file.Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, noFollow);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink()) return false;
        if (!SHIM_MODE.equals(java.nio.file.Files.getPosixFilePermissions(file, noFollow))) return false;
        try (InputStream a = context.getAssets().open(assetName);
             InputStream b = java.nio.file.Files.newInputStream(file, noFollow)) {
            byte[] x = new byte[8192];
            byte[] y = new byte[8192];
            for (;;) {
                int nx = readFully(a, x);
                int ny = readFully(b, y);
                if (nx != ny) return false;
                if (nx == 0) return true;
                for (int i = 0; i < nx; i++) {
                    if (x[i] != y[i]) return false;
                }
            }
        }
    }

    private static String randomSuffix() {
        byte[] raw = new byte[8];
        new java.security.SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte v : raw) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    private static int readFully(InputStream in, byte[] buf) throws java.io.IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
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
