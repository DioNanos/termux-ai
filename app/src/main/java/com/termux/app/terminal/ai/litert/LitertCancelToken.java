package com.termux.app.terminal.ai.litert;

/**
 * The cancel of ONE request. It is created when the request is accepted and belongs to it alone, so a cancel can
 * never reach the next request that happens to use the same engine: the engine keeps no cancel state of its own.
 *
 * <p>The stop (the native call that interrupts an inference) runs on the {@link LitertStopper} thread, never on the
 * thread that cancels and never under the lock, so it can take as long as it needs without blocking the caller (the
 * service's only IPC thread) or anyone else. What must never happen is the conversation being closed
 * while that stop is still running, or a stop starting on a conversation that is already closed. {@link #finish}
 * is the end of a generation: it first detaches the stop (no new stop can start after it) and then waits, up to a
 * declared limit, for a stop that is already running; only then does it let the conversation be closed.
 */
public final class LitertCancelToken {
    /** The longest a generation waits for a running native stop before giving up on closing its conversation. */
    public static final long STOP_WAIT_MS = 5_000L;

    private final LitertStopper stopper;
    private boolean cancelled;
    private Runnable action;
    /** A stop has been handed to the stopper and has not returned yet. */
    private boolean stopping;

    public LitertCancelToken(LitertStopper stopper) { this.stopper = stopper; }

    public synchronized boolean isCancelled() { return cancelled; }

    /**
     * Marks the request cancelled and, if an inference is attached, hands its stop to the stopper and returns at
     * once. Safe from any thread; idempotent.
     */
    public synchronized void cancel() {
        if (cancelled) return;
        cancelled = true;
        Runnable stop = action;
        action = null;
        if (stop != null) handOff(stop);
    }

    /** Attaches the action that stops the running inference; it is handed to the stopper at once if already cancelled. */
    public synchronized void onCancel(Runnable stop) {
        if (cancelled) {
            handOff(stop);
        } else {
            action = stop;
        }
    }

    /** Called with the lock held. Taking the stop and marking it running is one step, so finish() cannot miss it. */
    private void handOff(Runnable stop) {
        stopping = true;
        if (!stopper.tryStart(stop, this::stopReturned)) {
            // Another stop is in flight (one per engine): this cancel marks the request and nothing more.
            stopping = false;
        }
    }

    private synchronized void stopReturned() {
        stopping = false;
        notifyAll();
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
