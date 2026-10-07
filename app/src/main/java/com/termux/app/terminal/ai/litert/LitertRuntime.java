package com.termux.app.terminal.ai.litert;

/** What the worker needs from the LiteRT-LM SDK. The real one is {@code LiteRtLmRuntime}; tests use a fake. */
public interface LitertRuntime {
    /**
     * Opens an engine on exactly this backend. It never falls back to another one: a failure is the failure.
     *
     * @throws LitertFailure with the SDK's own message
     */
    Loaded load(String modelPath, LitertBackend backend, int contextTokens) throws LitertFailure;

    interface Loaded {
        /** Runs one generation on a fresh conversation, closed before this returns. Blocks until done or cancelled. */
        Output generate(String prompt, int maxTokens, double temperature, int topK, double topP, int seed) throws LitertFailure;

        /** Stops the generation that is running, if any. Safe to call from another thread. */
        void cancel();

        /** What the runtime could observe about the executor, as plain text; never a claim of execution. */
        String evidence();

        /** Closes the engine. Called with no generation running. */
        void close();
    }

    final class Output {
        public final String text;
        /** One of stop, max_tokens, other. */
        public final String finishReason;

        public Output(String text, String finishReason) {
            this.text = text == null ? "" : text;
            this.finishReason = finishReason;
        }
    }
}
