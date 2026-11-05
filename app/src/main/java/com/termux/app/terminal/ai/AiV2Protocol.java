package com.termux.app.terminal.ai;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;

/**
 * Negotiation and validation of protocol 2, applied to one framed request
 * <em>after</em> the framing and <em>before</em> any dispatch. The framing
 * reader and its 1 MiB outer bound are {@link AiRequestFraming}, reused
 * unchanged; this class never reads a byte.
 *
 * <p>Negotiation rule: a request is protocol 2 only when it parses as a
 * JSON object and carries {@code "protocol": 2}. A request without a
 * {@code protocol} key, or with {@code "protocol": 1}, is protocol 1 and
 * keeps the legacy semantics byte for byte — including a request that is
 * not JSON at all, which the legacy dispatcher answers with its own parse
 * error. Any other explicit {@code protocol} value (0, 3, a string, a
 * float, null, an object) is a protocol 2 client speaking an unknown
 * version and gets the enumerated {@link AiV2Error#UNKNOWN_PROTOCOL}
 * answer.
 *
 * <p>Validation order: protocol version, request size in UTF-8 bytes
 * ({@link AiV2Limits#MAX_REQUEST_BYTES_V2}, stricter than the 1 MiB outer
 * frame bound, and measured on the frame as it was read — before any trim,
 * so padding whitespace cannot hide frame bytes from the cap), requestId,
 * cmd shape, cmd membership in the known set, args, input. The first
 * failure wins; nothing is dispatched on failure. Unknown extra fields are
 * ignored, as the legacy path ignores them.
 */
public final class AiV2Protocol {

    private AiV2Protocol() {}

    /** The outcome of a negotiation: legacy, a valid request, or a v2 failure. */
    public static final class Negotiated {
        /** A valid protocol 2 request, ready to dispatch; null otherwise. */
        public final AiV2Request request;
        /** The enumerated failure to answer with; null otherwise. */
        public final AiV2Error error;
        /**
         * The requestId an error frame should echo: the id once it is known to
         * be a non-empty in-limit string, the empty string when validation
         * failed before that (or the request is not protocol 2 at all).
         */
        public final String requestIdEcho;

        private Negotiated(AiV2Request request, AiV2Error error, String requestIdEcho) {
            this.request = request;
            this.error = error;
            this.requestIdEcho = requestIdEcho;
        }

        static Negotiated legacy() {
            return new Negotiated(null, null, "");
        }

        static Negotiated ok(AiV2Request request) {
            return new Negotiated(request, null, request.requestId());
        }

        static Negotiated fail(AiV2Error error) {
            return new Negotiated(null, error, "");
        }

        static Negotiated fail(AiV2Error error, String requestIdEcho) {
            return new Negotiated(null, error, requestIdEcho);
        }

        /** True when the request is not protocol 2: serve it as legacy. */
        public boolean isLegacy() {
            return request == null && error == null;
        }
    }

    /**
     * Negotiates and validates one framed request against the known command
     * set. Never throws and never dispatches.
     *
     * @param frame the framed request, trimmed as the dispatcher will see it
     * @param frameBytes the UTF-8 size of the frame as it was read, before
     *                   any trim: the protocol 2 request cap is measured on
     *                   this, not on the trimmed text
     */
    public static Negotiated negotiate(String frame, int frameBytes, Set<String> knownCommands) {
        JSONObject request;
        try {
            // The legacy dispatcher treats an empty request as "{}"; here an
            // empty or non-object frame simply is not protocol 2.
            request = new JSONObject(frame);
        } catch (JSONException e) {
            return Negotiated.legacy();
        }
        Object protocol = request.opt("protocol");
        if (protocol == null) return Negotiated.legacy();
        if (protocol instanceof Integer) {
            int version = (Integer) protocol;
            if (version == 1) return Negotiated.legacy();
            if (version != 2) return Negotiated.fail(AiV2Error.UNKNOWN_PROTOCOL);
        } else {
            return Negotiated.fail(AiV2Error.UNKNOWN_PROTOCOL);
        }

        // Protocol 2 from here on. Own request cap first: the outer frame
        // bound is still 1 MiB (shared with protocol 1), this one is stricter
        // and checked after the framing, before the parse-sensitive fields.
        // The size is the wire size the caller measured before the trim: the
        // trimmed text cannot be re-measured to reconstruct it.
        if (frameBytes > AiV2Limits.MAX_REQUEST_BYTES_V2) {
            return Negotiated.fail(AiV2Error.REQUEST_TOO_LARGE);
        }

        Object requestId = request.opt("requestId");
        if (!(requestId instanceof String)
            || ((String) requestId).isEmpty()
            || ((String) requestId).length() > AiV2Limits.MAX_REQUEST_ID_CHARS) {
            return Negotiated.fail(AiV2Error.BAD_REQUEST_ID);
        }

        Object cmd = request.opt("cmd");
        if (!(cmd instanceof String)
            || ((String) cmd).isEmpty()
            || ((String) cmd).length() > AiV2Limits.MAX_CMD_CHARS) {
            return Negotiated.fail(AiV2Error.BAD_CMD, (String) requestId);
        }
        if (!knownCommands.contains((String) cmd)) {
            return Negotiated.fail(AiV2Error.UNKNOWN_COMMAND, (String) requestId);
        }

        JSONObject args = new JSONObject();
        Object rawArgs = request.opt("args");
        if (rawArgs != null && rawArgs != JSONObject.NULL) {
            if (!(rawArgs instanceof JSONObject)) return Negotiated.fail(AiV2Error.BAD_ARGS, (String) requestId);
            args = (JSONObject) rawArgs;
        }

        String input = null;
        Object rawInput = request.opt("input");
        if (rawInput != null && rawInput != JSONObject.NULL) {
            if (!(rawInput instanceof String)) return Negotiated.fail(AiV2Error.BAD_INPUT, (String) requestId);
            input = (String) rawInput;
        }

        return Negotiated.ok(new AiV2Request(
            (String) requestId, (String) cmd, args, input));
    }
}
