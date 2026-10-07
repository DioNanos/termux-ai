package com.termux.app.terminal.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads one request frame of the Termux AI socket: the bytes up to the first
 * newline. The peer is not expected to close its side, so the frame ends at the
 * newline and nothing is read after it. The size is limited in bytes and the
 * bytes are decoded as UTF-8 only once the frame is complete.
 *
 * <p>A peer that closes the connection after some bytes but without a newline
 * is accepted: that is the frame (older clients end the request by closing).
 */
public final class AiRequestFraming {

    /** Upper bound of one request, in bytes (a prompt plus a little envelope). */
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;

    /** One byte at a time: the read returns as soon as that byte is there. */
    public interface ByteSource {
        /** Reads one byte into {@code buffer[0]}; returns 1, or -1 at end of stream. */
        int read(byte[] buffer) throws IOException;
    }

    public enum Kind { EMPTY, TOO_LARGE, INVALID_UTF8, IO }

    /** A request that cannot be turned into a frame; nothing was dispatched. */
    public static final class FrameException extends Exception {
        public final Kind kind;

        FrameException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    private AiRequestFraming() {}

    public static String readFrame(ByteSource source, int maxBytes) throws FrameException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        byte[] one = new byte[1];
        boolean sawNewline = false;
        while (true) {
            int n;
            try {
                n = source.read(one);
            } catch (IOException e) {
                throw new FrameException(Kind.IO, "request not received: " + e.getMessage(), e);
            }
            if (n < 0) break;
            if (one[0] == '\n') {
                sawNewline = true;
                break;
            }
            if (frame.size() >= maxBytes) {
                throw new FrameException(Kind.TOO_LARGE,
                    "request larger than " + maxBytes + " bytes", null);
            }
            frame.write(one[0]);
        }
        if (frame.size() == 0 && !sawNewline) {
            throw new FrameException(Kind.EMPTY, "empty request", null);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(frame.toByteArray()))
                .toString();
        } catch (CharacterCodingException e) {
            throw new FrameException(Kind.INVALID_UTF8, "request is not valid UTF-8", e);
        }
    }
}
