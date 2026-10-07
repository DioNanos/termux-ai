package com.termux.app.terminal.ai;

/** The outcome of one generation as reported by the model client. */
public final class GenResult {
    public final String text;
    /** One of {@code stop}, {@code max_tokens}, {@code other}. */
    public final String finishReason;

    public GenResult(String text, String finishReason) {
        this.text = text == null ? "" : text;
        this.finishReason = finishReason;
    }
}
