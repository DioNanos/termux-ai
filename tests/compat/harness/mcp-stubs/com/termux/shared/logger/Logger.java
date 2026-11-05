package com.termux.shared.logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// Overlay stub for the audit harness: records every message so tests can prove
// that no payload ever reaches the log (logcat).
public final class Logger {
    public static final List<String> CAPTURED = new CopyOnWriteArrayList<>();
    public static void logErrorExtended(String tag, String message) { CAPTURED.add(tag + ": " + message); }
    public static void logDebug(String tag, String message) { CAPTURED.add(tag + ": " + message); }
    public static void logError(String tag, String message) { CAPTURED.add(tag + ": " + message); }
    public static void logVerbose(String tag, String message) { CAPTURED.add(tag + ": " + message); }
    public static void logWarn(String tag, String message) { CAPTURED.add(tag + ": " + message); }
    public static void logStackTraceWithMessage(String tag, String message, Exception e) { CAPTURED.add(tag + ": " + message); }
    private Logger() {}
}
