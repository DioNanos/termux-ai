package com.termux.shared.net.socket.local;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

// Overlay stub for the MCP harness. It records every start/stop and, when SERVE is
// set, binds a REAL unix-domain socket (JDK 16+) and hands every accepted
// connection to the production client on its own thread, exactly like the
// platform manager does. stop() closes only the listening socket and, like the
// platform manager, does NOT close the connections already accepted, so the
// tests exercise the worst case: a connection of a previous server epoch that
// stays open after a stop/restart.
public final class LocalSocketManager {
    public static volatile int STARTS;
    public static volatile int STOPS;
    public static volatile boolean SERVE;
    public static volatile boolean FAIL_START;
    public static volatile LocalSocketManagerClientBase LAST_CLIENT;
    /** Released once per accepted connection, before the (optional) gate: a rendezvous for the tests. */
    public static final java.util.concurrent.Semaphore ACCEPTED = new java.util.concurrent.Semaphore(0);
    /** When set, an accepted connection is handed to the client only after the gate opens (a late accept). */
    public static volatile java.util.concurrent.CountDownLatch ACCEPT_GATE;

    public static void resetCounters() {
        STARTS = 0;
        STOPS = 0;
        FAIL_START = false;
        LAST_CLIENT = null;
        ACCEPT_GATE = null;
        ACCEPTED.drainPermits();
    }

    private final LocalSocketRunConfig config;
    private volatile ServerSocketChannel server;

    public LocalSocketManager(android.content.Context context, LocalSocketRunConfig config) {
        this.config = config;
    }

    public com.termux.shared.errors.Error start() {
        STARTS++;
        LAST_CLIENT = config.client;
        if (FAIL_START) return new com.termux.shared.errors.Error();
        if (!SERVE) return null;
        try {
            final ServerSocketChannel s = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            s.bind(UnixDomainSocketAddress.of(config.socketPath));
            server = s;
            Thread acceptor = new Thread(() -> {
                while (true) {
                    final SocketChannel ch;
                    try {
                        ch = s.accept();
                    } catch (IOException e) {
                        return;
                    }
                    final LocalSocketManager self = this;
                    Thread t = new Thread(() -> {
                        ACCEPTED.release();
                        java.util.concurrent.CountDownLatch gate = ACCEPT_GATE;
                        if (gate != null) {
                            try { gate.await(); } catch (InterruptedException e) { return; }
                        }
                        config.client.onClientAccepted(self,
                        new LocalClientSocket(mcpharness.ChannelStreams.in(ch), mcpharness.ChannelStreams.out(ch), () -> {
                            try { ch.close(); } catch (IOException ignored) { }
                        }));
                    });
                    t.setDaemon(true);
                    t.start();
                }
            }, "harness-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
            return null;
        } catch (IOException e) {
            return new com.termux.shared.errors.Error();
        }
    }

    public void stop() {
        STOPS++;
        ServerSocketChannel s = server;
        server = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }
}
