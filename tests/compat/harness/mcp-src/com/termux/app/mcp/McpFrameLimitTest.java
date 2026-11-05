package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.termux.app.mcp.a11y.AndroidControlService;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.socket.local.LocalClientSocket;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The frame limit of the server's client loop (before any authentication, and on an
 * authenticated connection) and of the shared reader. Same limit as the shim.
 */
public class McpFrameLimitTest {

    private static final int MAX = McpFrameReader.MAX_FRAME_BYTES;
    private static final String S_FRAME = "SENTINEL_BIGFRAME_4e9d";
    private static final String REJECTED = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"frame rejected\"}}";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    private static List<String> frames(byte[] data) throws Exception {
        McpFrameReader r = new McpFrameReader(new ByteArrayInputStream(data));
        List<String> out = new ArrayList<>();
        byte[] f;
        while ((f = r.next()) != null) out.add(new String(f, StandardCharsets.UTF_8));
        return out;
    }

    private static boolean rejected(byte[] data) throws Exception {
        try {
            frames(data);
            return false;
        } catch (McpFrameReader.Rejected e) {
            return true;
        }
    }

    // ---- the reader ----

    @Test
    public void theLimitIsExactlyOneMebibyteOfWireBytes() throws Exception {
        assertEquals(1 << 20, MAX);
        assertEquals("cap passes", 1, frames(bytes(repeat('a', MAX) + "\n")).size());
        assertTrue("cap + 1 is rejected", rejected(bytes(repeat('a', MAX + 1) + "\n")));
        assertTrue("cap + 1 without any LF is rejected", rejected(bytes(repeat('a', MAX + 1))));
        assertEquals("cap without LF at end of input is a frame", 1, frames(bytes(repeat('a', MAX))).size());
    }

    @Test
    public void theLimitCountsBytesNotCharacters() throws Exception {
        String twoByte = repeat('é', MAX / 2); // exactly MAX bytes
        assertEquals(MAX, bytes(twoByte).length);
        assertEquals(1, frames(bytes(twoByte + "\n")).size());
        assertTrue("one more two-byte char crosses the limit", rejected(bytes(twoByte + "é\n")));
        assertEquals("multi-byte text survives", Arrays.asList("caffè ☃ 😀"), frames(bytes("caffè ☃ 😀\n")));
    }

    @Test
    public void lineTerminatorsAreLfAndCrlfAndNothingElse() throws Exception {
        assertEquals(Arrays.asList("a", "b", "", "c"), frames(bytes("a\r\nb\n\nc")));
        assertTrue("a CR inside a frame is not a terminator, it is refused", rejected(bytes("a\rb\n")));
        assertTrue(rejected(bytes("a\r\rb\n")));
        assertEquals("a CRLF at the cap counts the CR", true, rejected(bytes(repeat('a', MAX) + "\r\n")));
        assertEquals(1, frames(bytes(repeat('a', MAX - 1) + "\r\n")).size());
    }

    @Test
    public void aSenderThatNeverEndsTheLineCostsBoundedMemoryAndBoundedInput() throws Exception {
        final AtomicLong served = new AtomicLong();
        InputStream endless = new InputStream() {
            @Override public int read() { served.incrementAndGet(); return 'x'; }
            @Override public int read(byte[] b, int off, int len) {
                Arrays.fill(b, off, off + len, (byte) 'x');
                served.addAndGet(len);
                return len;
            }
        };
        McpFrameReader r = new McpFrameReader(endless);
        try {
            r.next();
            throw new AssertionError("an endless line must be rejected");
        } catch (McpFrameReader.Rejected expected) {
            // fine
        }
        assertTrue("it read at most the limit plus one read buffer (" + served.get() + ")", served.get() <= MAX + 1 + 8192);
    }

    @Test
    public void framesSplitAcrossReadsAreReassembled() throws Exception {
        final byte[] data = bytes("{\"a\":1}\n{\"b\":2}\r\n{\"c\":3}");
        InputStream oneByte = new InputStream() {
            int i;
            @Override public int read() { return i < data.length ? data[i++] & 0xff : -1; }
            @Override public int read(byte[] b, int off, int len) { int c = read(); if (c < 0) return -1; b[off] = (byte) c; return 1; }
        };
        McpFrameReader r = new McpFrameReader(oneByte);
        List<String> got = new ArrayList<>();
        byte[] f;
        while ((f = r.next()) != null) got.add(new String(f, StandardCharsets.UTF_8));
        assertEquals(Arrays.asList("{\"a\":1}", "{\"b\":2}", "{\"c\":3}"), got);
        assertNull(r.next());
    }

    // ---- the server's client loop ----

    private static String paddedInitialize(String token, int totalBytes) {
        String head = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"auth\":\"" + token + "\",\"pad\":\"";
        String tail = "\"}}";
        int room = totalBytes - head.length() - tail.length();
        assertTrue(room >= 0);
        return head + repeat('p', room) + tail;
    }

    private static String run(LocalSocketManagerClientBase client, byte[] input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        client.onClientAccepted(null, new LocalClientSocket(new ByteArrayInputStream(input), out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void anOversizedLineBeforeAuthIsRejectedAndNothingAfterItRuns() throws Exception {
        McpAuthFixtures.reset();
        Logger.CAPTURED.clear();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        String token = McpAuthFixtures.readToken();
        AndroidControlService.INSTANCE = svc;
        try {
            // the edge case: a VALID initialize with the right token, padded past the cap (1048709 bytes)
            String big = paddedInitialize(token, MAX + 5);
            assertEquals(1048581, bytes(big).length);
            String out = run(client, bytes(big + "\n" + McpAuthFixtures.initializeWithToken(2, token) + "\n" + McpAuthFixtures.callSnapshot(3) + "\n"));
            assertEquals("one fixed answer, then the connection ends", REJECTED + "\n", out);
            assertEquals("no tool ran, the later valid frames were never read", 0, svc.snapshots.get());
            assertFalse(out.contains(token) || out.contains("pppp"));
            for (String l : Logger.CAPTURED) assertFalse("the log carries no frame and no token", l.contains(token) || l.contains("pppp"));
            assertTrue(Logger.CAPTURED.contains("McpSocketServer: frame rejected"));
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
        }
    }

    @Test
    public void theLimitIsInclusiveOnTheServerToo() throws Exception {
        McpAuthFixtures.reset();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        String token = McpAuthFixtures.readToken();
        try {
            String atCap = paddedInitialize(token, MAX);
            assertEquals(MAX, bytes(atCap).length);
            String out = run(client, bytes(atCap + "\n"));
            assertTrue("a frame of exactly the cap is served", McpAuthFixtures.isResult(out.trim()));
            String over = run(client, bytes(paddedInitialize(token, MAX + 1) + "\n"));
            assertEquals(REJECTED + "\n", over);
        } finally {
            McpSocketServer.stop();
        }
    }

    @Test
    public void anOversizedLineOnAnAuthenticatedConnectionEndsItAtOnce() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        String token = McpAuthFixtures.readToken();
        AndroidControlService.INSTANCE = svc;
        try {
            String big = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"ui_snapshot\",\"arguments\":{\"x\":\""
                + S_FRAME + repeat('z', MAX) + "\"}}}";
            String out = run(client, bytes(McpAuthFixtures.initializeWithToken(1, token) + "\n" + McpAuthFixtures.callSnapshot(2) + "\n"
                + big + "\n" + McpAuthFixtures.callSnapshot(4) + "\n"));
            String[] lines = out.split("\n");
            assertEquals(3, lines.length);
            assertTrue(McpAuthFixtures.isResult(lines[0]));
            assertTrue(McpAuthFixtures.isResult(lines[1]));
            assertEquals(REJECTED, lines[2]);
            assertEquals("only the call before the big frame ran", 1, svc.snapshots.get());
            assertFalse(out.contains(S_FRAME));
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
        }
    }

    @Test
    public void aTruncatedOversizedLineIsNeverInterpretedAsAValidFrame() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        String token = McpAuthFixtures.readToken();
        AndroidControlService.INSTANCE = svc;
        try {
            // if the first MAX bytes of this line were parsed as a frame they would be a valid tools/call
            String call = McpAuthFixtures.callSnapshot(2);
            String spaces = repeat(' ', MAX - call.length());
            String out = run(client, bytes(McpAuthFixtures.initializeWithToken(1, token) + "\n" + call + spaces + "junk-after-the-cap\n"));
            String[] lines = out.split("\n");
            assertEquals(2, lines.length);
            assertEquals(REJECTED, lines[1]);
            assertEquals(0, svc.snapshots.get());
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
        }
    }

    @Test
    public void carriageReturnsAndUtf8OnTheServer() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        String token = McpAuthFixtures.readToken();
        AndroidControlService.INSTANCE = svc;
        try {
            // CRLF is a normal line end
            String ok = run(client, bytes(McpAuthFixtures.initializeWithToken(1, token) + "\r\n" + McpAuthFixtures.callSnapshot(2) + "\r\n"));
            assertEquals(2, ok.split("\n").length);
            assertEquals(1, svc.snapshots.get());
            // a CR inside a frame used to split it in two; now the frame is refused whole
            String smuggle = run(client, bytes(McpAuthFixtures.initializeWithToken(1, token) + "\n"
                + McpAuthFixtures.callSnapshot(2) + "\r" + McpAuthFixtures.callSnapshot(3) + "\n"));
            String[] lines = smuggle.split("\n");
            assertEquals(2, lines.length);
            assertEquals(REJECTED, lines[1]);
            assertEquals("neither half of the smuggled line ran", 1, svc.snapshots.get());
            // multi-byte text in a frame is served
            String utf = run(client, bytes(McpAuthFixtures.initializeWithToken(1, token) + "\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ui_snapshot\",\"arguments\":{\"t\":\"caffè ☃\"}}}\n"));
            assertEquals(2, utf.split("\n").length);
            assertEquals(2, svc.snapshots.get());
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
        }
    }

    @Test
    public void aSenderThatNeverEndsItsLineIsCutOffByTheServerWithBoundedInput() throws Exception {
        McpAuthFixtures.reset();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        try {
            final AtomicLong served = new AtomicLong();
            final CountDownLatch done = new CountDownLatch(1);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream endless = new InputStream() {
                @Override public int read() { served.incrementAndGet(); return ' '; }
                @Override public int read(byte[] b, int off, int len) {
                    Arrays.fill(b, off, off + len, (byte) ' ');
                    served.addAndGet(len);
                    return len;
                }
            };
            Thread t = new Thread(() -> {
                client.onClientAccepted(null, new LocalClientSocket(endless, out));
                done.countDown();
            });
            t.setDaemon(true);
            t.start();
            assertTrue("the loop ended by itself", done.await(10, TimeUnit.SECONDS));
            assertEquals(REJECTED + "\n", new String(out.toByteArray(), StandardCharsets.UTF_8));
            assertTrue("bounded input (" + served.get() + ")", served.get() <= MAX + 1 + 8192);
        } finally {
            McpSocketServer.stop();
        }
    }

    @Test
    public void theRealSocketCutsOffAnOversizedDirectClient() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        AndroidControlService.INSTANCE = svc;
        try {
            McpAuthFixtures.startServing();
            String token = McpAuthFixtures.readToken();
            try (McpAuthFixtures.RawClient c = new McpAuthFixtures.RawClient(McpSocketServer.SOCKET_FILE_PATH)) {
                try {
                    c.send(paddedInitialize(token, MAX + 5));
                } catch (IOException cutOff) {
                    // the server may close while the big line is still being written
                }
                String a = c.recv(5000);
                // either the fixed rejection and then EOF, or EOF (when the close beat the read of the answer)
                assertTrue("observed an answer or a close, not a timeout", a != null);
                assertTrue(a.equals(REJECTED) || a.equals(McpAuthFixtures.RawClient.EOF));
                if (a.equals(REJECTED)) assertEquals(McpAuthFixtures.RawClient.EOF, c.recv(5000));
            }
            assertEquals(0, svc.snapshots.get());
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
            com.termux.shared.net.socket.local.LocalSocketManager.SERVE = false;
        }
    }
}
