package com.termux.app.terminal.ai;

/** An AICore error with its name, code and the delay the SDK asks to wait. */
public class AiCoreFailure extends Exception {
    private static final long serialVersionUID = 1L;

    public final String name;
    public final int code;
    /** Milliseconds, or -1 when the SDK gave none. */
    public final long retryDelayMs;

    public AiCoreFailure(String name, int code, long retryDelayMs, String message, Throwable cause) {
        super(message, cause);
        this.name = name;
        this.code = code;
        this.retryDelayMs = retryDelayMs;
    }
}
