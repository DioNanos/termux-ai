package com.termux.app.terminal.ai;

import org.json.JSONObject;

import java.io.IOException;
import java.util.function.Function;

/**
 * One connection of the Termux AI socket: read a frame, dispatch it exactly
 * once, write the response and a newline. It never reads after the frame and
 * never dispatches a request that could not be framed.
 */
public final class AiSocketHandler {

    public interface ByteSink {
        void write(byte[] data) throws IOException;
    }

    /**
     * The dispatcher of one framed request. The frame arrives trimmed, as
     * the legacy dispatcher has always seen it; {@code frameBytes} is the
     * UTF-8 size of the frame as it was read, before that trim, so a
     * protocol bounding the request measures the real wire size and not the
     * normalized text (padding whitespace that the trim removes stays
     * invisible to a dispatcher that only sees the trimmed string).
     */
    public interface FrameDispatcher {
        String dispatch(String frame, int frameBytes);
    }

    private AiSocketHandler() {}

    /**
     * The legacy entry: the dispatcher sees only the trimmed frame, exactly
     * as before protocol 2 existed.
     *
     * @return true when a request was dispatched.
     */
    public static boolean handle(AiRequestFraming.ByteSource source, ByteSink sink,
                                 Function<String, String> dispatcher, int maxBytes) {
        String response;
        boolean dispatched = false;
        try {
            String request = AiRequestFraming.readFrame(source, maxBytes);
            dispatched = true;
            response = dispatcher.apply(request.trim());
        } catch (AiRequestFraming.FrameException e) {
            response = error("Read failed: " + e.getMessage(), e.kind.name().toLowerCase(java.util.Locale.ROOT));
        }
        try {
            sink.write((response + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The peer is gone; the request, if any, was dispatched once.
        }
        return dispatched;
    }

    /**
     * The entry that also hands the dispatcher the wire size of the frame.
     *
     * @return true when a request was dispatched.
     */
    public static boolean handle(AiRequestFraming.ByteSource source, ByteSink sink,
                                 FrameDispatcher dispatcher, int maxBytes) {
        String response;
        boolean dispatched = false;
        try {
            String request = AiRequestFraming.readFrame(source, maxBytes);
            dispatched = true;
            // The untrimmed text re-encoded is exactly the byte sequence of
            // the frame on the wire (the reader decodes strict UTF-8 and
            // drops only the terminating newline), so this is the frame's
            // real size, padding included: measured, not reconstructed from
            // the trimmed string after the fact.
            int frameBytes = request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            response = dispatcher.dispatch(request.trim(), frameBytes);
        } catch (AiRequestFraming.FrameException e) {
            response = error("Read failed: " + e.getMessage(), e.kind.name().toLowerCase(java.util.Locale.ROOT));
        }
        try {
            sink.write((response + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The peer is gone; the request, if any, was dispatched once.
        }
        return dispatched;
    }

    static String error(String message, String kind) {
        try {
            return new JSONObject().put("ok", false).put("error", message).put("error_kind", kind).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"internal error\"}";
        }
    }
}
