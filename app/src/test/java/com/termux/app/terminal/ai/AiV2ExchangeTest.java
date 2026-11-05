package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The protocol 2 exchange between the framing and the shared legacy
 * dispatcher: legacy frames pass through untouched, valid requests run
 * exactly once, invalid ones never run, and no raw error string or payload
 * leaks into a protocol 2 answer.
 */
public class AiV2ExchangeTest {

    private static final Set<String> PING_ONLY = Collections.singleton("ping");

    /** Records what the legacy dispatcher saw and what it must answer. */
    private static class RecordingDispatcher implements Function<String, String> {
        final AtomicInteger calls = new AtomicInteger();
        String lastRequest;
        String answer = "{\"ok\":true,\"data\":{\"message\":\"pong\"}}";

        @Override public String apply(String request) {
            calls.incrementAndGet();
            lastRequest = request;
            return answer;
        }
    }

    /** Every grammar test runs with the gate explicitly open. */
    private static String exchange(String frame, RecordingDispatcher dispatcher) {
        return AiV2Exchange.exchange(frame, rawBytes(frame), dispatcher, PING_ONLY, true);
    }

    private static int rawBytes(String frame) {
        return frame.getBytes(StandardCharsets.UTF_8).length;
    }

    // ---- the production gate: the v2 wire is not offered ----

    @Test
    public void theProductionGateIsClosedSoAProtocol2FrameIsServedAsLegacy() {
        // V2_ENABLED is false until the remaining protocol stages close: a client
        // sending protocol 2 today gets the legacy answer for its frame,
        // with input and protocol as ignored extra fields, byte for byte
        // like before the class existed.
        assertFalse("the gate is closed by default", AiV2Exchange.V2_ENABLED);
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        String frame = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\","
            + "\"args\":{\"x\":1},\"input\":\"stdin is just an ignored field here\"}";
        String answer = AiV2Exchange.exchange(frame, rawBytes(frame), dispatcher, PING_ONLY);
        assertEquals(dispatcher.answer, answer);
        assertEquals(1, dispatcher.calls.get());
        assertEquals("the whole frame reaches the legacy dispatcher unchanged", frame,
            dispatcher.lastRequest);
    }

    @Test
    public void anExplicitProtocolFieldIsABoundaryOnlyWhileTheGateIsOpen() throws JSONException {
        // Before this class, {"cmd":"ping","protocol":null} was a normal
        // legacy frame (the extra field ignored). The comparative fixture:
        // gate closed it still is; gate open it is an unknown protocol
        // version and never reaches the dispatcher.
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        String frame = "{\"cmd\":\"ping\",\"protocol\":null}";

        String closed = AiV2Exchange.exchange(frame, rawBytes(frame), dispatcher, PING_ONLY, false);
        assertEquals("gate closed: the legacy answer, byte for byte", dispatcher.answer, closed);
        assertEquals(1, dispatcher.calls.get());

        String open = AiV2Exchange.exchange(frame, rawBytes(frame), dispatcher, PING_ONLY, true);
        assertEquals("gate open: an explicit null protocol is an unknown version",
            "UNKNOWN_PROTOCOL", new JSONObject(open).getString("code"));
        assertEquals("and it never dispatches", 1, dispatcher.calls.get());
        assertEquals("the refused frame does not echo an unvalidated id", "",
            new JSONObject(open).getString("requestId"));
    }

    // ---- the legacy path is untouched ----

    @Test
    public void aLegacyFrameReachesTheDispatcherByteForByteAndItsAnswerComesBack() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.answer = "{\"ok\":true,\"data\":{\"n\":1}}";
        String frame = "{\"cmd\":\"ping\",\"args\":{\"x\":1}}";
        assertEquals("{\"ok\":true,\"data\":{\"n\":1}}", exchange(frame, dispatcher));
        assertEquals(1, dispatcher.calls.get());
        assertEquals("the legacy dispatcher sees the exact frame", frame, dispatcher.lastRequest);
    }

    @Test
    public void aProtocolOneFrameIsLegacyToo() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        exchange("{\"protocol\":1,\"cmd\":\"ping\"}", dispatcher);
        assertEquals("{\"protocol\":1,\"cmd\":\"ping\"}", dispatcher.lastRequest);
        assertEquals("the protocol 1 answer is not re-framed",
            dispatcher.answer, exchange("{\"protocol\":1,\"cmd\":\"ping\"}", dispatcher));
    }

    @Test
    public void aFrameThatIsNotJsonIsStillAnsweredByTheLegacyDispatcher() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.answer = "{\"ok\":false,\"error\":\"Invalid request: whatever\"}";
        String answer = exchange("not json", dispatcher);
        assertEquals(dispatcher.answer, answer);
        assertEquals("not json", dispatcher.lastRequest);
    }

    @Test
    public void anOversizedLegacyRequestStillRunsAtTheOuterOneMebibyteBound() {
        // 300 KiB of payload: refused for protocol 2, normal for protocol 1,
        // and the outer bound of the read stays 1 MiB for both.
        StringBuilder frame = new StringBuilder("{\"cmd\":\"ping\",\"pad\":\"");
        for (int i = 0; i < 300 * 1024; i++) frame.append('a');
        frame.append("\"}");
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        exchange(frame.toString(), dispatcher);
        assertEquals(1, dispatcher.calls.get());
    }

    // ---- a valid protocol 2 request ----

    @Test
    public void aValidRequestRunsOnceAndIsAnsweredWithBeginChunkEnd() throws JSONException {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        String answer = exchange(
            "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"args\":{\"x\":1}}", dispatcher);

        assertEquals("the command runs exactly once", 1, dispatcher.calls.get());
        JSONObject dispatched = new JSONObject(dispatcher.lastRequest);
        assertEquals("ping", dispatched.getString("cmd"));
        assertEquals(1, dispatched.getJSONObject("args").getInt("x"));
        assertFalse("the stdin payload never reaches the dispatch",
            dispatched.has("input"));

        String[] lines = answer.split("\n", -1);
        assertEquals(3, lines.length);
        JSONObject begin = new JSONObject(lines[0]);
        assertEquals("begin", begin.getString("type"));
        assertEquals("r1", begin.getString("requestId"));
        JSONObject chunk = new JSONObject(lines[1]);
        assertEquals("chunk", chunk.getString("type"));
        assertEquals("out", chunk.getString("stream"));
        assertEquals("the streamed stdout is the whole legacy answer",
            dispatcher.answer, chunk.getString("data"));
        JSONObject end = new JSONObject(lines[2]);
        assertEquals("end", end.getString("type"));
        assertTrue(end.getBoolean("ok"));
        assertEquals(0, end.getInt("exit"));
        assertEquals(2, end.getInt("seq"));
    }

    @Test
    public void aRequestWithoutArgsDispatchesWithAnEmptyArgsObject() throws JSONException {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        exchange("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}", dispatcher);
        JSONObject dispatched = new JSONObject(dispatcher.lastRequest);
        assertEquals("ping", dispatched.getString("cmd"));
        assertEquals(0, dispatched.getJSONObject("args").length());
    }

    @Test
    public void anInputPresentIsRefusedBeforeAnyDispatchOrEffect() throws JSONException {
        // stdin belongs to the compat adapter. Until then a request that carries
        // input is refused before the dispatch: the args are NOT a fallback
        // stdin, so a sys.clipboard.set style request cannot wipe anything.
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        String answer = exchange(
            "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\","
                + "\"args\":{\"text\":\"args text is not the input\"},\"input\":\"keep this text\"}",
            dispatcher);

        assertEquals("zero dispatch, zero effects", 0, dispatcher.calls.get());
        assertNull(dispatcher.lastRequest);
        JSONObject error = new JSONObject(answer);
        assertEquals("error", error.getString("type"));
        assertEquals("r1", error.getString("requestId"));
        assertEquals("INPUT_UNSUPPORTED", error.getString("code"));
        assertEquals(AiV2Error.INPUT_UNSUPPORTED.diagnostic(), error.getString("diagnostic"));
        assertFalse("the refused payload is not echoed", answer.contains("keep this text"));
    }

    @Test
    public void anEmptyInputIsStillPresentAndIsRefused() throws JSONException {
        // Presence is not usefulness: an empty stdin is a present input.
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        String answer = exchange(
            "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"input\":\"\"}", dispatcher);
        assertEquals(0, dispatcher.calls.get());
        assertEquals("INPUT_UNSUPPORTED", new JSONObject(answer).getString("code"));
    }

    @Test
    public void anExplicitNullInputCountsAsAbsentAndDispatches() throws JSONException {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        String answer = exchange(
            "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"input\":null}", dispatcher);
        assertEquals(1, dispatcher.calls.get());
        assertEquals("the exchange reaches begin/chunk/end",
            "end", new JSONObject(answer.split("\n", -1)[2]).getString("type"));
    }

    // ---- a dispatched command that fails ----

    @Test
    public void aFailedCommandEndsWithTheEnumeratedCodeAndNoLegacyErrorString() throws JSONException {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.answer = "{\"ok\":false,\"error\":\"Invalid request: secret command details\"}";
        String answer = exchange("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}", dispatcher);

        assertEquals(1, dispatcher.calls.get());
        String[] lines = answer.split("\n", -1);
        assertEquals(2, lines.length);
        JSONObject end = new JSONObject(lines[1]);
        assertEquals("end", end.getString("type"));
        assertFalse(end.getBoolean("ok"));
        assertEquals(1, end.getInt("exit"));
        assertEquals("COMMAND_FAILED", end.getString("code"));
        assertFalse("the raw legacy error never reaches protocol 2",
            answer.contains("secret command details"));
        assertFalse(answer.contains("Invalid request"));
    }

    // ---- validation failures never dispatch ----

    @Test
    public void anInvalidRequestIsOneErrorFrameAndNeverDispatched() throws JSONException {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        String answer = exchange("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"nope\"}", dispatcher);
        assertEquals(0, dispatcher.calls.get());
        JSONObject error = new JSONObject(answer);
        assertEquals("error", error.getString("type"));
        assertEquals("r1", error.getString("requestId"));
        assertEquals("UNKNOWN_COMMAND", error.getString("code"));
        assertEquals(AiV2Error.UNKNOWN_COMMAND.diagnostic(), error.getString("diagnostic"));
    }

    @Test
    public void anUnknownProtocolVersionIsRefusedBeforeAnythingRuns() throws JSONException {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        String answer = exchange("{\"protocol\":3,\"requestId\":\"r1\",\"cmd\":\"ping\"}", dispatcher);
        assertEquals(0, dispatcher.calls.get());
        assertEquals("UNKNOWN_PROTOCOL", new JSONObject(answer).getString("code"));
        assertEquals("no id is trusted before its validation", "",
            new JSONObject(answer).getString("requestId"));
    }

    @Test
    public void anOverLimitProtocol2RequestIsRefusedBeforeAnythingRuns() throws JSONException {
        StringBuilder frame = new StringBuilder("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\",\"pad\":\"");
        for (int i = 0; i < 256 * 1024; i++) frame.append('a');
        frame.append("\"}");
        assertTrue(frame.toString().getBytes(StandardCharsets.UTF_8).length
            > AiV2Limits.MAX_REQUEST_BYTES_V2);
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        String answer = exchange(frame.toString(), dispatcher);
        assertEquals(0, dispatcher.calls.get());
        assertEquals("REQUEST_TOO_LARGE", new JSONObject(answer).getString("code"));
    }

    @Test
    public void aPaddedProtocol2RequestIsRefusedByItsUntrimmedWireSize() throws JSONException {
        // The framing handler hands over the trimmed JSON with the wire size
        // it measured before the trim: whitespace padding past the protocol 2
        // cap is refused although the trimmed text is tiny.
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        String trimmed = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}";
        // The explicit gate: the production 4-argument overload stays closed
        // and is covered by theProductionGateIsClosedSoAProtocol2FrameIsServedAsLegacy.
        String answer = AiV2Exchange.exchange(trimmed,
            AiV2Limits.MAX_REQUEST_BYTES_V2 + 1, dispatcher, PING_ONLY, true);
        assertEquals("zero dispatch beyond the cap", 0, dispatcher.calls.get());
        assertEquals("REQUEST_TOO_LARGE", new JSONObject(answer).getString("code"));
    }

    // ---- the exchange itself stays bounded and enumerated ----

    @Test
    public void aThrowingDispatcherGetsThePostEffectInternalError() throws JSONException {
        // The command was already handed over: INTERNAL_AFTER_DISPATCH never
        // claims the command did not run and licenses no retry.
        RecordingDispatcher dispatcher = new RecordingDispatcher() {
            @Override public String apply(String request) {
                super.apply(request);
                throw new RuntimeException("boom: /data/user/0/secret");
            }
        };
        String answer = exchange("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}", dispatcher);
        assertEquals("the dispatcher really ran first", 1, dispatcher.calls.get());
        JSONObject error = new JSONObject(answer);
        assertEquals("error", error.getString("type"));
        assertEquals("INTERNAL_AFTER_DISPATCH", error.getString("code"));
        assertTrue("the diagnostic says the effect is unknown and unsafe to retry",
            error.getString("diagnostic").contains("may have had effects"));
        assertFalse("no cause or message leaks", answer.contains("boom"));
        assertFalse(answer.contains("secret"));
    }

    @Test
    public void aNonJsonLegacyAnswerIsThePostEffectInternalError() throws JSONException {
        // Impossible by construction (the dispatcher always returns JSON),
        // but the exchange does not trust it: fail closed, stay enumerated,
        // and still never claim the command did not run.
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.answer = "not json at all";
        String answer = exchange("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}", dispatcher);
        assertEquals("INTERNAL_AFTER_DISPATCH", new JSONObject(answer).getString("code"));
    }

    @Test
    public void everyProtocol2AnswerLineIsAFrameWithTheSameRequestId() throws JSONException {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        String answer = exchange("{\"protocol\":2,\"requestId\":\"id-😀-42\",\"cmd\":\"ping\"}", dispatcher);
        for (String line : answer.split("\n", -1)) {
            JSONObject frame = new JSONObject(line);
            assertEquals(2, frame.getInt("v"));
            assertEquals("id-😀-42", frame.getString("requestId"));
        }
    }

    @Test
    public void theSequenceOfAnAnswerIsStrictlyOrdered() throws JSONException {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        StringBuilder payload = new StringBuilder("{\"ok\":true,\"data\":{\"pad\":\"");
        for (int i = 0; i < 2 * AiV2Limits.MAX_CHUNK_CHARS + 10; i++) payload.append('a');
        payload.append("\"}}");
        dispatcher.answer = payload.toString();
        String answer = exchange("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}", dispatcher);
        int expected = 0;
        for (String line : answer.split("\n", -1)) {
            JSONObject frame = new JSONObject(line);
            assertEquals(expected, frame.getInt("seq"));
            expected++;
        }
        assertTrue("a payload over the chunk bound really splits",
            answer.split("\n", -1).length > 3);
    }

    @Test
    public void aCommandAnswerOverTheServerBoundIsRefusedAsAWhole() throws JSONException {
        // The command ran, but its answer cannot be served inside the server
        // response bound: the exchange refuses it as a whole instead of
        // streaming a partial stdout followed by success.
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        StringBuilder payload = new StringBuilder("{\"ok\":true,\"data\":{\"pad\":\"");
        for (int i = 0; i < AiV2Limits.MAX_SERVER_PAYLOAD_CHARS + 1; i++) payload.append('a');
        payload.append("\"}}");
        dispatcher.answer = payload.toString();
        String answer = exchange("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}", dispatcher);

        assertEquals("the command ran exactly once", 1, dispatcher.calls.get());
        String[] lines = answer.split("\n", -1);
        assertEquals("the whole answer is one error frame", 1, lines.length);
        JSONObject error = new JSONObject(lines[0]);
        assertEquals("error", error.getString("type"));
        assertEquals("RESPONSE_TOO_LARGE", error.getString("code"));
        assertEquals("r1", error.getString("requestId"));
        assertFalse("no ok:true anywhere", answer.contains("\"ok\":true"));
    }
}
