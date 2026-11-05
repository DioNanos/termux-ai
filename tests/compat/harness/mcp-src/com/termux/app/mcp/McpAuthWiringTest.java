package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.termux.app.mcp.a11y.AndroidControlService;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * The real McpSocketServer, start to finish: absent/bad/good over separate
 * connections, restart with a changed token, publication failure and a direct
 * socket client. Real unix sockets where a connection has to outlive a restart.
 */
public class McpAuthWiringTest {

    private static final String S_FRAME = "SENTINEL_FRAME_71aa";

    private static void assertNoLeak(String token) throws Exception {
        for (String l : Logger.CAPTURED) assertFalse("logcat must not carry the token", l.contains(token));
        File[] files = McpAuthFixtures.dir().listFiles();
        if (files != null) for (File f : files) {
            if (!f.isFile() || f.getName().equals(McpAuth.TOKEN_FILE_NAME)) continue;
            String body = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertFalse(f.getName() + " must not carry the token", body.contains(token));
        }
    }

    @Test
    public void absentBadGoodOnSeparateConnections() throws Exception {
        McpAuthFixtures.reset();
        Logger.CAPTURED.clear();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        String token = McpAuthFixtures.readToken();
        AndroidControlService.INSTANCE = svc;
        try {
            // absent
            List<String> absent = McpAuthFixtures.session(client, McpAuthFixtures.callSnapshot(1));
            assertTrue(McpAuthFixtures.isError(absent.get(0), -32002));
            // bad
            List<String> bad = McpAuthFixtures.session(client,
                McpAuthFixtures.initializeWithToken(1, McpAuthFixtures.ZERO_TOKEN), McpAuthFixtures.callSnapshot(2));
            assertTrue(McpAuthFixtures.isError(bad.get(0), -32001));
            assertTrue(McpAuthFixtures.isError(bad.get(1), -32002));
            assertEquals("nothing ran for absent or bad", 0, svc.snapshots.get());
            // good
            List<String> good = McpAuthFixtures.session(client,
                McpAuthFixtures.initializeWithToken(1, token), McpAuthFixtures.callSnapshot(2));
            assertTrue(McpAuthFixtures.isResult(good.get(0)));
            assertTrue(McpAuthFixtures.isResult(good.get(1)));
            assertEquals(1, svc.snapshots.get());
            // and the session of the good connection is not inherited by the next one
            List<String> next = McpAuthFixtures.session(client, McpAuthFixtures.callSnapshot(3));
            assertTrue(McpAuthFixtures.isError(next.get(0), -32002));
            assertEquals(1, svc.snapshots.get());
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
        }
        assertNoLeak(token);
        assertEquals("AUTH_OK", McpSocketServer.authStatus());
    }

    @Test
    public void aFailedReinitializeOnTheRealClientLoopClosesTheSession() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        String token = McpAuthFixtures.readToken();
        AndroidControlService.INSTANCE = svc;
        try {
            List<String> r = McpAuthFixtures.session(client,
                McpAuthFixtures.initializeWithToken(1, token),
                McpAuthFixtures.callSnapshot(2),
                McpAuthFixtures.initializeWithToken(3, McpAuthFixtures.ZERO_TOKEN),
                McpAuthFixtures.callSnapshot(4));
            assertTrue(McpAuthFixtures.isResult(r.get(0)));
            assertTrue(McpAuthFixtures.isResult(r.get(1)));
            assertTrue(McpAuthFixtures.isError(r.get(2), -32001));
            assertTrue("after the failed re-initialize the tool is denied", McpAuthFixtures.isError(r.get(3), -32002));
            assertEquals(1, svc.snapshots.get());
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
        }
    }

    @Test
    public void restartWithAChangedTokenInvalidatesTheOldEpochAndItsConnections() throws Exception {
        McpAuthFixtures.reset();
        Logger.CAPTURED.clear();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        AndroidControlService.INSTANCE = svc;
        try {
            McpAuthFixtures.startServing();
            String t1 = McpAuthFixtures.readToken();
            assertNotNull(t1);
            McpAuthFixtures.RawClient old = new McpAuthFixtures.RawClient(McpSocketServer.SOCKET_FILE_PATH);
            assertTrue(McpAuthFixtures.isResult(old.request(McpAuthFixtures.initializeWithToken(1, t1))));
            assertTrue(McpAuthFixtures.isResult(old.request(McpAuthFixtures.callSnapshot(2))));
            assertEquals(1, svc.snapshots.get());

            McpAuthFixtures.startServing(); // restart: new epoch, new token
            String t2 = McpAuthFixtures.readToken();
            assertNotNull(t2);
            assertFalse("the token changed", t1.equals(t2));

            // the connection of the previous epoch is dead: closed, or at least denied
            String answer;
            try {
                old.send(McpAuthFixtures.callSnapshot(3));
                answer = old.recv(5000);
            } catch (java.io.IOException closed) {
                answer = McpAuthFixtures.RawClient.EOF;
            }
            McpAuthFixtures.assertClosedOrDenied("old connection after the restart", answer);
            assertEquals("the old connection ran nothing after the restart", 1, svc.snapshots.get());
            old.close();

            // the old token no longer opens a session on the new epoch; the new one does
            try (McpAuthFixtures.RawClient c1 = new McpAuthFixtures.RawClient(McpSocketServer.SOCKET_FILE_PATH)) {
                assertTrue(McpAuthFixtures.isError(c1.request(McpAuthFixtures.initializeWithToken(1, t1)), -32001));
                assertTrue(McpAuthFixtures.isError(c1.request(McpAuthFixtures.callSnapshot(2)), -32002));
            }
            try (McpAuthFixtures.RawClient c2 = new McpAuthFixtures.RawClient(McpSocketServer.SOCKET_FILE_PATH)) {
                assertTrue(McpAuthFixtures.isResult(c2.request(McpAuthFixtures.initializeWithToken(1, t2))));
                assertTrue(McpAuthFixtures.isResult(c2.request(McpAuthFixtures.callSnapshot(2))));
            }
            assertEquals(2, svc.snapshots.get());
            assertNoLeak(t1);
            assertNoLeak(t2);
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
            LocalSocketManager.SERVE = false;
        }
    }

    @Test
    public void stopRemovesTheTokenClosesConnectionsAndRefusesNewOnes() throws Exception {
        McpAuthFixtures.reset();
        try {
            McpAuthFixtures.startServing();
            String t = McpAuthFixtures.readToken();
            McpAuthFixtures.RawClient c = new McpAuthFixtures.RawClient(McpSocketServer.SOCKET_FILE_PATH);
            assertTrue(McpAuthFixtures.isResult(c.request(McpAuthFixtures.initializeWithToken(1, t))));
            McpSocketServer.stop();
            assertFalse("token file is gone", new File(McpSocketServer.TOKEN_FILE_PATH).exists());
            String after;
            try {
                c.send(McpAuthFixtures.callSnapshot(2));
                after = c.recv(5000);
            } catch (java.io.IOException closed) {
                after = McpAuthFixtures.RawClient.EOF;
            }
            McpAuthFixtures.assertClosedOrDenied("old connection after the stop", after);
            c.close();
            McpAuthFixtures.Attempt after2 = McpAuthFixtures.sessionAttempt(McpSocketServer.SOCKET_FILE_PATH, t, 5000);
            assertTrue("nobody gets a session after stop, observed " + after2, after2.provesNoSession());
        } finally {
            LocalSocketManager.SERVE = false;
            McpSocketServer.stop();
        }
    }

    @Test
    public void aPublicationErrorMeansNoServerAndAFixedCode() throws Exception {
        // 1) the token path cannot be replaced
        McpAuthFixtures.primeAuthOk();
        McpAuthFixtures.reset();
        Logger.CAPTURED.clear();
        File blocker = new File(McpSocketServer.TOKEN_FILE_PATH);
        assertTrue(blocker.mkdir());
        Files.write(new File(blocker, "keep").toPath(), new byte[1]);
        McpSocketServer.start(new android.content.Context());
        assertEquals("the socket manager was never started", 0, LocalSocketManager.STARTS);
        assertEquals("AUTH_PUBLISH_FAILED", McpSocketServer.authStatus());
        assertEquals(Arrays.asList("McpSocketServer: auth AUTH_PUBLISH_FAILED"), Logger.CAPTURED);
        assertFalse("no socket file", new File(McpSocketServer.SOCKET_FILE_PATH).exists());

        // 2) the application directory is not a directory
        McpAuthFixtures.primeAuthOk();
        McpAuthFixtures.reset();
        McpAuthFixtures.deleteTree(McpAuthFixtures.dir());
        Files.write(McpAuthFixtures.dir().toPath(), new byte[0]);
        Logger.CAPTURED.clear();
        McpSocketServer.start(new android.content.Context());
        assertEquals(0, LocalSocketManager.STARTS);
        assertEquals("AUTH_DIR_UNSAFE", McpSocketServer.authStatus());
        assertTrue(Logger.CAPTURED.contains("McpSocketServer: auth AUTH_DIR_UNSAFE"));
        McpAuthFixtures.deleteTree(McpAuthFixtures.dir());
        McpAuthFixtures.reset();
    }

    @Test
    public void aFailedRestartLeavesNoOldTokenAndNoListener() throws Exception {
        McpAuthFixtures.reset();
        try {
            McpAuthFixtures.startServing();
            String t1 = McpAuthFixtures.readToken();
            McpAuthFixtures.RawClient old = new McpAuthFixtures.RawClient(McpSocketServer.SOCKET_FILE_PATH);
            assertTrue(McpAuthFixtures.isResult(old.request(McpAuthFixtures.initializeWithToken(1, t1))));

            // make the next publication fail: a non-empty directory where the token goes
            // (start() stops the old epoch first, which removes the old token file)
            McpSocketServer.stop();
            File blocker = new File(McpSocketServer.TOKEN_FILE_PATH);
            assertTrue(blocker.mkdir());
            Files.write(new File(blocker, "keep").toPath(), new byte[1]);
            int starts = LocalSocketManager.STARTS;
            McpAuthFixtures.startServing();
            assertEquals("no new server", starts, LocalSocketManager.STARTS);
            assertEquals("AUTH_PUBLISH_FAILED", McpSocketServer.authStatus());
            assertTrue(blocker.isDirectory());
            String after;
            try {
                old.send(McpAuthFixtures.callSnapshot(2));
                after = old.recv(5000);
            } catch (java.io.IOException closed) {
                after = McpAuthFixtures.RawClient.EOF;
            }
            McpAuthFixtures.assertClosedOrDenied("old connection after the stop", after);
            old.close();
            McpAuthFixtures.Attempt after2 = McpAuthFixtures.sessionAttempt(McpSocketServer.SOCKET_FILE_PATH, t1, 5000);
            assertTrue("nobody gets a session after the failed restart, observed " + after2, after2.provesNoSession());
        } finally {
            LocalSocketManager.SERVE = false;
            McpSocketServer.stop();
        }
    }

    @Test
    public void aSocketStartFailureLeavesNoTokenBehind() throws Exception {
        McpAuthFixtures.reset();
        LocalSocketManager.FAIL_START = true;
        try {
            McpSocketServer.start(new android.content.Context());
        } finally {
            LocalSocketManager.FAIL_START = false;
        }
        assertEquals(1, LocalSocketManager.STARTS);
        assertFalse("no token for a server that is not running", new File(McpSocketServer.TOKEN_FILE_PATH).exists());
        assertEquals("AUTH_NOT_STARTED", McpSocketServer.authStatus());
    }

    @Test
    public void hostileFramesNeverEchoOrLogTheirContentOrTheToken() throws Exception {
        McpAuthFixtures.reset();
        Logger.CAPTURED.clear();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        String token = McpAuthFixtures.readToken();
        try {
            List<String> r = McpAuthFixtures.session(client,
                "{ not json " + S_FRAME + " " + token,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + S_FRAME + "\",\"params\":{\"auth\":\"" + token + "\"}}",
                McpAuthFixtures.initialize(2, "{\"auth\":\"" + S_FRAME + "\"}"),
                McpAuthFixtures.initializeWithToken(3, token),
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"" + S_FRAME + "\",\"arguments\":{\"text\":\"" + token + "\"}}}");
            for (String line : r) {
                assertFalse("response echoes the frame", line.contains(S_FRAME));
                assertFalse("response carries the token", line.contains(token));
            }
            for (String l : Logger.CAPTURED) {
                assertFalse("log echoes the frame", l.contains(S_FRAME));
                assertFalse("log carries the token", l.contains(token));
            }
            assertNoLeak(token);
        } finally {
            McpSocketServer.stop();
        }
    }

    @Test
    public void aRefusedSessionLeavesNoAuditTrailOfAToolThatNeverRan() throws Exception {
        McpAuthFixtures.reset();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        try {
            McpAuthFixtures.session(client, McpAuthFixtures.callSnapshot(1),
                McpAuthFixtures.initialize(2, "{}"), McpAuthFixtures.callSnapshot(3));
        } finally {
            McpSocketServer.stop();
        }
        File v2 = new File(McpSocketServer.AUDIT_LOG_V2_PATH);
        assertTrue("no audit line for a call that was refused", !v2.exists() || Files.size(v2.toPath()) == 0);
    }

    @Test
    public void aConnectionAcceptedAfterTheEpochEndedIsClosedAndNeverServed() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        AndroidControlService.INSTANCE = svc;
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        try {
            McpAuthFixtures.startServing();
            String token = McpAuthFixtures.readToken();
            LocalSocketManager.ACCEPT_GATE = gate; // controlled rendezvous: accepted, not yet handed to the client
            try (McpAuthFixtures.RawClient late = new McpAuthFixtures.RawClient(McpSocketServer.SOCKET_FILE_PATH)) {
                assertTrue("the connection was accepted by the listener",
                    LocalSocketManager.ACCEPTED.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS));
                McpSocketServer.stop(); // the epoch ends while the accepted connection is still in flight
                gate.countDown();       // ... and only now it reaches the client of the retired epoch
                try {
                    late.send(McpAuthFixtures.initializeWithToken(1, token));
                } catch (java.io.IOException closedAlready) {
                    // the close may win the race: the EOF below is what proves it
                }
                String first = late.recv(5000);
                assertNotNull("an observed close, not a timeout", first);
                assertEquals("the late connection is closed, not answered", McpAuthFixtures.RawClient.EOF, first);
            }
            assertEquals("no tool ran for the late connection", 0, svc.snapshots.get());
        } finally {
            gate.countDown();
            AndroidControlService.INSTANCE = null;
            LocalSocketManager.SERVE = false;
            McpSocketServer.stop();
        }
    }

    @Test
    public void aLiveSilentListenerIsInconclusiveNeverSafe() throws Exception {
        // the counter-proof: a listener that accepts, stays open and says nothing
        File dir = java.nio.file.Files.createTempDirectory("silent").toFile();
        String path = new File(dir, "s.sock").getPath();
        java.nio.channels.ServerSocketChannel server =
            java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
        server.bind(java.net.UnixDomainSocketAddress.of(path));
        java.util.concurrent.atomic.AtomicReference<java.nio.channels.SocketChannel> accepted = new java.util.concurrent.atomic.AtomicReference<>();
        Thread acceptor = new Thread(() -> {
            try { accepted.set(server.accept()); } catch (java.io.IOException ignored) { }
        });
        acceptor.start();
        try {
            McpAuthFixtures.Attempt a = McpAuthFixtures.sessionAttempt(path, McpAuthFixtures.ZERO_TOKEN, 400);
            assertEquals("a timeout is not a proof", McpAuthFixtures.Attempt.TIMEOUT, a);
            assertFalse(a.provesNoSession());
        } finally {
            java.nio.channels.SocketChannel ch = accepted.get();
            if (ch != null) ch.close();
            server.close();
            acceptor.join(2000);
        }
        // and the helper does tell the real outcomes apart
        assertEquals(McpAuthFixtures.Attempt.REFUSED, McpAuthFixtures.sessionAttempt(path, McpAuthFixtures.ZERO_TOKEN, 400));
    }
}
