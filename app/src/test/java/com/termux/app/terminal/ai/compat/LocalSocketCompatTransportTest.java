package com.termux.app.terminal.ai.compat;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * JVM tests of the PRODUCTION transport loop: a controlled socket seam ({@link LocalSocketCompatTransport.
 * CompatLocalSocket}) and clock drive {@link LocalSocketCompatTransport}
 * itself — not a fake transport deciding timeouts on its own — so the
 * absolute deadline, the send-flag semantics, the bounded close, the
 * deadline CANCELLATION of a blocked connect/write and the budget RE-READ
 * before a success is published are the shipped code paths. The fake
 * implements the seam interface of this codebase: the test references no
 * platform class and compiles against the real API surface unchanged.
 */
public class LocalSocketCompatTransportTest {

    /** Controlled monotonic clock. */
    static final class ManualClock implements CompatClock {
        volatile long now;
        @Override public long nowMs() { return now; }
    }

    /**
     * Controlled peer: scripted input, failing or clean output phases and —
     * for the blocked-phase proofs — a connect or write that BLOCKS forever on a
     * private latch and only ever returns because close() released it. The
     * blocked phase advances the clock past the deadline first, so the
     * watchdog reaction is observable without waiting wall-clock seconds.
     */
    static class FakeLocalSocket implements LocalSocketCompatTransport.CompatLocalSocket {
        interface InScript { int read(byte[] chunk) throws IOException; }

        InScript inScript;
        boolean connectFails;
        boolean writeFails;
        boolean flushFails;
        boolean shutdownOutputFails;
        volatile boolean closed;
        int lastSoTimeout;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();

        // blocked-phase control: non-null → the phase blocks
        CountDownLatch connectGate;
        CountDownLatch writeGate;
        ManualClock clock;

        @Override public void connect(String path) throws IOException {
            if (connectFails) throw new IOException("refused");
            if (connectGate != null) {
                blockPastDeadline(connectGate);
                throw new IOException("connect unblocked only by close");
            }
        }
        @Override public void setSoTimeout(int ms) { lastSoTimeout = ms; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int b) throws IOException { written.write(b); }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (writeGate != null) {
                        blockPastDeadline(writeGate);
                        throw new IOException("write unblocked only by close");
                    }
                    if (writeFails) throw new IOException("write");
                    written.write(b, off, len);
                }
                @Override public void flush() throws IOException {
                    if (flushFails) throw new IOException("flush");
                }
            };
        }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read(byte[] chunk) throws IOException {
                    return inScript.read(chunk);
                }
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = inScript.read(one);
                    return n > 0 ? (one[0] & 0xff) : n;
                }
            };
        }
        @Override public void shutdownOutput() throws IOException {
            if (shutdownOutputFails) throw new IOException("half-close");
        }
        @Override public void close() {
            closed = true;
            releaseGates();
        }

        private void blockPastDeadline(CountDownLatch gate) throws IOException {
            // the blocked phase parks the clock beyond the 30 s budget; only
            // close() may release the latch — never a spontaneous return
            clock.now = 2 * CompatLimits.TOTAL_DEADLINE_MS;
            try {
                if (!gate.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("blocked phase never released");
                }
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
        }

        private void releaseGates() {
            if (connectGate != null) connectGate.countDown();
            if (writeGate != null) writeGate.countDown();
        }
    }

    private static CompatTransport.CompatTransportException exchangeFails(
            FakeLocalSocket socket, ManualClock clock, String line) {
        try {
            new LocalSocketCompatTransport("/tmp/test.sock", () -> socket)
                .exchange(line, clock);
        } catch (CompatTransport.CompatTransportException e) {
            return e;
        }
        throw new AssertionError("expected a transport failure");
    }

    // --- the deadline interrupts phases that never return ------------

    @Test(timeout = 15_000)
    public void blockedConnectTimesOutWithoutThePeerReturning() {
        // connect parks the clock past the deadline and blocks on a private
        // latch it never releases itself: the exchange must still come back
        // with E_TIMEOUT, possibly-sent=false, because the caller closed the
        // socket at the deadline
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = new FakeLocalSocket();
        socket.clock = clock;
        socket.connectGate = new CountDownLatch(1);
        socket.inScript = chunk -> { throw new AssertionError("no read may happen"); };
        try {
            CompatTransport.CompatTransportException e =
                exchangeFails(socket, clock, "{\"cmd\":\"sys.battery\"}");
            assertEquals(CompatError.E_TIMEOUT, e.code);
            assertFalse("connect-phase timeout is pre-send", e.requestSent);
            assertEquals("nothing was written", 0, socket.written.size());
            assertTrue("the deadline closed the socket", socket.closed);
        } finally {
            socket.releaseGates(); // no pending worker even on failure
        }
    }

    @Test(timeout = 15_000)
    public void blockedWriteTimesOutAsPossiblySent() {
        // the first write parks the clock past the deadline and blocks: the
        // deadline must fire and report the request as possibly sent (the
        // write may have partially reached the server), never a retryable
        // pre-send failure
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = new FakeLocalSocket();
        socket.clock = clock;
        socket.writeGate = new CountDownLatch(1);
        socket.inScript = chunk -> -1;
        try {
            CompatTransport.CompatTransportException e =
                exchangeFails(socket, clock, "{\"cmd\":\"sys.battery\"}");
            assertEquals(CompatError.E_TIMEOUT, e.code);
            assertTrue("a blocked write is already an attempt", e.requestSent);
            assertTrue(socket.closed);
        } finally {
            socket.releaseGates();
        }
    }

    // --- the two counter-proofs, now on the production loop -------

    @Test
    public void eofPastTheDeadlineIsATimeoutNotPartialData() {
        // first chunk lands at t=1000 ms, EOF only at t=60000 ms: with a 30 s
        // total deadline the method must refuse, not return the 3 bytes
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = new FakeLocalSocket();
        final boolean[] served = new boolean[1];
        socket.inScript = chunk -> {
            if (!served[0]) {
                served[0] = true;
                clock.now = 1_000;
                chunk[0] = 'a'; chunk[1] = 'b'; chunk[2] = 'c';
                return 3;
            }
            clock.now = 60_000; // EOF far past the deadline
            return -1;
        };
        CompatTransport.CompatTransportException e =
            exchangeFails(socket, clock, "{\"cmd\":\"sys.battery\"}");
        assertEquals(CompatError.E_TIMEOUT, e.code);
        assertTrue("a written frame means possibly sent", e.requestSent);
        assertTrue(socket.closed);
    }

    @Test
    public void flushFailureAfterTheFullFrameIsAfterSendNeverConnect() {
        // the whole frame was written and then the flush fails: the request
        // may already be at the server, so this is never a pre-send failure
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = new FakeLocalSocket();
        socket.flushFails = true;
        socket.inScript = chunk -> -1;
        String line = "{\"cmd\":\"sys.battery\",\"args\":{}}";
        CompatTransport.CompatTransportException e = exchangeFails(socket, clock, line);
        assertEquals(CompatError.E_EOF, e.code);
        assertTrue(e.requestSent);
        assertEquals(line + "\n", new String(socket.written.toByteArray(), StandardCharsets.UTF_8));
        assertTrue(socket.closed);
    }

    // --- deadline and send-flag semantics of the production loop ------------

    @Test
    public void halfCloseFailureAfterWriteIsAlsoAfterSend() {
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = new FakeLocalSocket();
        socket.shutdownOutputFails = true;
        socket.inScript = chunk -> -1;
        CompatTransport.CompatTransportException e =
            exchangeFails(socket, clock, "{\"cmd\":\"sys.battery\"}");
        assertEquals(CompatError.E_EOF, e.code);
        assertTrue(e.requestSent);
    }

    @Test
    public void firstWriteAttemptFailureIsAlreadyPossiblySent() {
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = new FakeLocalSocket();
        socket.writeFails = true;
        socket.inScript = chunk -> -1;
        CompatTransport.CompatTransportException e =
            exchangeFails(socket, clock, "{\"cmd\":\"sys.battery\"}");
        // "possibly sent" starts at the FIRST write attempt
        assertEquals(CompatError.E_EOF, e.code);
        assertTrue(e.requestSent);
    }

    @Test
    public void connectFailureIsTheOnlyPreSendFailure() {
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = new FakeLocalSocket();
        socket.connectFails = true;
        socket.inScript = chunk -> -1;
        CompatTransport.CompatTransportException e =
            exchangeFails(socket, clock, "{\"cmd\":\"sys.battery\"}");
        assertEquals(CompatError.E_CONNECT, e.code);
        assertFalse(e.requestSent);
        assertTrue(socket.closed);
    }

    @Test
    public void happyPathReturnsTheBytesWithinTheDeadline() throws Exception {
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = new FakeLocalSocket();
        byte[] payload = "{\"ok\":true,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
        final int[] cursor = new int[1];
        socket.inScript = chunk -> {
            if (cursor[0] >= payload.length) return -1;
            int n = Math.min(chunk.length, payload.length - cursor[0]);
            System.arraycopy(payload, cursor[0], chunk, 0, n);
            cursor[0] += n;
            return n;
        };
        byte[] response = new LocalSocketCompatTransport("/tmp/test.sock", () -> socket)
            .exchange("{\"cmd\":\"sys.battery\"}", clock);
        assertArrayEquals(payload, response);
        assertTrue(socket.closed);
        assertTrue(socket.lastSoTimeout > 0);
        assertTrue(socket.lastSoTimeout <= (int) CompatLimits.IDLE_TIMEOUT_MS);
    }

    @Test
    public void idleTimeoutIsClampedToTheRemainingDeadline() {
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = new FakeLocalSocket();
        final boolean[] served = new boolean[1];
        socket.inScript = chunk -> {
            if (!served[0]) {
                served[0] = true;
                clock.now = 26_000; // 4 s left of the 30 s budget
                chunk[0] = 'x';
                return 1;
            }
            throw new java.net.SocketTimeoutException();
        };
        CompatTransport.CompatTransportException e =
            exchangeFails(socket, clock, "{\"cmd\":\"sys.battery\"}");
        assertEquals(CompatError.E_TIMEOUT, e.code);
        assertTrue(e.requestSent);
        // the read timeout the loop set was the residual, not the idle cap
        assertTrue("clamped soTimeout=" + socket.lastSoTimeout,
            socket.lastSoTimeout <= 4_000 && socket.lastSoTimeout >= 1);
    }

    @Test
    public void deadlineIsCheckedAfterConnectBeforeAnyWrite() {
        // the connect phase itself eats the whole budget: the exchange must
        // refuse right after it, before writing (and ever reading) anything
        ManualClock clock = new ManualClock();
        // the scripted connect consumes more than the total budget
        FakeLocalSocket slowConnect = new FakeLocalSocket() {
            @Override public void connect(String path) {
                // NB: resolves to the inherited field, hence the assignment
                clock.now += CompatLimits.TOTAL_DEADLINE_MS + 5;
            }
        };
        slowConnect.clock = clock;
        slowConnect.inScript = chunk -> { throw new AssertionError("no read may happen"); };
        CompatTransport.CompatTransportException e =
            exchangeFails(slowConnect, clock, "{\"cmd\":\"sys.battery\"}");
        assertEquals(CompatError.E_TIMEOUT, e.code);
        assertFalse(e.requestSent);
        assertEquals(0, slowConnect.written.size());
        assertTrue(slowConnect.closed);
    }

    @Test
    public void responseBeyondTheCapIsRefused() {
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = new FakeLocalSocket();
        final long[] servedBytes = new long[1];
        socket.inScript = chunk -> {
            servedBytes[0] += chunk.length;
            return servedBytes[0] > CompatLimits.MAX_RESPONSE_BYTES + 8192 ? -1 : chunk.length;
        };
        CompatTransport.CompatTransportException e =
            exchangeFails(socket, clock, "{\"cmd\":\"sys.battery\"}");
        assertEquals(CompatError.E_RESPONSE_TOO_LARGE, e.code);
        assertTrue(e.requestSent);
        assertTrue(socket.closed);
    }

    @Test
    public void emptyStreamAtEofIsEof() {
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = new FakeLocalSocket();
        socket.inScript = chunk -> -1;
        CompatTransport.CompatTransportException e =
            exchangeFails(socket, clock, "{\"cmd\":\"sys.battery\"}");
        assertEquals(CompatError.E_EOF, e.code);
        assertTrue(e.requestSent);
    }

    // --- the budget is re-read before a success is published -------

    private static int liveExchangeWorkers() {
        int alive = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && "termux-ai-compat-exchange".equals(t.getName())) alive++;
        }
        return alive;
    }

    /**
     * Serves a whole payload with the clock inside the budget; the worker's
     * own cleanup (its final close) then pushes the clock to 60000 with a
     * 30000 deadline when armed — the late tail of the counterexample.
     */
    private static FakeLocalSocket cleanupTailSocket(
            final ManualClock clock, final boolean tailCrossesDeadline, byte[] payload) {
        FakeLocalSocket socket = new FakeLocalSocket() {
            @Override public void close() {
                if (tailCrossesDeadline) {
                    clock.now = 2 * CompatLimits.TOTAL_DEADLINE_MS;
                }
                super.close();
            }
        };
        socket.clock = clock;
        final int[] cursor = new int[1];
        socket.inScript = chunk -> {
            if (cursor[0] >= payload.length) return -1;
            int n = Math.min(chunk.length, payload.length - cursor[0]);
            System.arraycopy(payload, cursor[0], chunk, 0, n);
            cursor[0] += n;
            return n;
        };
        return socket;
    }

    @Test(timeout = 15_000)
    public void lateCompletionDuringWorkerCleanupIsATimeoutNotALateSuccess() {
        // the seam serves a whole valid response inside the budget; the
        // worker's own cleanup (its final close) then pushes the clock to
        // 60000 with a 30000 deadline. A worker observed dead is NOT proof
        // the exchange ended in time: the success must not be
        // published — E_TIMEOUT, classified possibly-sent, never retried
        ManualClock clock = new ManualClock();
        FakeLocalSocket socket = cleanupTailSocket(clock, true,
            "{\"ok\":true,\"data\":{}}".getBytes(StandardCharsets.UTF_8));
        int workersBefore = liveExchangeWorkers();
        CompatTransport.CompatTransportException e =
            exchangeFails(socket, clock, "{\"cmd\":\"sys.battery\"}");
        assertEquals(CompatError.E_TIMEOUT, e.code);
        assertTrue("a delivered response is possibly sent, never retried", e.requestSent);
        assertTrue(socket.closed);
        assertEquals("no exchange worker may survive the refusal",
            workersBefore, liveExchangeWorkers());
    }

    @Test(timeout = 15_000)
    public void completionWithinTheBudgetStillReturnsTheResponse() throws Exception {
        // the twin with a cleanup that does NOT cross the deadline: the same
        // exchange still succeeds — the re-read budget gates late successes
        // only, never in-time ones
        ManualClock clock = new ManualClock();
        byte[] payload = "{\"ok\":true,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
        FakeLocalSocket socket = cleanupTailSocket(clock, false, payload);
        byte[] response = new LocalSocketCompatTransport("/tmp/test.sock", () -> socket)
            .exchange("{\"cmd\":\"sys.battery\"}", clock);
        assertArrayEquals(payload, response);
        assertTrue(socket.closed);
    }

    @Test(timeout = 15_000)
    public void lateCompletionSurfacesAtTheClientBoundaryAsTimeoutWithEmptyStdout()
            throws Exception {
        // the same tail through the FULL client: the envelope is valid and
        // was delivered inside the budget, but the cleanup crossed the
        // deadline — exit 3 with exactly "E_TIMEOUT" on stderr and an EMPTY
        // stdout; the in-budget twin of the same scenario stays exit 0
        byte[] envelope = "{\"ok\":true,\"data\":{\"text\":\"late\"}}"
            .getBytes(StandardCharsets.UTF_8);

        ManualClock lateClock = new ManualClock();
        FakeLocalSocket lateSocket = cleanupTailSocket(lateClock, true, envelope);
        java.io.ByteArrayOutputStream lateOut = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream lateErr = new java.io.ByteArrayOutputStream();
        int workersBefore = liveExchangeWorkers();
        int exit = TermuxApiCompatClient.run(new String[]{"Clipboard"},
            new java.io.ByteArrayInputStream(new byte[0]),
            new java.io.PrintStream(lateOut, true, StandardCharsets.UTF_8),
            new java.io.PrintStream(lateErr, true, StandardCharsets.UTF_8),
            new LocalSocketCompatTransport("/tmp/test.sock", () -> lateSocket), lateClock);
        assertEquals(3, exit);
        assertEquals("a late success must not print anything", "", lateOut.toString("UTF-8"));
        assertEquals("E_TIMEOUT\n", lateErr.toString("UTF-8"));
        assertEquals(workersBefore, liveExchangeWorkers());

        ManualClock okClock = new ManualClock();
        FakeLocalSocket okSocket = cleanupTailSocket(okClock, false, envelope);
        java.io.ByteArrayOutputStream okOut = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream okErr = new java.io.ByteArrayOutputStream();
        int okExit = TermuxApiCompatClient.run(new String[]{"Clipboard"},
            new java.io.ByteArrayInputStream(new byte[0]),
            new java.io.PrintStream(okOut, true, StandardCharsets.UTF_8),
            new java.io.PrintStream(okErr, true, StandardCharsets.UTF_8),
            new LocalSocketCompatTransport("/tmp/test.sock", () -> okSocket), okClock);
        assertEquals(0, okExit);
        assertEquals("late", okOut.toString("UTF-8"));
        assertEquals("", okErr.toString("UTF-8"));
    }
}
