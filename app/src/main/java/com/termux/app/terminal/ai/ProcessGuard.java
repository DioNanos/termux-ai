package com.termux.app.terminal.ai;

/**
 * The app has a second process for the LiteRT-LM engine. That process runs the {@code Application} too, and must
 * not start the socket server or the terminal infrastructure.
 */
public final class ProcessGuard {
    public static final String LITERT_SUFFIX = ":litert";

    private ProcessGuard() {}

    /** The process name from the bytes of {@code /proc/self/cmdline}: everything before the first NUL. */
    public static String parseCmdline(byte[] cmdline) {
        if (cmdline == null) return null;
        int end = 0;
        while (end < cmdline.length && cmdline[end] != 0) end++;
        return end == 0 ? null : new String(cmdline, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** This process's name, read from {@code /proc/self/cmdline} (works on every API level); null if unreadable. */
    public static String currentProcessName() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] buffer = new byte[512];
            int read = in.read(buffer);
            return read <= 0 ? null : parseCmdline(java.util.Arrays.copyOf(buffer, read));
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** True for the {@code :litert} process of the given package. */
    public static boolean isLitertProcess(String processName, String packageName) {
        return processName != null && packageName != null && processName.equals(packageName + LITERT_SUFFIX);
    }
}
