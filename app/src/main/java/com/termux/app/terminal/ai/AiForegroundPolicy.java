package com.termux.app.terminal.ai;

/**
 * The typed AICore foreground failures: our own codes (the SDK ones are
 * mirrored in {@code GenAiErrors}) and the remedy each blocking error names,
 * so every block reaches the CLI with something to do about it.
 */
public final class AiForegroundPolicy {

    /** The command could not bring the run surface up (permission missing or launch refused). */
    public static final String FOREGROUND_REQUIRED = "FOREGROUND_REQUIRED";
    public static final int FOREGROUND_REQUIRED_CODE = 2001;
    /** The engine refused the call because the app is not visibly in the foreground. */
    public static final String BACKGROUND_USE_BLOCKED = "BACKGROUND_USE_BLOCKED";

    public static final String REMEDY_FOREGROUND =
        "open the Termux AI app so it stays in the foreground, or grant Termux the Display over other apps permission";

    /** The remedy an error names; null when this error has none. */
    public static String remedyFor(String errorName) {
        if (FOREGROUND_REQUIRED.equals(errorName) || BACKGROUND_USE_BLOCKED.equals(errorName)) {
            return REMEDY_FOREGROUND;
        }
        return null;
    }

    private AiForegroundPolicy() {}
}
