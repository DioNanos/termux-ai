package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The shim core (McpStdioShim.run): framing, token insertion, limits, fixed
 * diagnostics, no retry, no stray bytes on stdout. The server side is a controlled
 * stand-in; the real unix socket path is covered by McpAuthE2ETest.
 */
public class McpStdioShimTest {

    private static final String TOKEN = "f00dfeedf00dfeedf00dfeedf00dfeedf00dfeedf00dfeedf00dfeedf00dfeed";
    private static final String S_PATH = "SENTINEL_PATH_5d19";
    private static final String S_BODY = "SENTINEL_BODY_c3a2";

    /** Blocking in-memory stream: the server's side of the wire. */
    static final class QueueIn extends InputStream {
        private static final byte[] END = new byte[0];
        private final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        private byte[] cur = new byte[0];
        private int pos;
        void push(byte[] b) { q.add(b); }
        void eof() { q.add(END); }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            while (pos == cur.length) {
                try {
                    cur = q.take();
                } catch (InterruptedException e) {
                    throw new IOException("interrupted");
                }
                pos = 0;
                if (cur == END) {
                    q.add(END);
                    return -1;
                }
            }
            int n = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    /** A server stand-in: records every byte it receives, answers per received line. */
    static final class FakeLink implements McpStdioShim.Link {
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final QueueIn fromServer = new QueueIn();
        final StringBuilder pending = new StringBuilder();
        /** By default the server answers every frame with one line, like the real one. */
        volatile Function<String, byte[]> responder = line -> "{}\n".getBytes(StandardCharsets.UTF_8);
        /** When false, shutdownOutput does not make the server finish: it stays connected (a silent server). */
        volatile boolean cooperative = true;
        volatile boolean closed;
        volatile boolean shut;
        volatile int failWriteAfterFrames = -1;
        volatile CountDownLatch gate;
        final AtomicInteger frames = new AtomicInteger();

        @Override public InputStream input() { return fromServer; }
        @Override public OutputStream output() {
            return new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    CountDownLatch g = gate;
                    if (g != null) {
                        try { g.await(); } catch (InterruptedException e) { throw new IOException("interrupted"); }
                    }
                    synchronized (received) {
                        if (failWriteAfterFrames >= 0 && frames.get() >= failWriteAfterFrames) throw new IOException("server gone");
                        received.write(b, off, len);
                        for (int i = off; i < off + len; i++) {
                            if (b[i] == '\n') {
                                String line = new String(pending.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                                pending.setLength(0);
                                frames.incrementAndGet();
                                byte[] answer = responder.apply(line);
                                if (answer != null) fromServer.push(answer);
                            } else {
                                pending.append((char) (b[i] & 0xff));
                            }
                        }
                    }
                }
            };
        }
        @Override public void shutdownOutput() { shut = true; if (cooperative) fromServer.eof(); }
        @Override public void close() { closed = true; fromServer.eof(); }

        List<String> lines() {
            String all;
            synchronized (received) { all = new String(received.toByteArray(), StandardCharsets.UTF_8); }
            if (all.isEmpty()) return new ArrayList<>();
            String[] parts = all.split("\n", -1);
            int n = all.endsWith("\n") ? parts.length - 1 : parts.length;
            return new ArrayList<>(Arrays.asList(parts).subList(0, n));
        }
        byte[] raw() { synchronized (received) { return received.toByteArray(); } }
    }

    static final class Result {
        int code;
        String stdout;
        String stderr;
        final AtomicInteger connects = new AtomicInteger();
        final List<Integer> exits = new ArrayList<>();
        FakeLink link = new FakeLink();
    }

    private static File dirWithToken(String token) throws IOException {
        File dir = Files.createTempDirectory("shimdir").toFile();
        if (token != null) Files.write(new File(dir, McpStdioShim.TOKEN_NAME).toPath(), token.getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static Result run(InputStream stdin, File dir, FakeLink link, IOException connectFails) throws Exception {
        return run(stdin, dir, link, connectFails, McpStdioShim.DRAIN_MS);
    }

    private static Result run(InputStream stdin, File dir, FakeLink link, IOException connectFails, long drainMs) throws Exception {
        final Result r = new Result();
        r.link = link;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        r.code = McpStdioShim.run(stdin, out, new PrintStream(err, true, "UTF-8"), dir.getPath(),
            path -> {
                r.connects.incrementAndGet();
                if (connectFails != null) throw connectFails;
                return link;
            },
            c -> { synchronized (r.exits) { r.exits.add(c); } }, drainMs);
        r.stdout = new String(out.toByteArray(), StandardCharsets.UTF_8);
        r.stderr = new String(err.toByteArray(), StandardCharsets.UTF_8);
        return r;
    }

    private static Result run(String clientBytes, FakeLink link) throws Exception {
        return run(new ByteArrayInputStream(clientBytes.getBytes(StandardCharsets.UTF_8)), dirWithToken(TOKEN), link, null);
    }

    private static void assertQuiet(Result r, String... secrets) {
        for (String s : secrets) {
            assertFalse("stdout must not carry a secret", r.stdout.contains(s));
            assertFalse("stderr must not carry a secret", r.stderr.contains(s));
        }
    }

    // ---- token insertion ----

    @Test
    public void initializeGetsTheTokenAndEveryOtherFieldIsKept() throws Exception {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\","
            + "\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"c\",\"version\":\"1\"}}}";
        FakeLink link = new FakeLink();
        link.responder = l -> "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}\n".getBytes(StandardCharsets.UTF_8);
        Result r = run(init + "\n", link);
        assertEquals(0, r.code);
        List<String> got = link.lines();
        assertEquals("exactly the client's own initialize, no extra frame", 1, got.size());
        JSONObject o = new JSONObject(got.get(0));
        assertEquals("2.0", o.getString("jsonrpc"));
        assertEquals(7, o.getInt("id"));
        assertEquals("initialize", o.getString("method"));
        JSONObject p = o.getJSONObject("params");
        assertEquals("2024-11-05", p.getString("protocolVersion"));
        assertTrue(p.getJSONObject("capabilities").getJSONObject("roots").getBoolean("listChanged"));
        assertEquals("c", p.getJSONObject("clientInfo").getString("name"));
        assertEquals("1", p.getJSONObject("clientInfo").getString("version"));
        assertTrue("the token is params.auth", TOKEN.equals(p.getString("auth")));
        assertEquals("response is relayed untouched", "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}\n", r.stdout);
        assertEquals("", r.stderr);
        assertQuiet(r, TOKEN);
    }

    @Test
    public void aStringIdAndAMissingParamsAreHandled() throws Exception {
        FakeLink link = new FakeLink();
        Result r = run("{\"jsonrpc\":\"2.0\",\"id\":\"abc-1\",\"method\":\"initialize\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":null}\n", link);
        assertEquals(0, r.code);
        List<String> got = link.lines();
        assertEquals(2, got.size());
        JSONObject a = new JSONObject(got.get(0));
        assertEquals("abc-1", a.getString("id"));
        assertTrue(TOKEN.equals(a.getJSONObject("params").getString("auth")));
        JSONObject b = new JSONObject(got.get(1));
        assertEquals(2, b.getInt("id"));
        assertTrue(TOKEN.equals(b.getJSONObject("params").getString("auth")));
    }

    @Test
    public void aClientSuppliedAuthIsReplacedNeverForwarded() throws Exception {
        FakeLink link = new FakeLink();
        Result r = run("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"auth\":\"" + S_BODY + "\",\"x\":1}}\n", link);
        assertEquals(0, r.code);
        String sent = link.lines().get(0);
        assertFalse("the client's own auth value is gone", sent.contains(S_BODY));
        JSONObject p = new JSONObject(sent).getJSONObject("params");
        assertTrue(TOKEN.equals(p.getString("auth")));
        assertEquals(1, p.getInt("x"));
    }

    @Test
    public void aParamsThatIsNotAnObjectIsForwardedUntouchedWithoutTheToken() throws Exception {
        FakeLink link = new FakeLink();
        String line = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":\"" + S_BODY + "\"}";
        Result r = run(line + "\n", link);
        assertEquals(0, r.code);
        assertEquals(Arrays.asList(line), link.lines());
        assertFalse(new String(link.raw(), StandardCharsets.UTF_8).contains(TOKEN));
    }

    @Test
    public void everyOtherFrameIsForwardedByteForByteAndNeverCarriesTheToken() throws Exception {
        String[] frames = {
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "{ \"jsonrpc\" : \"2.0\" ,\"id\":2, \"method\":\"tools/list\" }",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"type\",\"arguments\":{\"text\":\"caffè ☃ \\\"q\\\"\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"initialize\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":[\"initialize\"]}",
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"Initialize\"}",
            "this is not json " + S_BODY,
            "[1,2,3]",
        };
        FakeLink link = new FakeLink();
        Result r = run(String.join("\n", frames) + "\n", link);
        assertEquals(0, r.code);
        assertEquals(Arrays.asList(frames), link.lines());
        assertFalse("the token goes only into an initialize", new String(link.raw(), StandardCharsets.UTF_8).contains(TOKEN));
    }

    @Test
    public void noInitializeIsFabricatedInFrontOfTheClientsFirstFrame() throws Exception {
        FakeLink link = new FakeLink();
        String first = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        Result r = run(first + "\n", link);
        assertEquals(0, r.code);
        assertEquals("the server sees only what the client sent", Arrays.asList(first), link.lines());
    }

    // ---- framing ----

    @Test
    public void blankLinesAreSkippedAndCrlfIsNormalised() throws Exception {
        FakeLink link = new FakeLink();
        Result r = run("\n   \n\t\n{\"id\":1,\"method\":\"tools/list\"}\r\n\r\n", link);
        assertEquals(0, r.code);
        assertEquals(Arrays.asList("{\"id\":1,\"method\":\"tools/list\"}"), link.lines());
    }

    @Test
    public void aCarriageReturnInsideAFrameIsRefusedAndNothingAfterItIsSent() throws Exception {
        FakeLink link = new FakeLink();
        String smuggle = "{\"id\":1,\"method\":\"tools/list\"}\r{\"id\":2,\"method\":\"tools/call\"}";
        Result r = run("{\"id\":0,\"method\":\"ping\"}\n" + smuggle + "\n{\"id\":3,\"method\":\"tools/list\"}\n", link);
        assertEquals(McpStdioShim.EXIT_FRAME, r.code);
        assertEquals("android-control: E_FRAME\n", r.stderr);
        assertTrue("at most the answer to the frame before the bad one", r.stdout.equals("") || r.stdout.equals("{}\n"));
        assertEquals("only what came before the bad frame", Arrays.asList("{\"id\":0,\"method\":\"ping\"}"), link.lines());
    }

    @Test
    public void anOversizedFrameIsRefusedNeverTruncated() throws Exception {
        FakeLink link = new FakeLink();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < McpStdioShim.MAX_FRAME_BYTES + 1; i++) big.append('x');
        Result r = run("{\"id\":0,\"method\":\"ping\"}\n" + big + "\n{\"id\":3,\"method\":\"tools/list\"}\n", link);
        assertEquals(McpStdioShim.EXIT_FRAME, r.code);
        assertEquals("android-control: E_FRAME\n", r.stderr);
        assertEquals(Arrays.asList("{\"id\":0,\"method\":\"ping\"}"), link.lines());
        assertTrue("nothing of the big frame reached the server", link.raw().length < 100);
    }

    @Test
    public void aFrameOfExactlyTheLimitPasses() throws Exception {
        FakeLink link = new FakeLink();
        StringBuilder exact = new StringBuilder();
        for (int i = 0; i < McpStdioShim.MAX_FRAME_BYTES; i++) exact.append('y');
        Result r = run(exact + "\n", link);
        assertEquals(0, r.code);
        assertEquals(1, link.lines().size());
        assertEquals(McpStdioShim.MAX_FRAME_BYTES, link.lines().get(0).length());
    }

    @Test
    public void aLastFrameWithoutALineFeedAndOneByteReadsStillWork() throws Exception {
        FakeLink link = new FakeLink();
        final byte[] data = "{\"id\":1,\"method\":\"tools/list\"}\n{\"id\":2,\"method\":\"tools/list\"}".getBytes(StandardCharsets.UTF_8);
        InputStream oneByte = new InputStream() {
            int i;
            @Override public int read() { return i < data.length ? data[i++] & 0xff : -1; }
            @Override public int read(byte[] b, int off, int len) { int c = read(); if (c < 0) return -1; b[off] = (byte) c; return 1; }
        };
        Result r = run(oneByte, dirWithToken(TOKEN), link, null);
        assertEquals(0, r.code);
        assertEquals(Arrays.asList("{\"id\":1,\"method\":\"tools/list\"}", "{\"id\":2,\"method\":\"tools/list\"}"), link.lines());
    }

    // ---- local failures: one fixed line, no stdout, never talking without the token ----

    @Test
    public void aMissingOrMalformedTokenStopsBeforeAnyConnection() throws Exception {
        String[] bad = {
            null, "", "f00d", TOKEN.substring(1), TOKEN + "0", TOKEN + "\n", TOKEN.toUpperCase().replace('F', 'G'), "g" + TOKEN.substring(1),
            "é" + TOKEN.substring(2),
        };
        for (String t : bad) {
            FakeLink link = new FakeLink();
            Result r = run(new ByteArrayInputStream("{\"id\":1,\"method\":\"initialize\"}\n".getBytes(StandardCharsets.UTF_8)),
                dirWithToken(t), link, null);
            assertEquals(McpStdioShim.EXIT_NO_TOKEN, r.code);
            assertEquals("android-control: E_NO_TOKEN\n", r.stderr);
            assertEquals("", r.stdout);
            assertEquals("never connects without a token", 0, r.connects.get());
            assertEquals(0, link.raw().length);
        }
        // a directory where the token should be
        File dir = dirWithToken(null);
        assertTrue(new File(dir, McpStdioShim.TOKEN_NAME).mkdir());
        FakeLink link = new FakeLink();
        Result r = run(new ByteArrayInputStream(new byte[0]), dir, link, null);
        assertEquals(McpStdioShim.EXIT_NO_TOKEN, r.code);
        assertEquals(0, r.connects.get());
    }

    @Test
    public void anAbsentServerIsAFixedLocalErrorAndNeverEchoesTheCause() throws Exception {
        FakeLink link = new FakeLink();
        Result r = run(new ByteArrayInputStream("{\"id\":1,\"method\":\"initialize\"}\n".getBytes(StandardCharsets.UTF_8)),
            dirWithToken(TOKEN), link, new IOException("connect failed " + S_PATH + " " + TOKEN));
        assertEquals(McpStdioShim.EXIT_NO_SERVER, r.code);
        assertEquals("android-control: E_NO_SERVER\n", r.stderr);
        assertEquals("", r.stdout);
        assertEquals("one attempt, no retry", 1, r.connects.get());
        assertQuiet(r, S_PATH, TOKEN);
    }

    @Test
    public void aWriteFailureIsFinalAndNothingIsResent() throws Exception {
        FakeLink link = new FakeLink();
        link.failWriteAfterFrames = 1;
        String call = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"tap\",\"arguments\":{\"node_ref\":\"n1\"}}}";
        Result r = run("{\"id\":1,\"method\":\"tools/list\"}\n" + call + "\n" + call + "\n", link);
        assertEquals(McpStdioShim.EXIT_IO, r.code);
        assertEquals("android-control: E_IO\n", r.stderr);
        assertEquals("one connection, never re-opened", 1, r.connects.get());
        assertEquals("the failed tools/call was not delivered, nor sent a second time", 1, link.lines().size());
        assertTrue(link.closed);
        assertQuiet(r, TOKEN);
    }

    @Test
    public void theServerClosingFirstEndsTheRunWithAFixedError() throws Exception {
        final FakeLink link = new FakeLink();
        final QueueIn stdin = new QueueIn();
        final Result[] holder = new Result[1];
        final CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                holder[0] = run(stdin, dirWithToken(TOKEN), link, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        t.setDaemon(true);
        t.start();
        stdin.push("{\"id\":1,\"method\":\"tools/list\"}\n".getBytes(StandardCharsets.UTF_8));
        long end = System.currentTimeMillis() + 5000;
        while (link.frames.get() < 1 && System.currentTimeMillis() < end) Thread.sleep(10);
        link.close(); // the server goes away while the client is still connected
        // production: the exit callback ends the process; here the test closes stdin
        Thread.sleep(200);
        stdin.eof();
        assertTrue("run returned", done.await(5, TimeUnit.SECONDS));
        assertEquals(McpStdioShim.EXIT_IO, holder[0].code);
        assertEquals("android-control: E_IO\n", holder[0].stderr);
        assertTrue("the exit callback was used", !holder[0].exits.isEmpty() && holder[0].exits.get(0) == McpStdioShim.EXIT_IO);
    }

    // ---- output discipline and backpressure ----

    @Test
    public void stdoutCarriesExactlyTheServersBytes() throws Exception {
        FakeLink link = new FakeLink();
        StringBuilder big = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"");
        for (int i = 0; i < 300000; i++) big.append((char) ('a' + i % 26));
        big.append("\"}\n");
        final byte[] bigBytes = big.toString().getBytes(StandardCharsets.UTF_8);
        link.responder = line -> line.contains("tools/list") ? bigBytes : "raw non-json é☃ bytes\n".getBytes(StandardCharsets.UTF_8);
        Result r = run("{\"id\":1,\"method\":\"tools/list\"}\n{\"id\":2,\"method\":\"ping\"}\n", link);
        assertEquals(0, r.code);
        assertEquals(big + "raw non-json é☃ bytes\n", r.stdout);
        assertEquals("", r.stderr);
    }

    @Test
    public void theClientIsReadOnlyAsFastAsTheServerTakesFrames() throws Exception {
        final FakeLink link = new FakeLink();
        link.gate = new CountDownLatch(1);
        final int frameBytes = 100000;
        final int frames = 20;
        final AtomicInteger served = new AtomicInteger();
        InputStream stdin = new InputStream() {
            int frame;
            int inFrame;
            @Override public int read() { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : b[0] & 0xff; }
            @Override public int read(byte[] b, int off, int len) {
                if (frame >= frames) return -1;
                int n = 0;
                while (n < len && frame < frames) {
                    if (inFrame == frameBytes) { b[off + n++] = '\n'; inFrame = 0; frame++; served.addAndGet(1); continue; }
                    b[off + n++] = 'z';
                    inFrame++;
                    served.incrementAndGet();
                }
                return n;
            }
        };
        final Result[] holder = new Result[1];
        final CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                holder[0] = run(stdin, dirWithToken(TOKEN), link, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(500);
        // the server has not taken the first frame: at most that frame and one read buffer were pulled
        assertTrue("stdin read ahead is bounded while the server is blocked (pulled " + served.get() + ")",
            served.get() <= frameBytes + 1 + 2 * 8192);
        link.gate.countDown();
        assertTrue("run finished once the server accepted frames", done.await(30, TimeUnit.SECONDS));
        assertEquals(0, holder[0].code);
        assertEquals(frames, link.lines().size());
    }

    // ---- drain: a request sent without an answer is never a success ----

    private static final String REQ = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    private static void awaitPumpGone() throws Exception {
        long end = System.currentTimeMillis() + 5000;
        for (;;) {
            boolean alive = false;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.isAlive() && t.getName().equals("mcp-shim-pump")) { alive = true; t.join(100); }
            }
            if (!alive) return;
            assertTrue("pump thread ended", System.currentTimeMillis() < end);
        }
    }

    @Test
    public void aServerThatStaysSilentAfterTheClientsEofEndsWithAFixedDrainError() throws Exception {
        FakeLink link = new FakeLink();
        link.responder = l -> null;
        link.cooperative = false; // stays connected, never answers
        long t0 = System.nanoTime();
        Result r = run(new ByteArrayInputStream((REQ + "\n").getBytes(StandardCharsets.UTF_8)), dirWithToken(TOKEN), link, null, 300);
        long ms = (System.nanoTime() - t0) / 1000000;
        assertEquals(McpStdioShim.EXIT_DRAIN, r.code);
        assertEquals("android-control: E_DRAIN\n", r.stderr);
        assertEquals("", r.stdout);
        assertEquals("the request was delivered exactly once, never resent", Arrays.asList(REQ), link.lines());
        assertTrue("the limit applied (" + ms + " ms)", ms >= 250 && ms < 4000);
        assertEquals("one connection", 1, r.connects.get());
        assertTrue(link.closed);
        assertEquals("one owner of the exit", Arrays.asList(McpStdioShim.EXIT_DRAIN), r.exits);
        awaitPumpGone();
    }

    @Test
    public void aServerThatClosesWithoutAnsweringIsTheSameError() throws Exception {
        FakeLink link = new FakeLink();
        link.responder = l -> null; // cooperative: closes at the client's EOF, having said nothing
        Result r = run(REQ + "\n", link);
        assertEquals(McpStdioShim.EXIT_DRAIN, r.code);
        assertEquals("android-control: E_DRAIN\n", r.stderr);
        assertEquals("", r.stdout);
        assertEquals(Arrays.asList(REQ), link.lines());
        awaitPumpGone();
    }

    @Test
    public void aLateAnswerBeforeTheLimitIsDelivered() throws Exception {
        final FakeLink link = new FakeLink();
        link.responder = l -> null;
        link.cooperative = false;
        Thread late = new Thread(() -> {
            try {
                Thread.sleep(300);
                link.fromServer.push("{\"jsonrpc\":\"2.0\",\"id\":1,\"re".getBytes(StandardCharsets.UTF_8));
                Thread.sleep(150);
                link.fromServer.push("sult\":{}}\n".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException ignored) { }
        });
        late.start();
        long t0 = System.nanoTime();
        Result r = run(new ByteArrayInputStream((REQ + "\n").getBytes(StandardCharsets.UTF_8)), dirWithToken(TOKEN), link, null, 3000);
        long ms = (System.nanoTime() - t0) / 1000000;
        late.join();
        assertEquals("completion observed: the normal end", 0, r.code);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n", r.stdout);
        assertEquals("", r.stderr);
        assertTrue("it did not wait for the whole limit (" + ms + " ms)", ms < 2500);
        awaitPumpGone();
    }

    @Test
    public void anAnswerThatNeverCompletesItsLineIsNotACompletion() throws Exception {
        FakeLink link = new FakeLink();
        // one whole answer line, then the beginning of another that never ends
        link.responder = l -> "{}\n{\"jsonrpc\":\"2.0\",\"id\":1,\"res".getBytes(StandardCharsets.UTF_8);
        link.cooperative = false;
        Result r = run(new ByteArrayInputStream((REQ + "\n").getBytes(StandardCharsets.UTF_8)), dirWithToken(TOKEN), link, null, 300);
        assertEquals(McpStdioShim.EXIT_DRAIN, r.code);
        assertEquals("android-control: E_DRAIN\n", r.stderr);
        awaitPumpGone();
    }

    @Test
    public void onlyNotificationsMeanNothingIsOwedAndASilentServerIsNotWaitedFor() throws Exception {
        FakeLink link = new FakeLink();
        link.responder = l -> null;
        link.cooperative = false;
        long t0 = System.nanoTime();
        Result r = run(new ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n".getBytes(StandardCharsets.UTF_8)),
            dirWithToken(TOKEN), link, null, 3000);
        assertEquals(0, r.code);
        assertEquals("", r.stderr);
        assertTrue("no pointless wait", (System.nanoTime() - t0) / 1000000 < 1500);
        awaitPumpGone();
    }

    @Test
    public void everyAnswerInButTheServerKeepsTheConnectionIsAlreadyDone() throws Exception {
        FakeLink link = new FakeLink();
        link.cooperative = false; // answers, but never closes
        long t0 = System.nanoTime();
        Result r = run(new ByteArrayInputStream((REQ + "\n").getBytes(StandardCharsets.UTF_8)), dirWithToken(TOKEN), link, null, 3000);
        assertEquals(0, r.code);
        assertEquals("{}\n", r.stdout);
        assertTrue("the limit was not spent (" + (System.nanoTime() - t0) / 1000000 + " ms)", (System.nanoTime() - t0) / 1000000 < 1500);
        awaitPumpGone();
    }

    @Test
    public void anInterruptedWaitIsJudgedByWhatArrivedNotByTheInterruption() throws Exception {
        final FakeLink link = new FakeLink();
        link.responder = l -> null;
        link.cooperative = false;
        final Result[] holder = new Result[1];
        Thread t = new Thread(() -> {
            try {
                holder[0] = run(new ByteArrayInputStream((REQ + "\n").getBytes(StandardCharsets.UTF_8)), dirWithToken(TOKEN), link, null, 30000);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        t.start();
        long end = System.currentTimeMillis() + 5000;
        while (link.frames.get() < 1 && System.currentTimeMillis() < end) Thread.sleep(10);
        Thread.sleep(200);
        t.interrupt();
        t.join(5000);
        assertFalse("run returned", t.isAlive());
        assertEquals(McpStdioShim.EXIT_DRAIN, holder[0].code);
        assertEquals("android-control: E_DRAIN\n", holder[0].stderr);
        assertEquals(Arrays.asList(REQ), link.lines());
        awaitPumpGone();
    }

    @Test
    public void aWriteFailureAfterAToolsCallStaysAWriteError() throws Exception {
        FakeLink link = new FakeLink();
        link.failWriteAfterFrames = 1;
        String call = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"tap\"}}";
        Result r = run(REQ + "\n" + call + "\n", link);
        assertEquals(McpStdioShim.EXIT_IO, r.code);
        assertEquals("android-control: E_IO\n", r.stderr);
        assertEquals(Arrays.asList(REQ), link.lines());
        awaitPumpGone();
    }

    // ---- the frame limit is the server's limit ----

    @Test
    public void theShimAndTheServerShareOneFrameLimit() {
        assertEquals(McpFrameReader.MAX_FRAME_BYTES, McpStdioShim.MAX_FRAME_BYTES);
        assertEquals(1 << 20, McpStdioShim.MAX_FRAME_BYTES);
    }

    @Test
    public void anInitializeThatWouldExceedTheLimitOnceTheTokenIsInsertedIsRefused() throws Exception {
        // the client frame itself is within the limit; with params.auth it would not be
        String head = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"pad\":\"";
        String tail = "\"}}";
        StringBuilder pad = new StringBuilder();
        int room = McpStdioShim.MAX_FRAME_BYTES - head.length() - tail.length();
        for (int i = 0; i < room; i++) pad.append('p');
        String frame = head + pad + tail;
        assertEquals(McpStdioShim.MAX_FRAME_BYTES, frame.getBytes(StandardCharsets.UTF_8).length);
        FakeLink link = new FakeLink();
        Result r = run(frame + "\n", link);
        assertEquals(McpStdioShim.EXIT_FRAME, r.code);
        assertEquals("android-control: E_FRAME\n", r.stderr);
        assertEquals("the server never saw it", 0, link.raw().length);
    }
}
