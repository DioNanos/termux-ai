package com.termux.app.mcp;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The stdio side of the {@code android-control} MCP server: {@code app_process <base
 * apk> com.termux.app.mcp.McpStdioShim}, started by the thin {@code termux-ai-mcp}
 * wrapper asset (which only resolves the installed base APK). It bridges an MCP
 * client's stdin/stdout to the server's private unix socket and does one thing the
 * old {@code socat} shim could not: it presents the server token.
 *
 * <p>Framing: newline-delimited JSON-RPC, one frame per line (a single trailing CR
 * is stripped; any other CR is refused so the shim and the server can never split a
 * frame differently). A frame is classified with the platform {@code org.json}, the
 * same parser the server uses. Only a frame that is a JSON object whose
 * {@code method} is the string {@code initialize} is rewritten: the token is put in
 * its {@code params.auth} (replacing anything the client sent there; {@code params}
 * is created when absent) and the original request is otherwise preserved (id,
 * jsonrpc, every other field) - no second initialize is fabricated and nothing is
 * inserted before the client's own. Every other frame, notifications included, is
 * forwarded byte for byte. Frames from the server are copied back untouched.
 *
 * <p>Output discipline: stdout carries only server frames. All diagnostics are one
 * fixed {@code android-control: E_*} line on stderr and a nonzero exit; nothing from
 * a frame, the token or an exception is ever printed. If the token or the server is
 * missing the shim says so and exits - it never talks to the server without the
 * token. Limits: framing is {@link McpFrameReader}, the very reader the server applies
 * before authentication, so a client frame over {@value #MAX_FRAME_BYTES} bytes (or one that
 * would exceed it once the token is inserted) is refused, never truncated; the client is read
 * only after the previous frame was written to the server (backpressure), and the copy back
 * uses a fixed buffer. There is no retry: a frame that could not be written is not resent and
 * the shim exits, so a {@code tools/call} is never sent twice.
 *
 * <p>End of session: when the client closes stdin the shim half-closes the socket and waits,
 * for at most {@value #DRAIN_MS} ms, until every request it sent (a frame this server answers
 * with one line) has been answered by a whole line that reached stdout. Only that observed
 * completion is a normal exit; a server that stays silent past the limit, closes without
 * answering, or stops in the middle of a line gives the fixed {@code E_DRAIN} error and exit
 * code 7. Nothing is ever sent again after the client's end. Whether the final close returns
 * on ART is a device-CI question, not something the JVM tests prove.
 *
 * <p>Not the compat client ({@code TermuxApiCompatClient}, the one-shot
 * {@code ai.sock} bridge): different entry point, different socket, different wire.
 *
 * <p>Contract: running this shim is the provisioned path; any process of the same
 * uid can run it (or read the token file), so it is not a barrier between such
 * processes. Qualification of the real {@code app_process}/ART/stdio behaviour and of
 * the R8 keep rule belongs to the device CI.
 */
public final class McpStdioShim {

    /** Same value as McpSocketServer's directory (TermuxConstants apps dir + /termux-ai); a harness check pins the two. */
    static final String DEFAULT_DIR = "/data/data/com.termux/files/apps/com.termux/termux-ai";
    static final String SOCKET_NAME = "mcp.sock";
    static final String TOKEN_NAME = "mcp.token";
    static final int TOKEN_CHARS = 64;
    static final int MAX_FRAME_BYTES = McpFrameReader.MAX_FRAME_BYTES;
    static final long DRAIN_MS = 5000L;
    private static final long DRAIN_POLL_MS = 20L;
    private static final int COPY_BUFFER = 8192;

    // Exit codes and the fixed stderr vocabulary (nothing else is ever printed).
    static final int EXIT_OK = 0;
    static final int EXIT_NO_TOKEN = 2;
    static final int EXIT_NO_SERVER = 3;
    static final int EXIT_FRAME = 4;
    static final int EXIT_IO = 5;
    static final int EXIT_INTERNAL = 6;
    static final int EXIT_DRAIN = 7;
    static final String E_NO_TOKEN = "E_NO_TOKEN";
    static final String E_NO_SERVER = "E_NO_SERVER";
    static final String E_FRAME = "E_FRAME";
    static final String E_IO = "E_IO";
    static final String E_INTERNAL = "E_INTERNAL";
    static final String E_DRAIN = "E_DRAIN";
    static final String PREFIX = "android-control: ";

    private McpStdioShim() {}

    /** A connected server link (the platform LocalSocket in production). */
    interface Link {
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
        void shutdownOutput() throws IOException;
        void close();
    }

    /** Opens the one connection of this run. */
    interface Connector {
        Link connect(String socketPath) throws IOException;
    }

    /** Process-exit seam: the server closing first must end a main thread blocked on stdin. */
    interface Exit {
        void exit(int code);
    }

    public static void main(String[] argv) {
        final PrintStream err = System.err;
        int code = run(new FileInputStream(FileDescriptor.in), new FileOutputStream(FileDescriptor.out), err,
            DEFAULT_DIR, new PlatformConnector(), new Exit() {
                @Override public void exit(int c) {
                    System.exit(c);
                }
            });
        System.exit(code);
    }

    /**
     * Runs one shim session. Blocks until the client closed stdin and the server
     * answered what it was owed, or until a failure; returns the exit code. The
     * first code to be decided wins (the server closing first calls {@code exit}).
     */
    static int run(InputStream stdin, OutputStream stdout, PrintStream stderr,
                   String dir, Connector connector, Exit exit) {
        return run(stdin, stdout, stderr, dir, connector, exit, DRAIN_MS);
    }

    static int run(InputStream stdin, OutputStream stdout, PrintStream stderr,
                   String dir, Connector connector, Exit exit, long drainMs) {
        final Session s = new Session(stderr, exit);
        try {
            String token = readToken(new File(dir, TOKEN_NAME));
            if (token == null) return s.fail(EXIT_NO_TOKEN, E_NO_TOKEN);
            final Link link;
            try {
                link = connector.connect(new File(dir, SOCKET_NAME).getPath());
            } catch (IOException | RuntimeException e) {
                return s.fail(EXIT_NO_SERVER, E_NO_SERVER);
            }
            s.link = link;
            final InputStream fromServer;
            final OutputStream toServer;
            try {
                fromServer = link.input();
                toServer = link.output();
            } catch (IOException | RuntimeException e) {
                link.close();
                return s.fail(EXIT_NO_SERVER, E_NO_SERVER);
            }

            Thread pump = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] buf = new byte[COPY_BUFFER];
                    try {
                        int n;
                        while ((n = fromServer.read(buf)) > 0) {
                            stdout.write(buf, 0, n);
                            stdout.flush();
                            // Counted only once delivered: "answered" means the client has the line.
                            s.countServerBytes(buf, n);
                        }
                        // The server closed. Expected after our own EOF; a failure otherwise.
                        if (s.clientEof.get()) s.decideAfterDrain();
                        else s.decide(EXIT_IO, E_IO);
                    } catch (IOException | RuntimeException e) {
                        s.decide(EXIT_IO, E_IO);
                    }
                }
            }, "mcp-shim-pump");
            pump.setDaemon(true);
            pump.start();

            McpFrameReader frames = new McpFrameReader(stdin);
            try {
                for (;;) {
                    byte[] frame = frames.next();
                    if (frame == null) break;
                    Prepared p = prepare(frame, token);
                    if (p == null) continue;
                    toServer.write(p.bytes);
                    toServer.write('\n');
                    toServer.flush();
                    if (p.request) s.requestsSent.incrementAndGet();
                }
            } catch (McpFrameReader.Rejected e) {
                s.decide(EXIT_FRAME, E_FRAME);
            } catch (IOException | RuntimeException e) {
                s.decide(EXIT_IO, E_IO);
            }

            if (s.code.get() < 0) {
                // The client is done: say so to the server and let it finish its answers.
                s.clientEof.set(true);
                try {
                    link.shutdownOutput();
                } catch (IOException | RuntimeException e) {
                    s.decide(EXIT_IO, E_IO);
                }
                long deadline = System.nanoTime() + drainMs * 1000000L;
                try {
                    while (s.code.get() < 0 && pump.isAlive() && !s.answeredEverything()) {
                        long left = (deadline - System.nanoTime()) / 1000000L;
                        if (left <= 0) break;
                        pump.join(Math.min(left, DRAIN_POLL_MS));
                    }
                } catch (InterruptedException e) {
                    // The wait was cut short: judged below by what has actually arrived.
                }
                // Only observed completion is a normal end. A drain that ran out, or a
                // server that closed without answering every request that was sent, is
                // a fixed error; nothing is ever sent again.
                s.decideAfterDrain();
            }
            s.decide(EXIT_OK, null);
            link.close();
            return s.code.get();
        } catch (RuntimeException e) {
            return s.fail(EXIT_INTERNAL, E_INTERNAL);
        }
    }

    /** One run's outcome, decided once. */
    private static final class Session {
        final PrintStream stderr;
        final Exit exit;
        final AtomicInteger code = new AtomicInteger(-1);
        final AtomicBoolean clientEof = new AtomicBoolean(false);
        /** Requests (frames that expect an answer) written to the server. */
        final AtomicInteger requestsSent = new AtomicInteger();
        /** Complete answer lines copied back from the server. */
        final AtomicInteger answerLines = new AtomicInteger();
        /** Bytes of an answer line that has not ended yet. */
        final AtomicBoolean partialAnswer = new AtomicBoolean(false);
        volatile Link link;

        Session(PrintStream stderr, Exit exit) {
            this.stderr = stderr;
            this.exit = exit;
        }

        /** First decision wins; a failure prints its one fixed line. */
        void decide(int c, String codeText) {
            if (!code.compareAndSet(-1, c)) return;
            if (codeText != null) {
                stderr.println(PREFIX + codeText);
                stderr.flush();
            }
            Link l = link;
            if (l != null) l.close();
            exit.exit(c);
        }

        void countServerBytes(byte[] b, int n) {
            for (int i = 0; i < n; i++) {
                if (b[i] == '\n') {
                    answerLines.incrementAndGet();
                    partialAnswer.set(false);
                } else {
                    partialAnswer.set(true);
                }
            }
        }

        boolean answeredEverything() {
            return requestsSent.get() <= answerLines.get() && !partialAnswer.get();
        }

        /** Completion is every sent request answered by a whole line; otherwise E_DRAIN. */
        void decideAfterDrain() {
            if (answeredEverything()) decide(EXIT_OK, null);
            else decide(EXIT_DRAIN, E_DRAIN);
        }

        int fail(int c, String codeText) {
            decide(c, codeText);
            return code.get();
        }
    }

    /** A frame ready for the server, and whether the server owes an answer to it. */
    static final class Prepared {
        final byte[] bytes;
        final boolean request;

        Prepared(byte[] bytes, boolean request) {
            this.bytes = bytes;
            this.request = request;
        }
    }

    /**
     * Prepares one client frame (already split and CR-checked by {@link McpFrameReader}) for the
     * server: null to skip (a blank line, which the server also ignores), the rewritten initialize,
     * or the original bytes. {@code request} says whether the server owes one answer line: it does
     * for EVERY non-blank frame (a parse error, a non-object, a missing or non-string method and an
     * unknown method are all answered) except the single no-reply method of the dispatcher.
     */
    static Prepared prepare(byte[] frame, String token) throws McpFrameReader.Rejected {
        boolean blank = true;
        for (int i = 0; i < frame.length && blank; i++) {
            // String.trim() semantics, as in the server's client loop: every byte up to U+0020
            if ((frame[i] & 0xff) > ' ') blank = false;
        }
        if (blank) return null;

        JSONObject req;
        try {
            req = new JSONObject(new String(frame, StandardCharsets.UTF_8));
        } catch (JSONException | RuntimeException e) {
            return new Prepared(frame, true);
        }
        Object method = req.opt("method");
        boolean request = !(method instanceof String && McpDispatch.NO_REPLY_METHOD.equals(method));
        if (!(method instanceof String) || !"initialize".equals(method)) return new Prepared(frame, request);

        Object p = req.opt("params");
        final JSONObject params;
        if (p == null || p == JSONObject.NULL) {
            params = new JSONObject();
        } else if (p instanceof JSONObject) {
            params = (JSONObject) p;
        } else {
            // Not an object: there is nowhere to put the token. The server refuses it.
            return new Prepared(frame, true);
        }
        try {
            params.put("auth", token);
            req.put("params", params);
        } catch (JSONException e) {
            throw new McpFrameReader.Rejected();
        }
        String text = req.toString();
        if (text == null) throw new McpFrameReader.Rejected();
        byte[] out = text.getBytes(StandardCharsets.UTF_8);
        // The rewritten frame must fit the same limit the server enforces.
        if (out.length > MAX_FRAME_BYTES) throw new McpFrameReader.Rejected();
        return new Prepared(out, true);
    }

    /** The token if the file holds exactly one well-formed token, else null (missing, short, long, not lowercase hex). */
    static String readToken(File file) {
        if (!file.isFile()) return null;
        byte[] b = new byte[TOKEN_CHARS + 1];
        try (FileInputStream in = new FileInputStream(file)) {
            int n = 0;
            int r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            if (n != TOKEN_CHARS) return null;
            for (int i = 0; i < n; i++) {
                byte c = b[i];
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                if (!hex) return null;
            }
            return new String(b, 0, n, StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            Arrays.fill(b, (byte) 0);
        }
    }

    /** The platform unix socket (filesystem namespace) as a {@link Link}. */
    private static final class PlatformConnector implements Connector {
        @Override
        public Link connect(String socketPath) throws IOException {
            final LocalSocket socket = new LocalSocket();
            try {
                socket.connect(new LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException e) {
                try { socket.close(); } catch (IOException ignored) { }
                throw e;
            }
            return new Link() {
                @Override public InputStream input() throws IOException { return socket.getInputStream(); }
                @Override public OutputStream output() throws IOException { return socket.getOutputStream(); }
                @Override public void shutdownOutput() throws IOException { socket.shutdownOutput(); }
                @Override public void close() {
                    try { socket.close(); } catch (IOException ignored) { }
                }
            };
        }
    }
}
