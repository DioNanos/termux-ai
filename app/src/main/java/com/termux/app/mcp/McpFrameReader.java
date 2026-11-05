package com.termux.app.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Splits a byte stream into newline-delimited JSON-RPC frames with a hard size limit,
 * the same way on both sides of the MCP socket: the server's client loop (before any
 * authentication) and the stdio shim use this one reader, so a frame that one side
 * accepts is a frame the other side accepts.
 *
 * <p>A frame ends at LF. One trailing CR is stripped (CRLF). A CR anywhere else, or more
 * than {@value #MAX_FRAME_BYTES} bytes before the LF (the CR of a CRLF included), is
 * {@link Rejected}: nothing is buffered beyond the limit, so a sender that never ends
 * its line costs at most {@value #MAX_FRAME_BYTES} bytes plus one read buffer, and a
 * truncated line is never handed to a parser. The size is measured in bytes of the
 * wire encoding, not in characters.
 *
 * <p>No Android dependency: this class is loaded by the shim under {@code app_process}.
 */
final class McpFrameReader {

    static final int MAX_FRAME_BYTES = 1 << 20;
    private static final int READ_BUFFER = 8192;

    /** The stream carried a frame that must not be interpreted (too long, or a CR inside). */
    static final class Rejected extends Exception {
        Rejected() {
            super(null, null, false, false);
        }
    }

    private final InputStream in;
    private final byte[] buf = new byte[READ_BUFFER];
    private int pos;
    private int lim;
    private boolean eof;
    private byte[] acc = new byte[256];
    private int len;

    McpFrameReader(InputStream in) {
        this.in = in;
    }

    /**
     * The next frame without its line terminator (possibly empty), the unterminated
     * last frame at end of input, or null when the input is exhausted.
     */
    byte[] next() throws IOException, Rejected {
        len = 0;
        for (;;) {
            if (pos == lim) {
                if (eof) return len > 0 ? take() : null;
                int n = in.read(buf);
                if (n < 0) {
                    eof = true;
                    continue;
                }
                pos = 0;
                lim = n;
                continue;
            }
            byte b = buf[pos++];
            if (b == '\n') return take();
            if (len == MAX_FRAME_BYTES) throw new Rejected();
            if (len == acc.length) acc = Arrays.copyOf(acc, Math.min(acc.length * 2, MAX_FRAME_BYTES));
            acc[len++] = b;
        }
    }

    private byte[] take() throws Rejected {
        int n = len;
        len = 0;
        if (n > 0 && acc[n - 1] == '\r') n--;
        for (int i = 0; i < n; i++) {
            if (acc[i] == '\r') throw new Rejected();
        }
        return Arrays.copyOf(acc, n);
    }
}
