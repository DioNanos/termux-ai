package com.termux.app.terminal.ai.litert;

/**
 * The cancel of ONE request. It is created when the request is accepted and belongs to it alone, so a cancel can
 * never reach the next request that happens to use the same engine: the engine keeps no cancel state of its own.
 *
 * <p>The stop (the native call that interrupts an inference) runs on the thread that cancels, outside the lock, so
 * it can take as long as it needs without blocking anyone. What must never happen is the conversation being closed
 * while that stop is still running, or a stop starting on a conversation that is already closed. {@link #finish}
 * is the end of a generation: it first detaches the stop (no new stop can start after it) and then waits, up to a
 * declared limit, for a stop that is already running; only then does it let the conversation be closed.
 */
public final class LitertCancelToken {
    /** The longest a generation waits for a running native stop before giving up on closing its conversation. */
    public static final long STOP_WAIT_MS = 5_000L;

    private boolean cancelled;
    private Runnable action;
    /** A stop has been taken and is running (or about to run) on some thread. */
    private boolean stopping;

    public synchronized boolean isCancelled() { return cancelled; }

    /** Marks the request cancelled and, if an inference is attached, stops it. Safe from any thread; idempotent. */
    public void cancel() {
        Runnable stop;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            stop = action;
            action = null;
            // Taking the action and marking it running is one step: finish() can never see "detached" and
            // "no stop" while a stop has already been taken.
            if (stop != null) stopping = true;
        }
        if (stop != null) runStop(stop);
    }

    /** Attaches the action that stops the running inference; it runs at once if the request is already cancelled. */
    public void onCancel(Runnable stop) {
        boolean runNow;
        synchronized (this) {
            runNow = cancelled;
            if (runNow) {
                stopping = true;
            } else {
                action = stop;
            }
        }
        if (runNow) runStop(stop);
    }

    private void runStop(Runnable stop) {
        try {
            stop.run();
        } finally {
            synchronized (this) {
                stopping = false;
                notifyAll();
            }
        }
    }

    /**
     * Detaches the stop, so that none can start from now on, and waits for one that is already running.
     *
     * @return true when no stop is running any more, false when {@code waitMs} passed (or the wait was interrupted)
     */
    public boolean detach(long waitMs) {
        synchronized (this) {
            action = null;
            long end = System.nanoTime() + waitMs * 1_000_000L;
            while (stopping) {
                long left = end - System.nanoTime();
                if (left <= 0) return false;
                try {
                    wait(left / 1_000_000L, (int) (left % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Ends the generation: after this returns normally no stop is running and none can start, so
     * {@code closeConversation} may run. If a stop is still running after {@link #STOP_WAIT_MS} the conversation is
     * NOT closed (closing it under a running native stop could free memory the stop is using) and CANCEL_TIMEOUT is
     * thrown; the conversation is left open on purpose.
     */
    public void finish(Runnable closeConversation) throws LitertFailure {
        finish(STOP_WAIT_MS, closeConversation);
    }

    void finish(long waitMs, Runnable closeConversation) throws LitertFailure {
        if (!detach(waitMs)) {
            throw new LitertFailure(LitertErrorCode.CANCEL_TIMEOUT, null, "generate", null,
                "the native cancel did not return within " + waitMs + " ms; the conversation was left open on purpose"
                    + " so that it is not closed under a running stop", null);
        }
        closeConversation.run();
    }
}
