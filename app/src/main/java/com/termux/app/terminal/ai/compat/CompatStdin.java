package com.termux.app.terminal.ai.compat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Incremental stdin reader. Bytes are accumulated in chunks and the read is
 * refused as soon as the encoded size passes {@link CompatLimits#MAX_STDIN_BYTES}
 * — the cap is enforced while reading, never by materializing an unbounded
 * buffer first. Decoding to a String happens only after a
 * clean EOF, and a malformed UTF-8 tail is an enumerated error, not a
 * replacement character silently delivered to the dispatcher.
 */
public final class CompatStdin {
    private CompatStdin() {}

    public static final class StdinException extends IOException {
        public final CompatError code;
        public StdinException(CompatError code) {
            super(code.name());
            this.code = code;
        }
    }

    /** Reads the whole stream, bounded; returns the decoded text. */
    public static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (buffer.size() + n > CompatLimits.MAX_STDIN_BYTES) {
                throw new StdinException(CompatError.E_STDIN_TOO_LARGE);
            }
            buffer.write(chunk, 0, n);
        }
        return decodeStrict(buffer.toByteArray());
    }

    /** Strict UTF-8 decode: any malformed sequence is an error. */
    public static String decodeStrict(byte[] bytes) throws IOException {
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (!s.isEmpty() && !java.util.Arrays.equals(bytes, s.getBytes(StandardCharsets.UTF_8))) {
            throw new StdinException(CompatError.E_INVALID_RESPONSE);
        }
        return s;
    }
}
