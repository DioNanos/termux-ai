package com.termux.app.terminal.ai;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The bounded protocol 2 response writer. Every frame is one JSON object on
 * one line: {@code begin}, ordered {@code chunk}s, {@code end}, or a single
 * {@code error} frame when the exchange has no streamed answer. Each frame
 * carries the protocol version, the request id and a per-exchange sequence
 * number that starts at 0 and grows by one on every frame.
 *
 * <p>Bounded twice, on the server's own budget and independently of any
 * client contract:
 * <ul>
 *   <li>the combined out+err payload, summed across every chunk call, is
 *       capped by {@link AiV2Limits#MAX_SERVER_PAYLOAD_CHARS};
 *   <li>the encoded answer buffer — frames, separators and the up-to-
 *       sixfold escaping of control characters included — is capped by
 *       {@link AiV2Limits#MAX_SERVER_BUFFER_CHARS}, checked before every
 *       append that would cross it.
 * </ul>
 * A cap is never crossed: a piece that would cross one drops the answer
 * written so far and replaces it with the single enumerated
 * {@link AiV2Error#RESPONSE_TOO_LARGE} error frame — no end frame, no
 * ok:true, no partial stdout a client could take for success. The command
 * may have run by then; that code says nothing about its effects and is
 * not retryable.
 *
 * <p>Per frame: a chunk data payload above {@link AiV2Limits#MAX_CHUNK_CHARS}
 * characters is split into ordered chunks at a code point boundary, so a
 * surrogate pair is never cut in half. The JSON encoding escapes every
 * newline, quote and control character of the payload, so a frame is always
 * exactly one line; with the worst per-character escaping the largest frame
 * stays below {@link AiV2Limits#MAX_CLIENT_FRAME_BYTES}.
 *
 * <p>No raw exception message, command output or request payload is ever
 * part of a frame: the only free-form strings are the fixed diagnostics of
 * {@link AiV2Error} and the data the caller explicitly streams out.
 */
public final class AiV2ResponseWriter {

    /** Stream name for standard output. */
    public static final String STREAM_OUT = "out";
    /** Stream name for standard error. */
    public static final String STREAM_ERR = "err";

    private final StringBuilder frames = new StringBuilder();
    private final String requestId;
    private int seq;
    private int payloadChars;
    private boolean overflowed;

    /** @param requestId the validated id echoed by every frame of this exchange. */
    public AiV2ResponseWriter(String requestId) {
        this.requestId = requestId;
    }

    /** Writes the opening frame of an exchange that reached the dispatch. */
    public void begin() {
        if (overflowed) return;
        try {
            writeFrame(base("begin"), 0);
        } catch (JSONException ignored) {
            writeInternalErrorFallback();
        }
    }

    /**
     * Streams {@code data} on {@code stream} as one or more ordered chunks,
     * each within {@link AiV2Limits#MAX_CHUNK_CHARS}. An empty payload
     * writes nothing (an empty stream is a begin followed by end). The
     * pieces are counted against the aggregate payload bound, across every
     * call: a piece that would cross it overflows the whole answer.
     */
    public void chunk(String stream, String data) {
        if (overflowed) return;
        if (data == null || data.isEmpty()) return;
        int offset = 0;
        while (offset < data.length() && !overflowed) {
            int end = splitAt(data, offset);
            int piece = end - offset;
            if (payloadChars + piece > AiV2Limits.MAX_SERVER_PAYLOAD_CHARS) {
                overflow();
                return;
            }
            try {
                JSONObject frame = base("chunk");
                frame.put("stream", stream).put("data", data.substring(offset, end));
                writeFrame(frame, piece);
            } catch (JSONException ignored) {
                writeInternalErrorFallback();
                return;
            }
            offset = end;
        }
    }

    /** Writes the closing frame of a successful exchange. */
    public void endSuccess(int exit) {
        if (overflowed) return;
        try {
            JSONObject frame = base("end");
            frame.put("ok", true).put("exit", exit);
            writeFrame(frame, 0);
        } catch (JSONException ignored) {
            writeInternalErrorFallback();
        }
    }

    /**
     * Writes the closing frame of an exchange whose command ran and failed:
     * the enumerated code replaces any raw error string the command produced.
     */
    public void endFailure(AiV2Error error, int exit) {
        if (overflowed) return;
        try {
            JSONObject frame = base("end");
            frame.put("ok", false).put("exit", exit)
                .put("code", error.name()).put("diagnostic", error.diagnostic());
            writeFrame(frame, 0);
        } catch (JSONException ignored) {
            writeInternalErrorFallback();
        }
    }

    /**
     * Writes the single error frame. This also serves
     * {@link AiV2Error#INTERNAL_AFTER_DISPATCH}, where the legacy dispatcher
     * already ran and the effect is unknown: the frame never claims the
     * command did not run and licenses no retry.
     */
    public void error(AiV2Error error) {
        if (overflowed) return;
        try {
            JSONObject frame = base("error");
            frame.put("code", error.name()).put("diagnostic", error.diagnostic());
            writeFrame(frame, 0);
        } catch (JSONException ignored) {
            writeInternalErrorFallback();
        }
    }

    /**
     * The frames written so far, one JSON object per line. No trailing
     * newline: the framing handler appends the final one.
     */
    @Override public String toString() {
        return frames.toString();
    }

    private JSONObject base(String type) throws JSONException {
        JSONObject frame = new JSONObject();
        frame.put("v", 2).put("type", type).put("requestId", requestId).put("seq", seq);
        seq++;
        return frame;
    }

    /**
     * Appends one encoded frame. The encoded buffer bound is checked BEFORE
     * the append that would cross it: a frame that does not fit overflows
     * the whole answer instead of growing the buffer past its cap.
     *
     * @param payloadCharsAdded the payload characters this frame carries
     *                          (0 for frames with no data field)
     */
    private void writeFrame(JSONObject frame, int payloadCharsAdded) {
        String line = frame.toString();
        int extra = line.length() + (frames.length() > 0 ? 1 : 0);
        if (frames.length() + extra > AiV2Limits.MAX_SERVER_BUFFER_CHARS) {
            overflow();
            return;
        }
        if (frames.length() > 0) frames.append('\n');
        frames.append(line);
        payloadChars += payloadCharsAdded;
    }

    /**
     * An aggregate bound was about to be crossed: the answer written so far
     * is dropped and replaced by one enumerated error frame. No partial
     * stdout, no end frame, nothing a client could read as success — and
     * every later call on this writer is a no-op.
     */
    private void overflow() {
        overflowed = true;
        frames.setLength(0);
        try {
            JSONObject frame = new JSONObject();
            frame.put("v", 2).put("type", "error").put("requestId", requestId).put("seq", 0)
                .put("code", AiV2Error.RESPONSE_TOO_LARGE.name())
                .put("diagnostic", AiV2Error.RESPONSE_TOO_LARGE.diagnostic());
            frames.append(frame.toString());
        } catch (JSONException impossible) {
            writeInternalErrorFallback();
        }
    }

    /**
     * The end of a chunk, never splitting a surrogate pair: the limit, or one
     * less when the limit would cut between a high and a low surrogate.
     */
    private static int splitAt(String data, int offset) {
        int limit = offset + AiV2Limits.MAX_CHUNK_CHARS;
        if (limit >= data.length()) return data.length();
        if (Character.isHighSurrogate(data.charAt(limit - 1))
            && Character.isLowSurrogate(data.charAt(limit))) {
            return limit - 1;
        }
        return limit;
    }

    /**
     * Unreachable safety net (the puts above only use finite strings and
     * primitives): writes a valid enumerated frame instead of a broken line.
     */
    private void writeInternalErrorFallback() {
        if (frames.length() > 0) frames.append('\n');
        frames.append("{\"v\":2,\"type\":\"error\",\"requestId\":\"\",\"seq\":").append(seq)
            .append(",\"code\":\"INTERNAL\",\"diagnostic\":\"internal error while serving the request\"}");
    }
}
