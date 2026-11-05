package com.termux.app.terminal.ai.compat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Production transport: one v1 exchange over the app's LocalSocket
 * (TermuxAiSocketServer.SOCKET_FILE_PATH). Standard Android classes only —
 * the platform socket is touched by the normal {@link AndroidLocalSocket}
 * adapter, so the JVM harness drives the parser/adapter through
 * {@link CompatTransport} peers and drives THIS loop through controlled
 * implementations of the {@link CompatLocalSocket} seam plus a controlled
 * clock (the deadline logic is the production one, not a
 * fake that decides timeouts on its own, and no test needs a symbol that
 * exists only in a stub).
 *
 * The total deadline is EFFECTIVE on every phase: the blocking
 * exchange runs on a short-lived worker while the caller watches the same
 * monotonic clock. When the deadline passes — most importantly while the
 * worker sits in a connect or write that the peer never answers — the caller
 * closes the socket from its own thread (the only lever that interrupts a
 * blocked platform socket operation) and waits a bounded time for the worker
 * to leave the blocked phase and release the resource. If the worker still
 * has not exited by then, the request is reported as POSSIBLY SENT so the
 * caller never retries an exchange whose position is unknown. Inside the
 * worker the per-read idle timeout stays clamped to the remaining total
 * budget, EOF is accepted only inside the deadline, and the response is
 * refused past 8 MiB before any allocation of the decoded string. A worker
 * observed dead is not by itself proof the exchange ended in time: the
 * budget is re-read after the join and the worker's cleanup, before any
 * success is published.
 *
 * "Possibly sent" starts at the FIRST WRITE ATTEMPT: a failed flush or
 * half-close after bytes were written is never treated as proof that the
 * server saw nothing, so the caller never retries. Failures before the
 * first write attempt are the only connect-class failures.
 *
 * RESIDUAL WORKER CONTRACT: the caller closes the socket
 * synchronously (cancel()) and then joins the worker for a bounded window,
 * so a COOPERATIVE worker — a phase that unblocks when the socket closes —
 * is gone before the method returns, and every harness case ends with zero
 * surviving exchange workers. But close() itself can fail without
 * unblocking the worker (or never return), and a worker stuck in a
 * platform call the close does not interrupt can outlive the bounded join:
 * the method then reports E_TIMEOUT possibly-sent and RETURNS WITH THAT
 * DAEMON WORKER STILL ALIVE. This method ALONE therefore does NOT promise
 * zero pending workers in a reused process. The main is one-shot and
 * ends with System.exit after run, so the process exit is what finally
 * delimits a stuck worker; proving that boundary (the real app_process
 * exit, cancellation on the real LocalSocket) stays CI material. No
 * Thread.stop, no signals, no additional execution frontier is used or
 * implied.
 */
public final class LocalSocketCompatTransport implements CompatTransport {

    /**
     * Socket seam: the only socket surface the exchange loop
     * needs. Production implements it with the platform LocalSocket through
     * the normal adapter; tests inject controlled implementations of this
     * interface — a seam in this codebase, so tests compile against the real
     * API surface and no field is ever added to a platform class.
     */
    public interface CompatLocalSocket {
        void connect(String path) throws IOException;
        OutputStream getOutputStream() throws IOException;
        InputStream getInputStream() throws IOException;
        void shutdownOutput() throws IOException;
        void setSoTimeout(int ms) throws IOException;
        void close() throws IOException;
    }

    /** Opens the socket one exchange will use. */
    public interface SocketFactory {
        CompatLocalSocket open();
    }

    /** How often the caller re-checks the clock while the worker runs. */
    static final long DEADLINE_POLL_MS = 10;
    /** Bounded window for the worker to exit its blocked phase after close. */
    static final long CANCEL_JOIN_MS = 2_000;

    private final String socketPath;
    private final SocketFactory sockets;

    public LocalSocketCompatTransport(String socketPath) {
        this(socketPath, new SocketFactory() {
            @Override public CompatLocalSocket open() { return new AndroidLocalSocket(); }
        });
    }

    /** Test/construction seam: injects the socket implementation. */
    LocalSocketCompatTransport(String socketPath, SocketFactory sockets) {
        this.socketPath = socketPath;
        this.sockets = sockets;
    }

    @Override
    public byte[] exchange(String requestLine, CompatClock clock) throws CompatTransportException {
        final long deadline = clock.nowMs() + CompatLimits.TOTAL_DEADLINE_MS;
        final ExchangeRun run = new ExchangeRun(requestLine, clock, deadline);
        final Thread worker = new Thread(run, "termux-ai-compat-exchange");
        worker.setDaemon(true);
        worker.start();
        while (true) {
            long remaining = deadline - clock.nowMs();
            if (remaining <= 0) {
                break; // the total deadline passed: cancel the exchange for real
            }
            try {
                worker.join(Math.min(DEADLINE_POLL_MS, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break; // caller cancelled: same controlled-cancellation path
            }
            if (!worker.isAlive()) {
                joinDead(worker);
                return run.deliver(); // deliver() re-reads the budget after the worker's tail
            }
        }
        // Effective deadline: close from THIS thread — the
        // only lever that interrupts a connect or write blocked past the
        // deadline — then give the worker a bounded window to exit its
        // blocking phase and release the socket.
        run.cancel();
        try {
            worker.join(CANCEL_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // A worker that still has not exited leaves the exchange position
        // unknown: report possibly-sent so the caller never retries.
        boolean possiblySent = worker.isAlive() || run.sendAttempted();
        throw new CompatTransportException(CompatError.E_TIMEOUT, possiblySent, "deadline while blocked");
    }

    /** Happens-before for the outcome of a worker observed as terminated. */
    private static void joinDead(Thread worker) {
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One blocking exchange; runs on the worker thread. */
    private final class ExchangeRun implements Runnable {
        private final String requestLine;
        private final CompatClock clock;
        private final long deadline;
        private final CompatLocalSocket socket;
        private volatile boolean sendAttempted;
        private Object outcome; // written by the worker, read after join

        ExchangeRun(String requestLine, CompatClock clock, long deadline) {
            this.requestLine = requestLine;
            this.clock = clock;
            this.deadline = deadline;
            this.socket = sockets.open();
        }

        boolean sendAttempted() {
            return sendAttempted;
        }

        /** Controlled cancellation: the caller closes the blocked socket. */
        void cancel() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort: the bounded join after it still applies
            }
        }

        @Override
        public void run() {
            try {
                outcome = exchangeBlocking();
            } catch (CompatTransportException e) {
                outcome = e; // typed failures pass through unwrapped
            } catch (Throwable t) {
                outcome = t;
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }

        byte[] deliver() throws CompatTransportException {
            Object o = outcome;
            if (o instanceof byte[]) {
                // Budget re-read AFTER the wait and the worker's own cleanup
                // A worker observed dead proves only its last
                // clock check, not its tail — the final close, or the gap
                // after that check, may still cross the deadline. A success
                // is published only inside the remaining budget (same
                // boundary as the caller loop); past it the exchange is the
                // enumerated E_TIMEOUT with no stdout, classified with the
                // live send flag so a late response is never retried.
                if (deadline - clock.nowMs() <= 0) {
                    throw new CompatTransportException(
                        CompatError.E_TIMEOUT, sendAttempted, "deadline crossed while completing");
                }
                return (byte[]) o;
            }
            if (o instanceof CompatTransportException) throw (CompatTransportException) o;
            if (o instanceof RuntimeException) throw (RuntimeException) o;
            if (o instanceof Error) throw (Error) o;
            // unreachable for a joined worker; fail closed instead of null
            throw new CompatTransportException(CompatError.E_EOF, sendAttempted, "no outcome");
        }

        private byte[] exchangeBlocking() throws CompatTransportException, IOException {
            boolean sendAttemptedHere = false;
            try {
                socket.connect(socketPath);
                if (clock.nowMs() > deadline) {
                    throw new CompatTransportException(CompatError.E_TIMEOUT, false, null);
                }
                OutputStream out = socket.getOutputStream();
                byte[] line = requestLine.getBytes(StandardCharsets.UTF_8);
                sendAttempted = true; // before the first write attempt
                sendAttemptedHere = true;
                out.write(line);
                out.write('\n');
                out.flush();
                socket.shutdownOutput();

                InputStream in = socket.getInputStream();
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                while (true) {
                    long remaining = deadline - clock.nowMs();
                    if (remaining <= 0) {
                        throw new CompatTransportException(CompatError.E_TIMEOUT, sendAttemptedHere, null);
                    }
                    // idle timeout bounded by the remaining total budget
                    socket.setSoTimeout((int) Math.min(CompatLimits.IDLE_TIMEOUT_MS, Math.max(1, remaining)));
                    int n = in.read(chunk);
                    if (n < 0) {
                        // EOF is accepted only inside the deadline
                        if (clock.nowMs() > deadline) {
                            throw new CompatTransportException(CompatError.E_TIMEOUT, sendAttemptedHere, null);
                        }
                        break;
                    }
                    if (buffer.size() + n > CompatLimits.MAX_RESPONSE_BYTES) {
                        throw new CompatTransportException(CompatError.E_RESPONSE_TOO_LARGE, sendAttemptedHere, null);
                    }
                    buffer.write(chunk, 0, n);
                }
                if (clock.nowMs() > deadline) {
                    throw new CompatTransportException(CompatError.E_TIMEOUT, sendAttemptedHere, null);
                }
                if (buffer.size() == 0) {
                    throw new CompatTransportException(CompatError.E_EOF, sendAttemptedHere, null);
                }
                return buffer.toByteArray();
            } catch (CompatTransportException e) {
                throw e;
            } catch (java.net.SocketTimeoutException e) {
                throw new CompatTransportException(CompatError.E_TIMEOUT, sendAttemptedHere, null);
            } catch (IOException e) {
                if (!sendAttemptedHere) {
                    throw new CompatTransportException(CompatError.E_CONNECT, false, null);
                }
                // after a write attempt the request may already be at the
                // server: this is a stream failure after send, never a
                // certifiable pre-send state
                throw new CompatTransportException(CompatError.E_EOF, true, null);
            }
        }
    }
}
