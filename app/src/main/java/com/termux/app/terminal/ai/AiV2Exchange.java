package com.termux.app.terminal.ai;

import org.json.JSONObject;

import java.util.Set;
import java.util.function.Function;

/**
 * The protocol 2 layer between the framing handler and the shared legacy
 * dispatcher. Applied to one already framed (and trimmed) request after the
 * read, before anything sensitive runs.
 *
 * <p><b>Gate.</b> While {@link #V2_ENABLED} is false — and it is false until
 * the remaining protocol stages close — this whole layer is inert: every frame is
 * served by the legacy dispatcher unchanged, explicit {@code protocol}
 * fields included, exactly as before this class existed. The version
 * boundary below (an explicit non-integer or unknown {@code protocol} value
 * answered with UNKNOWN_PROTOCOL) is part of the gated wire, not of the
 * legacy wire, where a {@code protocol} field is simply an ignored extra
 * field. Nothing legacy changes while the gate is closed.
 *
 * <p>With the gate open:
 *
 * <ul>
 *   <li>not protocol 2 (no protocol key, protocol 1, or not JSON at all):
 *       the frame goes to the legacy dispatcher unchanged, and its answer
 *       comes back unchanged — protocol 1 keeps its semantics byte for byte,
 *       including its own parse errors;
 *   <li>protocol 2 and invalid: a single enumerated error frame, nothing is
 *       dispatched;
 *   <li>protocol 2, valid, with an {@code input} field present (a JSON null
 *       counts as absent, an empty string does not): refused with the
 *       enumerated {@link AiV2Error#INPUT_UNSUPPORTED} before anything can
 *       run — the shared dispatcher has no stdin transport, and accepting a
 *       request whose input would silently vanish (for example a
 *       {@code sys.clipboard.set} whose text lives in {@code input}) must
 *       never answer success; the stdin transport arrives with the compat client stage;
 *   <li>protocol 2 and valid: the command runs exactly once through the
 *       shared dispatcher, and the answer is re-framed as begin, ordered
 *       out chunks, end. An ok=false legacy answer becomes end(ok=false,
 *       exit=1, COMMAND_FAILED) without the legacy error string; an internal
 *       failure that strikes after the dispatch (a throwing dispatcher, a
 *       non-JSON answer that cannot exist by construction) becomes the
 *       enumerated {@link AiV2Error#INTERNAL_AFTER_DISPATCH} error frame,
 *       which says nothing about whether the command ran and licenses no
 *       retry.
 * </ul>
 *
 * <p>The response of a protocol 2 exchange is several newline separated
 * frames with no trailing newline: the framing handler appends the final
 * one, so a client reading lines sees exactly one frame per line and no
 * stray newline is ever read as a second answer. The exit code is 0 for
 * success and 1 for a dispatched command that failed; the real process exit
 * of the compat commands arrives with the compat adapter.
 */
public final class AiV2Exchange {

    /**
     * False until the remaining protocol stages close. While it is false the protocol
     * 2 wire is not offered to consumers: the socket answers every frame
     * with the legacy dispatcher, byte for byte. Flipping this constant is
     * part of closing those stages, not of this one.
     */
    public static final boolean V2_ENABLED = false;

    private AiV2Exchange() {}

    /**
     * @param frame the framed request, already trimmed
     * @param frameBytes the UTF-8 size of the frame as it was read, before
     *                  the trim (the protocol 2 request cap is measured on
     *                  this, so padding cannot hide frame bytes from it)
     * @param legacyDispatcher the shared protocol 1 dispatcher, called at
     *                        most once and only for a frame that is either
     *                        legacy or a valid protocol 2 request
     * @param knownCommands the accepted protocol 2 command names
     */
    public static String exchange(String frame,
                                  int frameBytes,
                                  Function<String, String> legacyDispatcher,
                                  Set<String> knownCommands) {
        return exchange(frame, frameBytes, legacyDispatcher, knownCommands, V2_ENABLED);
    }

    /**
     * The same exchange with the gate explicit, for the tests of the gated
     * grammar and for the future enabled build.
     *
     * @param protocol2Enabled whether protocol 2 negotiation is active for
     *                          this call; the production default is
     *                          {@link #V2_ENABLED}
     */
    public static String exchange(String frame,
                                  int frameBytes,
                                  Function<String, String> legacyDispatcher,
                                  Set<String> knownCommands,
                                  boolean protocol2Enabled) {
        if (!protocol2Enabled) {
            // The gate is closed: the wire is the legacy wire, byte for
            // byte, protocol fields included (the legacy dispatcher ignores
            // them as extra fields).
            return legacyDispatcher.apply(frame);
        }
        AiV2Protocol.Negotiated negotiated = AiV2Protocol.negotiate(frame, frameBytes, knownCommands);
        if (negotiated.isLegacy()) {
            return legacyDispatcher.apply(frame);
        }
        AiV2ResponseWriter writer = new AiV2ResponseWriter(negotiated.requestIdEcho);
        if (negotiated.error != null) {
            writer.error(negotiated.error);
            return writer.toString();
        }
        if (negotiated.request.input() != null) {
            // Presence is decided by the wire, not by usefulness: a present
            // input (an empty string included) means the client is speaking
            // the protocol 2 stdin contract, which this server cannot honor
            // until the compat adapter. Refuse before the dispatch: accepting
            // would run the command with the input silently dropped.
            writer.error(AiV2Error.INPUT_UNSUPPORTED);
            return writer.toString();
        }
        String answer;
        try {
            answer = legacyDispatcher.apply(negotiated.request.toLegacyRequest().toString());
        } catch (RuntimeException e) {
            // The legacy dispatcher is not expected to throw (it has its own
            // catch-all), but if it does the protocol 2 answer stays bounded
            // and enumerated: no cause, no message. The command was already
            // handed over, so this failure does not mean it did not run.
            writer.error(AiV2Error.INTERNAL_AFTER_DISPATCH);
            return writer.toString();
        }
        JSONObject parsed;
        try {
            parsed = new JSONObject(answer);
        } catch (Exception e) {
            // Unreachable by construction, but the command has run by now:
            // the same post-effect error, never a "not executed" promise.
            writer.error(AiV2Error.INTERNAL_AFTER_DISPATCH);
            return writer.toString();
        }
        if (!parsed.optBoolean("ok", false)) {
            writer.begin();
            writer.endFailure(AiV2Error.COMMAND_FAILED, 1);
            return writer.toString();
        }
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, answer);
        writer.endSuccess(0);
        return writer.toString();
    }
}
