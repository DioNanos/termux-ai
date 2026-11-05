package com.termux.app.terminal.ai.compat;

/**
 * Clock seam so the idle/deadline behaviour of the client is testable with a
 * controlled clock on the JVM. Production uses the monotonic system clock.
 */
public interface CompatClock {
    long nowMs();

    CompatClock SYSTEM = new CompatClock() {
        @Override public long nowMs() {
            return System.nanoTime() / 1_000_000L;
        }
    };
}
