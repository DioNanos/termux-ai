package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Negotiation and validation of protocol 2, applied after the framing and
 * before any dispatch. The 1 MiB outer frame bound belongs to the framing
 * reader; these tests only decide what the framed text IS.
 */
public class AiV2ProtocolTest {

    private static final Set<String> PING_ONLY = Collections.singleton("ping");

    private static AiV2Protocol.Negotiated negotiate(String frame) {
        return AiV2Protocol.negotiate(frame, rawBytes(frame), PING_ONLY);
    }

    private static int rawBytes(String frame) {
        return frame.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    // ---- negotiation: what is legacy ----

    @Test
    public void aRequestWithoutTheProtocolKeyIsLegacy() {
        assertTrue(negotiate("{\"cmd\":\"ping\"}").isLegacy());
    }

    @Test
    public void anEmptyObjectRequestIsLegacy() {
        assertTrue(negotiate("{}").isLegacy());
    }

    @Test
    public void protocolOneIsLegacy() {
        assertTrue(negotiate("{\"protocol\":1,\"cmd\":\"ping\"}").isLegacy());
    }

    @Test
    public void aRequestThatIsNotJsonObjectIsLegacyNotAV2Failure() {
        // The legacy dispatcher answers non-JSON frames with its own parse
        // error: negotiation must not steal that path.
        assertTrue(negotiate("not json at all").isLegacy());
        assertTrue(negotiate("[1,2,3]").isLegacy());
        assertTrue(negotiate("42").isLegacy());
        assertTrue(negotiate("null").isLegacy());
        assertTrue(negotiate("").isLegacy());
    }

    @Test
    public void aLegacyNegotiationCarriesNoRequestAndNoError() {
        AiV2Protocol.Negotiated negotiated = negotiate("{\"cmd\":\"ping\"}");
        assertNull(negotiated.request);
        assertNull(negotiated.error);
        assertEquals("", negotiated.requestIdEcho);
    }

    // ---- negotiation: unknown protocol versions ----

    @Test
    public void anExplicitUnknownIntegerVersionIsUnknownProtocol() {
        for (int version : new int[]{0, 3, -1, 100}) {
            assertEquals(AiV2Error.UNKNOWN_PROTOCOL,
                negotiate("{\"protocol\":" + version + "}").error);
        }
    }

    @Test
    public void aNonIntegerProtocolValueIsUnknownProtocol() {
        String[] frames = {
            "{\"protocol\":\"2\"}",
            "{\"protocol\":2.5}",
            "{\"protocol\":true}",
            "{\"protocol\":null}",
            "{\"protocol\":{}}",
            "{\"protocol\":[]}",
            "{\"protocol\":3000000000}"
        };
        for (String frame : frames) {
            assertEquals(frame, AiV2Error.UNKNOWN_PROTOCOL, negotiate(frame).error);
        }
    }

    // ---- validation of a protocol 2 request ----

    @Test
    public void aMinimalValidRequestPassesWithDefaults() {
        AiV2Protocol.Negotiated negotiated = negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}");
        assertNull(negotiated.error);
        assertEquals("r1", negotiated.request.requestId());
        assertEquals("ping", negotiated.request.cmd());
        assertEquals("args default to an empty object", 0, negotiated.request.args().length());
        assertNull("input defaults to absent", negotiated.request.input());
        assertEquals("r1", negotiated.requestIdEcho);
    }

    @Test
    public void argsAndInputAreCarriedWhenPresent() {
        AiV2Protocol.Negotiated negotiated = negotiate(
            "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\","
                + "\"args\":{\"text\":\"a \\\"quoted\\\" è 😀\"},\"input\":\"stdin payload\"}");
        assertEquals("a \"quoted\" è 😀", negotiated.request.args().opt("text"));
        assertEquals("stdin payload", negotiated.request.input());
    }

    @Test
    public void anExplicitNullArgsOrInputCountsAsAbsent() {
        AiV2Protocol.Negotiated negotiated = negotiate(
            "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"args\":null,\"input\":null}");
        assertNull(negotiated.error);
        assertEquals(0, negotiated.request.args().length());
        assertNull(negotiated.request.input());
    }

    @Test
    public void requestIdMustBeANonEmptyStringWithinTheLimit() {
        assertEquals(AiV2Error.BAD_REQUEST_ID, negotiate("{\"protocol\":2,\"cmd\":\"ping\"}").error);
        assertEquals(AiV2Error.BAD_REQUEST_ID,
            negotiate("{\"protocol\":2,\"requestId\":7,\"cmd\":\"ping\"}").error);
        assertEquals(AiV2Error.BAD_REQUEST_ID,
            negotiate("{\"protocol\":2,\"requestId\":\"\",\"cmd\":\"ping\"}").error);
        assertEquals(AiV2Error.BAD_REQUEST_ID,
            negotiate("{\"protocol\":2,\"requestId\":null,\"cmd\":\"ping\"}").error);
        assertEquals(AiV2Error.BAD_REQUEST_ID,
            negotiate("{\"protocol\":2,\"requestId\":\"" + repeat('x', AiV2Limits.MAX_REQUEST_ID_CHARS + 1)
                + "\",\"cmd\":\"ping\"}").error);
    }

    @Test
    public void aRequestIdAtTheLimitIsAccepted() {
        AiV2Protocol.Negotiated negotiated = negotiate(
            "{\"protocol\":2,\"requestId\":\"" + repeat('x', AiV2Limits.MAX_REQUEST_ID_CHARS)
                + "\",\"cmd\":\"ping\"}");
        assertNull(negotiated.error);
    }

    @Test
    public void aRequestIdIsTakenLiterallyNotReparsed() {
        // The two-character id "{}" is a string, not a JSON object.
        AiV2Protocol.Negotiated negotiated = negotiate(
            "{\"protocol\":2,\"requestId\":\"{}\",\"cmd\":\"ping\"}");
        assertNull(negotiated.error);
        assertEquals("{}", negotiated.request.requestId());
    }

    @Test
    public void cmdMustBeANonEmptyKnownStringWithinTheLimit() {
        assertEquals(AiV2Error.BAD_CMD, negotiate("{\"protocol\":2,\"requestId\":\"r1\"}").error);
        assertEquals(AiV2Error.BAD_CMD,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":9}").error);
        assertEquals(AiV2Error.BAD_CMD,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"\"}").error);
        assertEquals(AiV2Error.BAD_CMD,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\""
                + repeat('c', AiV2Limits.MAX_CMD_CHARS + 1) + "\"}").error);
        // a well formed cmd that the server does not know
        assertEquals(AiV2Error.UNKNOWN_COMMAND,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"sys.info\"}").error);
    }

    @Test
    public void membershipIsCheckedAgainstTheRealCommandSet() {
        AiV2Protocol.Negotiated negotiated = AiV2Protocol.negotiate(
            "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"bridge.version\"}",
            rawBytes("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"bridge.version\"}"), AiV2Commands.KNOWN);
        assertNull(negotiated.error);
        assertEquals("bridge.version", negotiated.request.cmd());
        String nope = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"nope\"}";
        assertEquals(AiV2Error.UNKNOWN_COMMAND,
            AiV2Protocol.negotiate(nope, rawBytes(nope), AiV2Commands.KNOWN).error);
    }

    @Test
    public void argsMustBeAJsonObjectWhenPresent() {
        assertEquals(AiV2Error.BAD_ARGS,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"args\":[1,2]}").error);
        assertEquals(AiV2Error.BAD_ARGS,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"args\":\"x=1\"}").error);
        assertEquals(AiV2Error.BAD_ARGS,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"args\":5}").error);
        assertEquals(AiV2Error.BAD_ARGS,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"args\":true}").error);
    }

    @Test
    public void inputMustBeAJsonStringWhenPresent() {
        assertEquals(AiV2Error.BAD_INPUT,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"input\":{\"a\":1}}").error);
        assertEquals(AiV2Error.BAD_INPUT,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"input\":4}").error);
        assertEquals(AiV2Error.BAD_INPUT,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"input\":[\"x\"]}").error);
    }

    // ---- the protocol 2 request cap, between the 1 MiB outer bound and dispatch ----

    @Test
    public void aProtocol2RequestOver256KiBIsRefusedAfterFraming() {
        // One ASCII byte per character: the frame is 262145 UTF-8 bytes,
        // far below the 1 MiB outer bound, over the protocol 2 limit.
        String over = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"pad\":\""
            + repeat('a', 256 * 1024) + "\"}";
        assertTrue(over.getBytes(StandardCharsets.UTF_8).length > AiV2Limits.MAX_REQUEST_BYTES_V2);
        assertEquals(AiV2Error.REQUEST_TOO_LARGE, negotiate(over).error);
    }

    @Test
    public void aProtocol2RequestAtExactly256KiBIsAccepted() {
        String prefix = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"pad\":\"";
        String suffix = "\"}";
        int pad = AiV2Limits.MAX_REQUEST_BYTES_V2 - prefix.length() - suffix.length();
        String atLimit = prefix + repeat('a', pad) + suffix;
        assertEquals(AiV2Limits.MAX_REQUEST_BYTES_V2, atLimit.getBytes(StandardCharsets.UTF_8).length);
        assertNull(negotiate(atLimit).error);
    }

    @Test
    public void theSameOversizedRequestIsLegacyWithoutTheProtocolKey() {
        // 300 KiB of payload: over the protocol 2 limit, well within the
        // 1 MiB outer bound of the framing reader, and protocol 1.
        String legacy = "{\"cmd\":\"ping\",\"pad\":\"" + repeat('a', 300 * 1024) + "\"}";
        assertTrue(negotiate(legacy).isLegacy());
    }

    @Test
    public void whitespacePaddingTowardTheCapIsMeasuredBeforeTheTrim() {
        // A short protocol 2 JSON padded with whitespace up to the cap: the
        // padding is part of the wire size, exactly at the limit it passes,
        // one byte more is refused.
        String json = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}";
        int pad = AiV2Limits.MAX_REQUEST_BYTES_V2 - rawBytes(json);
        String atLimit = json + repeat(' ', pad);
        assertEquals(AiV2Limits.MAX_REQUEST_BYTES_V2, rawBytes(atLimit));
        assertNull(negotiate(atLimit).error);
        String over = json + repeat(' ', pad + 1);
        assertEquals(AiV2Error.REQUEST_TOO_LARGE, negotiate(over).error);
    }

    @Test
    public void theCapTravelsWithTheWireSizeNotWithTheTrimmedText() {
        // The dispatcher sees the trimmed text; the negotiation must judge
        // by the untrimmed wire size it was told about. A trimmed 45-byte
        // JSON with a 256 KiB + 1 wire size is a protocol 2 refusal.
        String trimmed = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}";
        assertEquals(AiV2Error.REQUEST_TOO_LARGE, AiV2Protocol.negotiate(
            trimmed, AiV2Limits.MAX_REQUEST_BYTES_V2 + 1, PING_ONLY).error);
        assertNull(AiV2Protocol.negotiate(
            trimmed, rawBytes(trimmed), PING_ONLY).error);
    }

    // ---- validation order and the error frame echo ----

    @Test
    public void theSizeCheckComesBeforeTheFieldChecks() {
        String over = "{\"protocol\":2,\"cmd\":\"ping\",\"pad\":\"" + repeat('a', 256 * 1024) + "\"}";
        // requestId is missing too: the size failure wins.
        assertEquals(AiV2Error.REQUEST_TOO_LARGE, negotiate(over).error);
    }

    @Test
    public void requestIdIsCheckedBeforeCmd() {
        assertEquals(AiV2Error.BAD_REQUEST_ID,
            negotiate("{\"protocol\":2,\"cmd\":9}").error);
    }

    @Test
    public void cmdShapeIsCheckedBeforeMembership() {
        assertEquals(AiV2Error.BAD_CMD,
            negotiate("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"\"}").error);
    }

    @Test
    public void failuresAfterTheIdEchoItAndEarlierOnesDoNot() {
        assertEquals("", negotiate("{\"protocol\":9}").requestIdEcho);
        assertEquals("", negotiate("{\"protocol\":2,\"requestId\":\"\",\"cmd\":\"ping\"}").requestIdEcho);
        assertEquals("r9", negotiate("{\"protocol\":2,\"requestId\":\"r9\",\"cmd\":\"nope\"}").requestIdEcho);
        assertEquals("r9", negotiate("{\"protocol\":2,\"requestId\":\"r9\",\"cmd\":\"ping\",\"args\":3}").requestIdEcho);
        assertEquals("r9", negotiate("{\"protocol\":2,\"requestId\":\"r9\",\"cmd\":\"ping\",\"input\":3}").requestIdEcho);
    }

    @Test
    public void negotiationNeverDependsOnTheCallerTrustingTheFrame() {
        // Unknown extra fields are ignored, like the legacy path ignores them.
        AiV2Protocol.Negotiated negotiated = negotiate(
            "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"future\":\"field\",\"deep\":{\"a\":1}}");
        assertNull(negotiated.error);
        assertEquals("ping", negotiated.request.cmd());
    }

    @Test
    public void theKnownSetIsNotAccidentallyMutableOrEmpty() {
        Set<String> copy = new HashSet<>(AiV2Commands.KNOWN);
        assertEquals("every legacy command is a protocol 2 command", 39, copy.size());
        try {
            AiV2Commands.KNOWN.add("something.new");
            throw new AssertionError("the known set must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // fail closed by construction
        }
    }
}
