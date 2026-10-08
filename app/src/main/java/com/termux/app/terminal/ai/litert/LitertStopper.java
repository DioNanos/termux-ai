package com.termux.app.terminal.ai.litert;

/**
 * Runs the native stop of a generation, never on the thread that asked for the cancel.
 *
 * <p>The cancel of a request arrives on the one IPC thread of the {@code :litert} service. If the native stop were
 * run there and it blocked, the service would answer nothing at all, not even a status. The stop therefore runs on
 * its own thread, and there is at most one in flight: while one is running (or stuck) another is refused, so a stop
 * that never returns holds exactly one thread and cannot pile up more.
 */
public final class LitertStopper {
    private boolean running;

    /**
     * Starts {@code stop} on a new daemon thread unless a stop is already in flight.
     *
     * @param whenDone runs after the stop has returned and this stopper is free again
     * @return false when a stop is already in flight; nothing was started
     */
    public boolean tryStart(Runnable stop, Runnable whenDone) {
        synchronized (this) {
            if (running) return false;
            running = true;
        }
        Thread thread = new Thread(() -> {
            try {
                stop.run();
            } finally {
                // Free first, then tell the owner: when the owner sees its stop finished, this stopper is free too.
                synchronized (LitertStopper.this) { running = false; }
                whenDone.run();
            }
        }, "litert-stop");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** Whether a native stop is running right now (a stuck one stays true until it returns). */
    public synchronized boolean isBusy() { return running; }
}
