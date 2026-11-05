package com.termux.app.terminal.ai.compat;

/**
 * Enumerated, non-sensitive error codes of the compat client.
 *
 * Every failure path of {@link TermuxApiCompatClient} maps to exactly one of
 * these codes, and the stderr line is the code alone: a fixed vocabulary,
 * never bridge text, exception messages, paths or argv values.
 * Known causes are distinguished by the code itself; nothing received from
 * the bridge, the socket or the caller is ever echoed back. Exit codes are
 * documented on {@link TermuxApiCompatClient}.
 */
public enum CompatError {
    /** The invoked method is not in the 14-method allowlist. */
    E_UNKNOWN_METHOD,
    /** An option/flag the pinned wrappers never emit for this method. */
    E_UNSUPPORTED_OPTION,
    /** A typed extra (--ei/--ez/--ef/--ela/--eln) with a malformed value. */
    E_BAD_TYPE,
    /** Missing or excess positional arguments for the method. */
    E_BAD_USAGE,
    /** A known option with an out-of-domain value (e.g. bad stream name). */
    E_BAD_VALUE,
    /** Stdin exceeded the incremental read cap before EOF. */
    E_STDIN_TOO_LARGE,
    /** The assembled request line exceeded the 256 KiB UTF-8 cap. */
    E_REQUEST_TOO_LARGE,
    /** The bridge socket could not be connected. */
    E_CONNECT,
    /** The peer closed the stream before a complete response. */
    E_EOF,
    /** Idle or total deadline exceeded while waiting for the response. */
    E_TIMEOUT,
    /** The response crossed the 8 MiB cap; never printed partially. */
    E_RESPONSE_TOO_LARGE,
    /** The response bytes are not valid UTF-8 / JSON / envelope. */
    E_INVALID_RESPONSE,
    /** The bridge answered ok:false (or a nested data.error): no stdout. */
    E_BRIDGE_ERROR,
    /**
     * The client could not assemble or encode its own request (defensive:
     * e.g. an org.json checked failure after validation). Never carries
     * bridge or argv data.
     */
    E_INTERNAL;

    /**
     * The one stderr line of a failure: the code, nothing else. No received
     * text (bridge error, exception message, path, argv value) is ever
     * appended, so the whole diagnostic surface is this closed vocabulary.
     */
    public String stderrLine() {
        return name();
    }
}
