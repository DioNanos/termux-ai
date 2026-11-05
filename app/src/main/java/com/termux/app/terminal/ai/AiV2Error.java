package com.termux.app.terminal.ai;

/**
 * The enumerated failures of the protocol 2 path. Every error the new path
 * can produce is one of these codes with a fixed diagnostic: no raw
 * exception message, no request payload and no command output ever reaches
 * a protocol 2 response or a log line of this path.
 *
 * <p>The legacy protocol 1 path keeps its own error strings untouched.
 */
public enum AiV2Error {

    /** The request declared a protocol version this server does not speak. */
    UNKNOWN_PROTOCOL("unsupported protocol version"),

    /** The framed request is over the protocol 2 request limit. */
    REQUEST_TOO_LARGE("request exceeds the protocol 2 limit"),

    /** requestId is missing, not a string, empty or too long. */
    BAD_REQUEST_ID("requestId must be a non-empty string of at most "
        + AiV2Limits.MAX_REQUEST_ID_CHARS + " characters"),

    /** cmd is missing, not a string, empty or too long. */
    BAD_CMD("cmd must be a non-empty string of at most "
        + AiV2Limits.MAX_CMD_CHARS + " characters"),

    /** args is present but is not a JSON object. */
    BAD_ARGS("args must be a JSON object when present"),

    /** input is present but is not a JSON string. */
    BAD_INPUT("input must be a JSON string when present"),

    /**
     * input is a valid string but this server cannot feed stdin to the
     * command yet: nothing was dispatched and nothing ran.
     */
    INPUT_UNSUPPORTED("stdin input is not served by this server version"),

    /** cmd is well formed but is not a command this server knows. */
    UNKNOWN_COMMAND("cmd is not a known command"),

    /** The command ran and reported a failure; see the end frame exit code. */
    COMMAND_FAILED("the command did not complete successfully"),

    /**
     * The answer of an exchange crossed a server response bound: the
     * command may have run, none of its output is published, and this code
     * is not a license to retry.
     */
    RESPONSE_TOO_LARGE("the answer exceeds the protocol 2 response limit"),

    /** The exchange could not be served; nothing about the cause is exposed. */
    INTERNAL("internal error while serving the request"),

    /**
     * An internal failure struck after the command had been dispatched: the
     * command may have had its effect, this frame does not mean it did not
     * run, and a client must not retry on it.
     */
    INTERNAL_AFTER_DISPATCH("internal error after the command ran;"
        + " it may have had effects and retrying is unsafe");

    private final String diagnostic;

    AiV2Error(String diagnostic) {
        this.diagnostic = diagnostic;
    }

    /** The fixed, enumerated diagnostic carried by the error frame. */
    public String diagnostic() {
        return diagnostic;
    }
}
