package com.termux.app.terminal.ai.litert;

/**
 * The cancel of ONE request. It is created when the request is accepted and belongs to it alone, so a cancel can
 * never reach the next request that happens to use the same engine: the engine keeps no cancel state of its own.
 * Once the generation has ended {@link #detach()} drops the action that stops the native inference, and a cancel
 * that arrives late only marks this finished token.
 */
public final class LitertCancelToken {
    private boolean cancelled;
    private Runnable action;

    public synchronized boolean isCancelled() { return cancelled; }

    /** Marks the request cancelled and, if an inference is attached, stops it. Safe from any thread; idempotent. */
    public void cancel() {
        Runnable stop;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            stop = action;
            action = null;
        }
        if (stop != null) stop.run();
    }

    /** Attaches the action that stops the running inference; it runs at once if the request is already cancelled. */
    public void onCancel(Runnable stop) {
        boolean runNow;
        synchronized (this) {
            runNow = cancelled;
            if (!runNow) action = stop;
        }
        if (runNow) stop.run();
    }

    /** The generation is over: a later cancel no longer has anything to stop. */
    public synchronized void detach() { action = null; }
}
