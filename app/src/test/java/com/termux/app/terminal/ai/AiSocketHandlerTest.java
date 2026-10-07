package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class AiSocketHandlerTest {

    private static AiRequestFraming.ByteSource open(String text, long readTimeoutMs, long[] lastReadAt) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int[] position = {0};
        return buffer -> {
            // A peer that stays connected: once its bytes are consumed, a further
            // read would only end in the receive timeout.
            if (position[0] < data.length) {
                buffer[0] = data[position[0]++];
                lastReadAt[0] = System.nanoTime();
                return 1;
            }
            throw new IOException("Read failed: EAGAIN after " + readTimeoutMs + " ms");
        };
    }

    private static final class Sink implements AiSocketHandler.ByteSink {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Override public void write(byte[] data) { out.write(data, 0, data.length); }

        String text() { return new String(out.toByteArray(), StandardCharsets.UTF_8); }
    }

    @Test
    public void aPeerThatDoesNotCloseGetsOneDispatchImmediately() {
        AtomicInteger dispatches = new AtomicInteger();
        Sink sink = new Sink();
        long started = System.nanoTime();
        boolean dispatched = AiSocketHandler.handle(
            open("{\"cmd\":\"ping\"}\n", 10_000, new long[1]), sink,
            request -> { dispatches.incrementAndGet(); return "{\"ok\":true}"; }, 1024);
        assertTrue(dispatched);
        assertEquals(1, dispatches.get());
        assertEquals("{\"ok\":true}\n", sink.text());
        assertTrue("answered at once, not after a receive timeout",
            (System.nanoTime() - started) / 1_000_000 < 2_000);
    }

    @Test
    public void aSlowBackendStillAnswersBecauseTheSocketIsNotReadAgain() {
        // The old reader waited for EOF: the receive timeout (10 s) ended the request
        // before the model was asked. Here the dispatch outlasts a short timeout and
        // the answer still arrives, because nothing is read after the frame.
        Sink sink = new Sink();
        AiSocketHandler.handle(open("{\"cmd\":\"aicore.generate\"}\n", 50, new long[1]), sink, request -> {
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "{\"ok\":true,\"data\":{\"text\":\"slow\"}}";
        }, 1024);
        assertTrue(sink.text().contains("\"slow\""));
    }

    @Test
    public void theRequestIsTrimmedBeforeDispatch() {
        String[] seen = new String[1];
        AiSocketHandler.handle(open("  {\"cmd\":\"ping\"} \r\n", 10_000, new long[1]), new Sink(),
            request -> { seen[0] = request; return "{}"; }, 1024);
        assertEquals("{\"cmd\":\"ping\"}", seen[0]);
    }

    @Test
    public void aRequestThatCannotBeFramedIsNeverDispatched() throws Exception {
        String[] requests = {"", "no newline and the peer stays silent", "123456789\n"};
        int[] limits = {1024, 1024, 8};
        for (int i = 0; i < requests.length; i++) {
            AtomicInteger dispatches = new AtomicInteger();
            Sink sink = new Sink();
            boolean dispatched = AiSocketHandler.handle(open(requests[i], 10_000, new long[1]), sink,
                request -> { dispatches.incrementAndGet(); return "{\"ok\":true}"; }, limits[i]);
            assertFalse(dispatched);
            assertEquals("framing failures never reach the model", 0, dispatches.get());
            JSONObject answer = new JSONObject(sink.text().trim());
            assertFalse(answer.getBoolean("ok"));
            assertTrue(answer.getString("error").startsWith("Read failed"));
            assertTrue(answer.has("error_kind"));
        }
    }

    @Test
    public void theErrorKindsAreDistinct() throws Exception {
        Sink empty = new Sink();
        AiSocketHandler.handle(buffer -> -1, empty, request -> "{}", 1024);
        Sink large = new Sink();
        AiSocketHandler.handle(open("123456789\n", 10, new long[1]), large, request -> "{}", 8);
        Sink silent = new Sink();
        AiSocketHandler.handle(open("abc", 10, new long[1]), silent, request -> "{}", 1024);
        assertEquals("empty", new JSONObject(empty.text()).getString("error_kind"));
        assertEquals("too_large", new JSONObject(large.text()).getString("error_kind"));
        assertEquals("io", new JSONObject(silent.text()).getString("error_kind"));
    }

    @Test
    public void aClosedMidFrameRequestIsDispatchedOnceAndRejectedByTheParser() throws Exception {
        // The peer died after a partial JSON: the dispatcher (which parses) rejects it
        // and the model is never asked.
        AtomicInteger modelCalls = new AtomicInteger();
        Sink sink = new Sink();
        byte[] data = "{\"cmd\":\"aicore.gen".getBytes(StandardCharsets.UTF_8);
        int[] position = {0};
        AiSocketHandler.handle(buffer -> {
            if (position[0] < data.length) { buffer[0] = data[position[0]++]; return 1; }
            return -1;
        }, sink, request -> {
            try {
                new JSONObject(request);
                modelCalls.incrementAndGet();
                return "{\"ok\":true}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"Invalid request\"}";
            }
        }, 1024);
        assertEquals(0, modelCalls.get());
        assertTrue(sink.text().contains("Invalid request"));
    }
}
