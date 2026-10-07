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

    private AiSocketHandler() {}

    /** @return true when a request was dispatched. */
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

    static String error(String message, String kind) {
        try {
            return new JSONObject().put("ok", false).put("error", message).put("error_kind", kind).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"internal error\"}";
        }
    }
}
