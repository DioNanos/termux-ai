package com.termux.app.terminal.ai;

public interface AICoreStreamCallback {
    void onChunk(String text);
    void onComplete(String finishReason, int outputCharsApprox);
    void onError(Throwable error);
}
