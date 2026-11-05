package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The protocol 2 exchange as seen from the socket path: the framing handler
 * stays the single reader and writer of the connection, a protocol 2 answer
 * is several frames and exactly one final newline, and nothing after the
 * request frame is ever read.
 */
public class AiV2SocketPathTest {

    private static final Set<String> PING_ONLY = Collections.singleton("ping");

    /** A peer that has {@code data} ready and would answer more reads with a timeout. */
    private static final class Peer implements AiRequestFraming.ByteSource {
        final byte[] data;
        int position;

        Peer(String text) {
            this.data = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override public int read(byte[] buffer) throws IOException {
            if (position < data.length) {
                buffer[0] = data[position++];
                return 1;
            }
            throw new IOException("Read failed: EAGAIN (receive timeout)");
        }
    }

    /** A peer that closes after its bytes, like the oldest clients do. */
    private static final class ClosingPeer implements AiRequestFraming.ByteSource {
        final Peer peer;

        ClosingPeer(String text) {
            this.peer = new Peer(text);
        }

        @Override public int read(byte[] buffer) throws IOException {
            return peer.position < peer.data.length ? peer.read(buffer) : -1;
        }
    }

    private static final class Sink implements AiSocketHandler.ByteSink {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Override public void write(byte[] data) throws IOException {
            out.write(data, 0, data.length);
        }

        String text() {
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** The dispatcher used by the framing handler: negotiate, then dispatch. */
    private static AiSocketHandler.FrameDispatcher exchange(Function<String, String> legacy) {
        return (frame, frameBytes) -> AiV2Exchange.exchange(frame, frameBytes, legacy, PING_ONLY, true);
    }

    private static int rawBytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String spaces(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(' ');
        return sb.toString();
    }

    @Test
    public void aProtocol2AnswerIsThreeFramesAndExactlyOneFinalNewline() throws JSONException {
        Sink sink = new Sink();
        String wire = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}\n";
        AiSocketHandler.handle(new Peer(wire), sink,
            exchange(request -> "{\"ok\":true,\"data\":{\"message\":\"pong\"}}"),
            AiRequestFraming.MAX_REQUEST_BYTES);
        String answer = sink.text();

        assertTrue(answer.endsWith("\n"));
        assertFalse("the handler newline is not doubled", answer.endsWith("\n\n"));
        assertFalse("no blank line between frames", answer.contains("\n\n"));

        String[] lines = answer.split("\n", -1);
        assertEquals("three frames and the final newline split into four elements", 4, lines.length);
        assertEquals("the final newline leaves exactly one empty tail element", "", lines[3]);
        assertEquals("begin", new JSONObject(lines[0]).getString("type"));
        assertEquals("chunk", new JSONObject(lines[1]).getString("type"));
        assertEquals("{\"ok\":true,\"data\":{\"message\":\"pong\"}}",
            new JSONObject(lines[1]).getString("data"));
        JSONObject end = new JSONObject(lines[2]);
        assertTrue(end.getBoolean("ok"));
        assertEquals(0, end.getInt("exit"));
    }

    @Test
    public void aProtocol2FailureAnswerIsTwoFramesAndOneFinalNewline() throws JSONException {
        Sink sink = new Sink();
        String wire = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}\n";
        AiSocketHandler.handle(new Peer(wire), sink,
            exchange(request -> "{\"ok\":false,\"error\":\"Invalid request: details\"}"),
            AiRequestFraming.MAX_REQUEST_BYTES);
        String answer = sink.text();
        String[] lines = answer.split("\n", -1);
        assertEquals("two frames and the final newline split into three elements", 3, lines.length);
        assertEquals("exactly one empty tail element after the final newline", "", lines[2]);
        JSONObject end = new JSONObject(lines[1]);
        assertFalse(end.getBoolean("ok"));
        assertEquals("COMMAND_FAILED", end.getString("code"));
        assertFalse("the raw legacy error is dropped", answer.contains("details"));
        assertFalse(answer.endsWith("\n\n"));
    }

    @Test
    public void aLegacyAnswerIsStillOneLineAndTheNegotiationIsInvisible() {
        Sink sink = new Sink();
        AiSocketHandler.handle(new Peer("{\"cmd\":\"ping\"}\n"), sink,
            exchange(request -> "{\"ok\":true,\"data\":{\"message\":\"pong\"}}"),
            AiRequestFraming.MAX_REQUEST_BYTES);
        assertEquals("{\"ok\":true,\"data\":{\"message\":\"pong\"}}\n", sink.text());
    }

    @Test
    public void theProductionWiringServesAProtocol2FrameAsLegacy() {
        // The production dispatcher is the 4-argument exchange, whose gate
        // (AiV2Exchange.V2_ENABLED) is closed: a protocol 2 frame on today's
        // socket gets one legacy line, not protocol 2 frames.
        Sink sink = new Sink();
        AiSocketHandler.handle(new Peer("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}\n"),
            sink,
            (frame, frameBytes) -> AiV2Exchange.exchange(frame, frameBytes,
                request -> "{\"ok\":true,\"data\":{\"message\":\"pong\"}}", PING_ONLY),
            AiRequestFraming.MAX_REQUEST_BYTES);
        assertEquals("one legacy line: the v2 wire is not offered to consumers",
            "{\"ok\":true,\"data\":{\"message\":\"pong\"}}\n", sink.text());
    }

    @Test
    public void nothingAfterTheRequestFrameIsEverRead() {
        // A second frame is waiting on the wire: the connection answers and
        // closes without reading it, so a slow or hostile peer cannot delay
        // the exchange (no pipe deadlock, no second dispatch).
        Peer peer = new Peer("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}\n"
            + "{\"protocol\":2,\"requestId\":\"r2\",\"cmd\":\"ping\"}\n");
        AtomicInteger dispatches = new AtomicInteger();
        Sink sink = new Sink();
        AiSocketHandler.handle(peer, sink, (frame, frameBytes) -> {
            dispatches.incrementAndGet();
            return AiV2Exchange.exchange(frame, frameBytes,
                request -> "{\"ok\":true,\"data\":{\"n\":1}}", PING_ONLY);
        }, AiRequestFraming.MAX_REQUEST_BYTES);

        assertEquals(1, dispatches.get());
        assertEquals("only the first frame and its newline were consumed",
            "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}\n".length(), peer.position);
    }

    @Test
    public void aSlowCommandStillAnswersBecauseNothingIsReadAfterTheFrame() throws JSONException {
        Peer peer = new Peer("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}\n");
        Sink sink = new Sink();
        long started = System.nanoTime();
        AiSocketHandler.handle(peer, sink,
            exchange(request -> {
                try { Thread.sleep(400); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "{\"ok\":true,\"data\":{\"text\":\"slow\"}}";
            }),
            AiRequestFraming.MAX_REQUEST_BYTES);
        String[] lines = sink.text().split("\n", -1);
        JSONObject chunk = new JSONObject(lines[1]);
        assertEquals("chunk", chunk.getString("type"));
        // The legacy JSON is escaped inside the outer frame: verify the
        // decoded payload, not the raw wire text.
        assertTrue(chunk.getString("data").contains("slow"));
        assertTrue("answered without waiting for a receive timeout",
            (System.nanoTime() - started) / 1_000_000 < 2_000);
    }

    @Test
    public void aPeerGoneMidWriteChangesNothingAboutTheExchange() {
        // The write fails because the peer disconnected: the exchange was
        // already complete and the handler drops the IOException.
        AiSocketHandler.ByteSink broken = new AiSocketHandler.ByteSink() {
            @Override public void write(byte[] data) throws IOException {
                throw new IOException("write failed: broken pipe");
            }
        };
        Peer peer = new Peer("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}\n");
        boolean dispatched = AiSocketHandler.handle(peer, broken,
            exchange(request -> "{\"ok\":true,\"data\":{}}"),
            AiRequestFraming.MAX_REQUEST_BYTES);
        assertTrue(dispatched);
    }

    @Test
    public void aFramingFailureIsAnsweredByTheUnchangedLegacyError() throws JSONException {
        // The connection dies before any byte: no protocol is known yet, and
        // the protocol 1 answer with its raw framing message is the
        // documented legacy behaviour, unchanged by the transient v1 wire work.
        Sink sink = new Sink();
        AiSocketHandler.handle(buffer -> -1, sink,
            exchange(request -> "{}"),
            AiRequestFraming.MAX_REQUEST_BYTES);
        JSONObject error = new JSONObject(sink.text().trim());
        assertFalse(error.getBoolean("ok"));
        assertTrue(error.getString("error").startsWith("Read failed"));
    }

    @Test
    public void aPeerDisconnectedAfterSomeBytesHandsAPartialFrameToTheLegacyPath() {
        // Old clients end the request by closing: a partial protocol 2 text
        // is not JSON, negotiation leaves it to the legacy dispatcher, and
        // the dispatch happens exactly once (on the trimmed partial frame).
        AtomicInteger dispatches = new AtomicInteger();
        String[] seen = new String[1];
        Sink sink = new Sink();
        boolean dispatched = AiSocketHandler.handle(
            new ClosingPeer("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"pi"), sink,
            (frame, frameBytes) -> {
                dispatches.incrementAndGet();
                seen[0] = frame;
                return AiV2Exchange.exchange(frame, frameBytes, request -> "{\"ok\":true}", PING_ONLY);
            },
            AiRequestFraming.MAX_REQUEST_BYTES);
        assertTrue("the closed frame was framed and dispatched", dispatched);
        assertEquals(1, dispatches.get());
        assertEquals("{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"pi", seen[0]);
        assertEquals("{\"ok\":true}\n", sink.text());
    }

    // ---- the protocol 2 cap sees the untrimmed wire size of the frame ----

    @Test
    public void theFrameBytesHandedToTheDispatcherAreTheUntrimmedWireSize() {
        // Whitespace padding on both sides, exactly up to the protocol 2 cap:
        // the dispatcher sees the trimmed JSON, the byte count is the full
        // wire size, and the request is legal at the limit.
        String json = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}";
        int pad = AiV2Limits.MAX_REQUEST_BYTES_V2 - rawBytes(json);
        String padded = " " + json + spaces(pad - 1);
        assertEquals(AiV2Limits.MAX_REQUEST_BYTES_V2, rawBytes(padded));

        String[] seenFrame = new String[1];
        int[] seenBytes = new int[1];
        Sink sink = new Sink();
        AiSocketHandler.handle(new Peer(padded + "\n"), sink, (frame, frameBytes) -> {
            seenFrame[0] = frame;
            seenBytes[0] = frameBytes;
            return AiV2Exchange.exchange(frame, frameBytes,
                request -> "{\"ok\":true,\"data\":{\"message\":\"pong\"}}", PING_ONLY, true);
        }, AiRequestFraming.MAX_REQUEST_BYTES);

        assertEquals("the dispatcher still sees the trimmed text", json, seenFrame[0]);
        assertEquals("the byte count is the untrimmed wire size, head and tail included",
            AiV2Limits.MAX_REQUEST_BYTES_V2, seenBytes[0]);
        assertTrue("at the raw limit the request is served",
            sink.text().contains("\"type\":\"end\""));
    }

    @Test
    public void whitespacePaddingPastTheCapRefusesProtocol2WithZeroDispatch() throws JSONException {
        String json = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}";
        String padded = json + spaces(AiV2Limits.MAX_REQUEST_BYTES_V2 - rawBytes(json) + 1);
        AtomicInteger legacyDispatches = new AtomicInteger();
        Sink sink = new Sink();
        // The gate is explicitly open: with the 4-argument overload the frame
        // would go to the legacy dispatcher verbatim, which is the separate
        // case of theProductionWiringServesAProtocol2FrameAsLegacy.
        AiSocketHandler.handle(new Peer(padded + "\n"), sink, (frame, frameBytes) ->
            AiV2Exchange.exchange(frame, frameBytes, request -> {
                legacyDispatches.incrementAndGet();
                return "{\"ok\":true}";
            }, PING_ONLY, true), AiRequestFraming.MAX_REQUEST_BYTES);
        assertEquals("nothing runs beyond the cap", 0, legacyDispatches.get());
        assertEquals("REQUEST_TOO_LARGE", new JSONObject(sink.text().trim()).getString("code"));
    }

    @Test
    public void anEofTerminatedPaddedFrameCountsTheSameBytes() throws JSONException {
        // An old client that ends the request by closing instead of a
        // newline: the padding still counts toward the protocol 2 cap.
        String json = "{\"protocol\":2,\"requestId\":\"r1\",\"cmd\":\"ping\"}";
        String padded = json + spaces(AiV2Limits.MAX_REQUEST_BYTES_V2 - rawBytes(json) + 1);
        AtomicInteger legacyDispatches = new AtomicInteger();
        Sink sink = new Sink();
        AiSocketHandler.handle(new ClosingPeer(padded), sink,
            exchange(request -> {
                legacyDispatches.incrementAndGet();
                return "{\"ok\":true}";
            }), AiRequestFraming.MAX_REQUEST_BYTES);
        assertEquals("nothing runs beyond the cap on the EOF path either",
            0, legacyDispatches.get());
        assertEquals("REQUEST_TOO_LARGE", new JSONObject(sink.text().trim()).getString("code"));
    }

    @Test
    public void aPaddedLegacyRequestIsTrimmedAndDispatchedAsBefore() {
        // 700 KiB of padding around a legacy request: no protocol key, under
        // the 1 MiB outer bound — the legacy semantics are unchanged, the
        // padding is trimmed away and the dispatch sees the exact JSON.
        String json = "{\"cmd\":\"ping\"}";
        String padded = "   " + json + spaces(700 * 1024);
        String[] seen = new String[1];
        Sink sink = new Sink();
        AiSocketHandler.handle(new Peer(padded + "\n"), sink, (frame, frameBytes) -> {
            seen[0] = frame;
            return AiV2Exchange.exchange(frame, frameBytes,
                request -> "{\"ok\":true,\"data\":{\"message\":\"pong\"}}", PING_ONLY);
        }, AiRequestFraming.MAX_REQUEST_BYTES);
        assertEquals("the legacy dispatcher sees the trimmed JSON", json, seen[0]);
        assertEquals("the legacy answer comes back on one line",
            "{\"ok\":true,\"data\":{\"message\":\"pong\"}}\n", sink.text());
    }

    @Test
    public void aLegacyRequestAtTheFullOuterBoundStillDispatches() {
        // Exactly 1 MiB of legacy frame (no protocol key, no padding): the
        // outer bound of protocol 1 is untouched by the protocol 2 cap.
        String head = "{\"cmd\":\"ping\",\"pad\":\"";
        String tail = "\"}";
        int pad = AiRequestFraming.MAX_REQUEST_BYTES - rawBytes(head) - rawBytes(tail);
        StringBuilder frame = new StringBuilder(AiRequestFraming.MAX_REQUEST_BYTES);
        frame.append(head);
        for (int i = 0; i < pad; i++) frame.append('a');
        frame.append(tail);
        assertEquals(AiRequestFraming.MAX_REQUEST_BYTES, rawBytes(frame.toString()));

        AtomicInteger legacyDispatches = new AtomicInteger();
        Sink sink = new Sink();
        AiSocketHandler.handle(new Peer(frame + "\n"), sink, (frameText, frameBytes) ->
            AiV2Exchange.exchange(frameText, frameBytes, request -> {
                legacyDispatches.incrementAndGet();
                return "{\"ok\":true}";
            }, PING_ONLY), AiRequestFraming.MAX_REQUEST_BYTES);
        assertEquals(1, legacyDispatches.get());
        assertEquals("the full legacy answer, one line", "{\"ok\":true}\n", sink.text());
    }
}
