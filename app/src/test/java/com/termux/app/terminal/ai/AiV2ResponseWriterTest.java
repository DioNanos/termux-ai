package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The bounded protocol 2 response writer: one JSON object per line, ordered
 * sequence numbers, chunks within the limit, no payload ever breaking the
 * line structure.
 */
public class AiV2ResponseWriterTest {

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    private static String repeat(String unit, int count) {
        StringBuilder sb = new StringBuilder(unit.length() * count);
        for (int i = 0; i < count; i++) sb.append(unit);
        return sb.toString();
    }

    // ---- frame shape ----

    @Test
    public void aSuccessfulExchangeIsBeginChunkEndOnThreeLines() throws JSONException {
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, "{\"ok\":true,\"data\":{}}");
        writer.endSuccess(0);

        String out = writer.toString();
        assertFalse("no trailing newline: the framing handler adds it",
            out.endsWith("\n"));
        assertFalse("no blank line between frames", out.contains("\n\n"));

        String[] lines = out.split("\n", -1);
        assertEquals(3, lines.length);

        JSONObject begin = new JSONObject(lines[0]);
        assertEquals(2, begin.getInt("v"));
        assertEquals("begin", begin.getString("type"));
        assertEquals("r1", begin.getString("requestId"));
        assertEquals(0, begin.getInt("seq"));

        JSONObject chunk = new JSONObject(lines[1]);
        assertEquals("chunk", chunk.getString("type"));
        assertEquals("r1", chunk.getString("requestId"));
        assertEquals(1, chunk.getInt("seq"));
        assertEquals("out", chunk.getString("stream"));
        assertEquals("{\"ok\":true,\"data\":{}}", chunk.getString("data"));

        JSONObject end = new JSONObject(lines[2]);
        assertEquals("end", end.getString("type"));
        assertEquals(2, end.getInt("seq"));
        assertTrue(end.getBoolean("ok"));
        assertEquals(0, end.getInt("exit"));
    }

    @Test
    public void anEmptyStreamIsBeginFollowedByEnd() throws JSONException {
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, "");
        writer.chunk(AiV2ResponseWriter.STREAM_ERR, null);
        writer.endSuccess(0);
        String[] lines = writer.toString().split("\n", -1);
        assertEquals("an empty payload writes no chunk", 2, lines.length);
        assertEquals("begin", new JSONObject(lines[0]).getString("type"));
        assertEquals("end", new JSONObject(lines[1]).getString("type"));
        assertEquals("seq still grows per frame", 1, new JSONObject(lines[1]).getInt("seq"));
    }

    @Test
    public void aFailedCommandClosesWithAnEnumeratedEndFrame() throws JSONException {
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.endFailure(AiV2Error.COMMAND_FAILED, 1);

        String[] lines = writer.toString().split("\n", -1);
        assertEquals(2, lines.length);
        JSONObject end = new JSONObject(lines[1]);
        assertEquals("end", end.getString("type"));
        assertFalse(end.getBoolean("ok"));
        assertEquals(1, end.getInt("exit"));
        assertEquals("COMMAND_FAILED", end.getString("code"));
        assertEquals(AiV2Error.COMMAND_FAILED.diagnostic(), end.getString("diagnostic"));
    }

    @Test
    public void anUndispatchedExchangeIsASingleErrorFrame() throws JSONException {
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r9");
        writer.error(AiV2Error.BAD_CMD);
        String out = writer.toString();
        assertFalse(out.contains("\n"));
        JSONObject error = new JSONObject(out);
        assertEquals("error", error.getString("type"));
        assertEquals("r9", error.getString("requestId"));
        assertEquals(0, error.getInt("seq"));
        assertEquals("BAD_CMD", error.getString("code"));
        assertEquals(AiV2Error.BAD_CMD.diagnostic(), error.getString("diagnostic"));
    }

    @Test
    public void theErrorStreamIsLabelledAndKeepsItsOwnOrder() throws JSONException {
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, "hello");
        writer.chunk(AiV2ResponseWriter.STREAM_ERR, "warning");
        writer.endSuccess(0);
        String[] lines = writer.toString().split("\n", -1);
        assertEquals("out", new JSONObject(lines[1]).getString("stream"));
        assertEquals("hello", new JSONObject(lines[1]).getString("data"));
        assertEquals("err", new JSONObject(lines[2]).getString("stream"));
        assertEquals("warning", new JSONObject(lines[2]).getString("data"));
        assertEquals(3, new JSONObject(lines[3]).getInt("seq"));
    }

    // ---- the chunk bound ----

    @Test
    public void aPayloadWithinTheLimitIsOneChunk() {
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, repeat('a', AiV2Limits.MAX_CHUNK_CHARS));
        writer.endSuccess(0);
        assertEquals(3, writer.toString().split("\n", -1).length);
    }

    @Test
    public void aPayloadOverTheLimitIsSplitIntoOrderedChunksThatRebuildIt() throws JSONException {
        String payload = repeat('a', 2 * AiV2Limits.MAX_CHUNK_CHARS + 5);
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, payload);
        writer.endSuccess(0);

        String[] lines = writer.toString().split("\n", -1);
        assertEquals(5, lines.length); // begin + 3 chunks + end

        StringBuilder rebuilt = new StringBuilder();
        int expectedSeq = 1;
        for (int i = 1; i <= 3; i++) {
            JSONObject chunk = new JSONObject(lines[i]);
            assertEquals(expectedSeq, chunk.getInt("seq"));
            assertTrue("each chunk is within the limit",
                chunk.getString("data").length() <= AiV2Limits.MAX_CHUNK_CHARS);
            rebuilt.append(chunk.getString("data"));
            expectedSeq++;
        }
        assertEquals("the ordered chunks rebuild the payload", payload, rebuilt.toString());
        assertEquals("the end frame follows the last chunk", 4,
            new JSONObject(lines[4]).getInt("seq"));
    }

    @Test
    public void aSplitNeverCutsASurrogatePairInHalf() throws JSONException {
        // 32767 'a' then an emoji (a surrogate pair astride the 32768 limit).
        String payload = repeat('a', AiV2Limits.MAX_CHUNK_CHARS - 1) + "😀" + "b";
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, payload);
        writer.endSuccess(0);

        String[] lines = writer.toString().split("\n", -1);
        String first = new JSONObject(lines[1]).getString("data");
        String second = new JSONObject(lines[2]).getString("data");
        assertEquals(AiV2Limits.MAX_CHUNK_CHARS - 1, first.length());
        assertEquals("😀b", second);
        assertEquals(payload, first + second);
    }

    @Test
    public void everySplitPointOfAnEmojiPayloadKeepsPairsWhole() throws JSONException {
        String payload = repeat("😀", 16 * 1024 + 3); // 32774 chars, all pairs
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, payload);
        String[] lines = writer.toString().split("\n", -1);
        StringBuilder rebuilt = new StringBuilder();
        for (String line : lines) {
            JSONObject chunk = new JSONObject(line);
            String data = chunk.getString("data");
            char head = data.charAt(0);
            char tail = data.charAt(data.length() - 1);
            assertFalse("no chunk starts with a lone low surrogate",
                Character.isLowSurrogate(head));
            assertFalse("no chunk ends with a lone high surrogate",
                Character.isHighSurrogate(tail));
            rebuilt.append(data);
        }
        assertEquals(payload, rebuilt.toString());
    }

    // ---- payload encoding: one line per frame, whatever the bytes ----

    @Test
    public void controlCharactersQuotesAndNewlinesSurviveTheRoundTripOnOneLine() throws JSONException {
        String payload = "line1\nline2\ttabbed\r\n\"quoted\" back\\slash \u0000 è 😀 日本語";
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, payload);
        writer.endSuccess(0);

        String out = writer.toString();
        String[] lines = out.split("\n", -1);
        assertEquals("the payload newline is escaped, not a frame separator", 3, lines.length);
        assertEquals(payload, new JSONObject(lines[1]).getString("data"));
    }

    @Test
    public void theWorstEscapedChunkStaysUnderTheClientFrameBound() {
        // Every character escapes to six characters (NUL): this is the
        // largest frame a bounded chunk can produce.
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, repeat('\u0000', AiV2Limits.MAX_CHUNK_CHARS));
        String frame = writer.toString();
        assertTrue("frame escapes below the client bound",
            frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= AiV2Limits.MAX_CLIENT_FRAME_BYTES);
    }

    // ---- the aggregate server bounds: payload and encoded buffer ----

    private static void assertSingleTooLargeError(String out) throws JSONException {
        assertEquals("the overflow replaces the whole answer with one frame",
            1, out.split("\n", -1).length);
        JSONObject error = new JSONObject(out);
        assertEquals("error", error.getString("type"));
        assertEquals("RESPONSE_TOO_LARGE", error.getString("code"));
        assertEquals("r1", error.getString("requestId"));
        assertEquals(AiV2Error.RESPONSE_TOO_LARGE.diagnostic(), error.getString("diagnostic"));
        assertFalse("never ok:true, not even in an end frame", out.contains("\"ok\":true"));
    }

    @Test
    public void anAsciiPayloadAtTheServerBoundSucceedsWithABoundedFrameCount() throws JSONException {
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, repeat('a', AiV2Limits.MAX_SERVER_PAYLOAD_CHARS));
        writer.endSuccess(0);
        String out = writer.toString();
        int expectedChunks = AiV2Limits.MAX_SERVER_PAYLOAD_CHARS / AiV2Limits.MAX_CHUNK_CHARS;
        assertEquals("begin + every full chunk + end", 2 + expectedChunks,
            out.split("\n", -1).length);
        JSONObject end = new JSONObject(out.substring(out.lastIndexOf('\n') + 1));
        assertEquals("end", end.getString("type"));
        assertTrue(end.getBoolean("ok"));
    }

    @Test
    public void oneAsciiCharacterOverTheServerBoundOverflows() throws JSONException {
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, repeat('a', AiV2Limits.MAX_SERVER_PAYLOAD_CHARS + 1));
        writer.endSuccess(0);
        assertSingleTooLargeError(writer.toString());
    }

    @Test
    public void oneMultibyteCharacterOverTheServerBoundOverflows() throws JSONException {
        // 'è' is one character per unit: the bound is in characters.
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, repeat('è', AiV2Limits.MAX_SERVER_PAYLOAD_CHARS + 1));
        writer.endSuccess(0);
        assertSingleTooLargeError(writer.toString());
    }

    @Test
    public void aControlPayloadBelowTheCharBoundOverflowsThroughTheEncodedBound() throws JSONException {
        // Every NUL escapes to six characters: 768 KiB of payload is under
        // the 2 MiB payload bound, but its encoding alone is over the 4 MiB
        // buffer bound — the second guard refuses what the first would let
        // through.
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, repeat((char) 0, 768 * 1024));
        writer.endSuccess(0);
        assertSingleTooLargeError(writer.toString());
    }

    @Test
    public void aControlPayloadUnderBothBoundsSucceeds() throws JSONException {
        // 512 KiB of NUL encodes to about 3 MiB: under the buffer bound.
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, repeat((char) 0, 512 * 1024));
        writer.endSuccess(0);
        String out = writer.toString();
        JSONObject end = new JSONObject(out.substring(out.lastIndexOf('\n') + 1));
        assertEquals("end", end.getString("type"));
        assertTrue(end.getBoolean("ok"));
    }

    @Test
    public void repeatedChunkCallsAreCountedTogetherAgainstTheBound() throws JSONException {
        AiV2ResponseWriter atLimit = new AiV2ResponseWriter("r1");
        atLimit.begin();
        for (int i = 0; i < 4096; i++) {
            atLimit.chunk(AiV2ResponseWriter.STREAM_OUT, repeat('a', 512));
        }
        atLimit.endSuccess(0);
        String[] lines = atLimit.toString().split("\n", -1);
        assertEquals("4096 x 512 = exactly the payload bound: every chunk fits",
            2 + 4096, lines.length);
        JSONObject end = new JSONObject(lines[lines.length - 1]);
        assertEquals("end", end.getString("type"));
        assertTrue(end.getBoolean("ok"));

        AiV2ResponseWriter over = new AiV2ResponseWriter("r1");
        over.begin();
        for (int i = 0; i < 4097; i++) {
            over.chunk(AiV2ResponseWriter.STREAM_OUT, repeat('a', 512));
        }
        over.endSuccess(0);
        assertSingleTooLargeError(over.toString());
    }

    @Test
    public void anOverflowAfterPartOfTheAnswerReplacesItAndSwallowsTheRest() throws JSONException {
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        for (int i = 0; i < 10; i++) {
            writer.chunk(AiV2ResponseWriter.STREAM_OUT, repeat('a', 1024));
        }
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, repeat('a', AiV2Limits.MAX_SERVER_PAYLOAD_CHARS));
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, "after the overflow");
        writer.endSuccess(0);
        writer.endFailure(AiV2Error.COMMAND_FAILED, 1);
        String out = writer.toString();
        assertSingleTooLargeError(out);
        assertFalse("nothing streamed after the overflow survives", out.contains("after the overflow"));
    }

    @Test
    public void aVeryLargeAsciiPayloadProducesOneShortFrameNotMillions() throws JSONException {
        // 9 MiB of ASCII: the counter-example measured 9,458,500
        // characters over 290 frames ending ok:true on this input. The
        // bounded writer answers with one short enumerated error frame
        // instead: the answer size no longer follows the payload size.
        AiV2ResponseWriter writer = new AiV2ResponseWriter("r1");
        writer.begin();
        writer.chunk(AiV2ResponseWriter.STREAM_OUT, repeat('a', 9 * 1024 * 1024));
        writer.endSuccess(0);
        String out = writer.toString();
        assertTrue("the answer is bounded regardless of the payload", out.length() < 300);
        assertEquals("RESPONSE_TOO_LARGE", new JSONObject(out).getString("code"));
    }

    // ---- the client contract as constants (the real client is the compat client) ----

    @Test
    public void theClientContractConstantsAreTheSpecifiedOnes() {
        assertEquals("the client accumulates at most 8 MiB",
            8 * 1024 * 1024, AiV2Limits.MAX_CLIENT_TOTAL_BYTES);
        assertEquals("a client frame is at most 256 KiB",
            256 * 1024, AiV2Limits.MAX_CLIENT_FRAME_BYTES);
        assertTrue("the client bound is above the worst server frame",
            AiV2Limits.MAX_CLIENT_FRAME_BYTES > AiV2Limits.MAX_CHUNK_CHARS * 6);
        assertTrue("the total bound is above the frame bound",
            AiV2Limits.MAX_CLIENT_TOTAL_BYTES > AiV2Limits.MAX_CLIENT_FRAME_BYTES);
        assertEquals("the server payload bound is 2 MiB characters",
            2 * 1024 * 1024, AiV2Limits.MAX_SERVER_PAYLOAD_CHARS);
        assertEquals("the server buffer bound is 4 MiB characters",
            4 * 1024 * 1024, AiV2Limits.MAX_SERVER_BUFFER_CHARS);
        assertTrue("a server-legal answer (2 MiB chars, at most 6 MiB of UTF-8)"
            + " never trips the client bound",
            AiV2Limits.MAX_SERVER_PAYLOAD_CHARS * 3 < AiV2Limits.MAX_CLIENT_TOTAL_BYTES);
    }
}
