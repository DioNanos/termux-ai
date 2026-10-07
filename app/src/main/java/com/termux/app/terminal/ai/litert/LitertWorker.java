package com.termux.app.terminal.ai.litert;

/**
 * The border between the main process and the {@code :litert} process. Requests and replies are the JSON of
 * {@link LitertRunner}. A worker that dies, or does not answer in time, is reported as a {@link LitertFailure}
 * and the request is never sent again.
 */
public interface LitertWorker {
    /**
     * Sends one request and waits for the reply.
     *
     * @throws LitertFailure MODEL_WORKER_DIED when the process or the connection is gone,
     *                       DEADLINE_EXCEEDED when no reply arrives within {@code timeoutMs}
     */
    String call(String requestJson, long timeoutMs) throws LitertFailure;

    /** Whether the {@code :litert} process is up and bound. Asking never starts it. */
    boolean isConnected();

    /** Asks the worker to stop the generation of a request; best effort, never blocks. */
    void cancel(String requestId);
}
