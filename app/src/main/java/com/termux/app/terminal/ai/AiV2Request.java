package com.termux.app.terminal.ai;

import org.json.JSONObject;

/**
 * A protocol 2 request that passed wire validation. A request whose
 * {@code input} is present is <em>not</em> dispatchable yet: the exchange
 * refuses it before the dispatch until the compat adapter feeds the stdin to
 * the command, so a payload the transport would drop can never run.
 */
public final class AiV2Request {

    private final String requestId;
    private final String cmd;
    private final JSONObject args;
    private final String input;

    public AiV2Request(String requestId, String cmd, JSONObject args, String input) {
        this.requestId = requestId;
        this.cmd = cmd;
        this.args = args;
        this.input = input;
    }

    /** The client-chosen id echoed by every response frame. */
    public String requestId() {
        return requestId;
    }

    /** The command name, already checked against the known set. */
    public String cmd() {
        return cmd;
    }

    /** The arguments object; never null (defaults to empty). */
    public JSONObject args() {
        return args;
    }

    /**
     * The stdin payload, or null when the request carries none. Presence
     * semantics: an absent field and a JSON {@code null} are both absent
     * (null); any string is present, an empty string included — an empty
     * stdin is still a request of the stdin contract and is not guessed
     * away. The exchange refuses every present input before the dispatch.
     */
    public String input() {
        return input;
    }

    /** The legacy envelope shape of this request, for the shared dispatcher. */
    public JSONObject toLegacyRequest() {
        try {
            return new JSONObject().put("cmd", cmd).put("args", args);
        } catch (org.json.JSONException e) {
            // Cannot happen: cmd and args are already valid JSON values.
            throw new IllegalStateException("valid request became invalid JSON", e);
        }
    }
}
