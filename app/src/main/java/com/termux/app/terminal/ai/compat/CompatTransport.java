package com.termux.app.terminal.ai.compat;

import java.io.IOException;

/**
 * Transport seam: the old stub
 * {@code termux-am} never exercised a Java LocalSocket client, so the parser
 * and the adapter are driven through this interface with a controlled peer.
 *
 * A transport sends exactly one request line (the caller has already encoded
 * and capped it) and returns the complete response bytes. It is responsible
 * for the idle/total timeouts against the supplied clock and for refusing
 * responses past {@link CompatLimits#MAX_RESPONSE_BYTES}. It never retries:
 * after the request has been sent a failure is reported as-is.
 */
public interface CompatTransport {
    /**
     * @param requestLine one JSON line, no trailing newline, already capped.
     * @param clock       controlled or system clock.
     * @return the complete response bytes (bounded, non-empty).
     * @throws CompatTransportException with the enumerated cause and a flag
     *                                  telling whether the request had already
     *                                  been written (no-retry invariant).
     */
    byte[] exchange(String requestLine, CompatClock clock) throws CompatTransportException;

    /** Typed transport failure carrying the {@link CompatError} code. */
    final class CompatTransportException extends IOException {
        public final CompatError code;
        public final boolean requestSent;

        public CompatTransportException(CompatError code, boolean requestSent, String detail) {
            super(detail);
            this.code = code;
            this.requestSent = requestSent;
        }
    }
}
