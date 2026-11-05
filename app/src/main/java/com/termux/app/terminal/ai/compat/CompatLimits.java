package com.termux.app.terminal.ai.compat;

import java.nio.charset.StandardCharsets;

/**
 * Wire limits of the compat client. They bound the
 * client only; they are not the provider/server budget and do not change
 * the AICore/LiteRT timeouts or any legacy consumer.
 */
public final class CompatLimits {
    private CompatLimits() {}

    /** Request line: one JSON line, UTF-8 encoded, escaping included. */
    public static final int MAX_REQUEST_BYTES = 256 * 1024;
    /** Stdin is read incrementally and refused past the same encoded cap. */
    public static final int MAX_STDIN_BYTES = 256 * 1024;
    /** The v1 response is accumulated up to this many bytes, then refused. */
    public static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    /** Per-read idle timeout. */
    public static final long IDLE_TIMEOUT_MS = 5_000;
    /** Total deadline of one compat exchange (request + response). */
    public static final long TOTAL_DEADLINE_MS = 30_000;

    public static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
