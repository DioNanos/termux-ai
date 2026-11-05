package com.termux.app.terminal.ai;

/**
 * Every bound of the protocol 2 path, in one table. The outer frame limit
 * stays {@link AiRequestFraming#MAX_REQUEST_BYTES} (1 MiB) because that is
 * also the protocol 1 limit. That 1 MiB is a bound on the request BYTES on
 * the wire, not on the heap: the read buffer grows in steps, the decoded
 * String, the parsed JSONObject and this writer's buffer (bounded by
 * {@link #MAX_SERVER_BUFFER_CHARS}) are further transient allocations on
 * top of it. Protocol 2 refuses its own requests above
 * {@link #MAX_REQUEST_BYTES_V2} after framing, before dispatch.
 */
public final class AiV2Limits {

    /**
     * Protocol 2 request limit, in UTF-8 bytes, applied to the frame as it
     * was read (padding included, before any trim). Below
     * {@link AiRequestFraming#MAX_REQUEST_BYTES}, which stays the outer
     * bound of the read.
     */
    public static final int MAX_REQUEST_BYTES_V2 = 256 * 1024;

    /** Longest accepted requestId, in characters. */
    public static final int MAX_REQUEST_ID_CHARS = 128;

    /** Longest accepted cmd, in characters. */
    public static final int MAX_CMD_CHARS = 64;

    /**
     * Largest chunk data payload the server writer emits, in characters;
     * larger payloads are split into ordered chunks. Characters (not bytes)
     * so a split can be taken at a code point boundary.
     */
    public static final int MAX_CHUNK_CHARS = 32 * 1024;

    /**
     * Server bound: the largest combined out+err payload the server writer
     * ever accepts for one exchange, in characters, summed across every
     * chunk call. A piece that would cross it is refused with
     * {@link AiV2Error#RESPONSE_TOO_LARGE}. This bounds the server on its
     * own, independently of the client contract below.
     */
    public static final int MAX_SERVER_PAYLOAD_CHARS = 2 * 1024 * 1024;

    /**
     * Server bound: the largest encoded answer buffer the writer may
     * accumulate for one exchange, in characters — frames, separators and
     * the up-to-sixfold escaping of control characters included. Checked
     * before every append that would cross it, so the memory of the writer
     * stays bounded whatever the command produced.
     */
    public static final int MAX_SERVER_BUFFER_CHARS = 4 * 1024 * 1024;

    /**
     * Client contract: the largest response line the client accepts, in
     * UTF-8 bytes. A server chunk is at most {@link #MAX_CHUNK_CHARS}
     * characters plus the frame envelope, so this bound only rejects a
     * misbehaving or hostile server.
     */
    public static final int MAX_CLIENT_FRAME_BYTES = 256 * 1024;

    /**
     * Client contract: the largest combined out+err payload the client
     * accumulates before giving up. Success is published only after a valid
     * end frame, so this is the bound of that accumulation.
     */
    public static final int MAX_CLIENT_TOTAL_BYTES = 8 * 1024 * 1024;

    private AiV2Limits() {}
}
