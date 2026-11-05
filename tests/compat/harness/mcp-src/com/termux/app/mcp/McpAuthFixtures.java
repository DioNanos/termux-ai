package com.termux.app.mcp;

import com.termux.app.mcp.a11y.AndroidControlService;
import com.termux.shared.net.socket.local.LocalClientSocket;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared helpers of the token/shim tests. They only use API that exists on the base
 * (McpSocketServer.start, the platform client base, the socket overlay), so the bypass
 * test compiles and runs red against the base and green after the fix.
 *
 * Nothing here ever prints a token: assertions use fixed messages.
 */
final class McpAuthFixtures {

    static final String ZERO_TOKEN = "0000000000000000000000000000000000000000000000000000000000000000";

    private McpAuthFixtures() {}

    static File dir() {
        return new File(McpSocketServer.ACT_FLAG_PATH).getParentFile();
    }

    /** Stops any server and empties the private sandbox directory of the run. */
    static void reset() throws Exception {
        McpSocketServer.stop();
        LocalSocketManager.resetCounters();
        LocalSocketManager.SERVE = false;
        File d = dir();
        if (d.exists()) deleteTree(d);
        Files.createDirectories(d.toPath());
        Files.setPosixFilePermissions(d.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }

    static void deleteTree(File f) throws Exception {
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    /** Starts the production server and returns the client the platform manager would call. */
    static LocalSocketManagerClientBase start() {
        McpSocketServer.start(new android.content.Context());
        return LocalSocketManager.LAST_CLIENT;
    }

    /** A successful start+stop, so the once-per-change status log starts from AUTH_OK. */
    static void primeAuthOk() throws Exception {
        reset();
        McpSocketServer.start(new android.content.Context());
        McpSocketServer.stop();
        LocalSocketManager.resetCounters();
    }

    static String readToken() throws Exception {
        File f = new File(McpSocketServer.TOKEN_FILE_PATH);
        if (!f.isFile()) return null;
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Counts how many times a tool body really ran. */
    static final class CountingService extends AndroidControlService {
        final AtomicInteger snapshots = new AtomicInteger();
        @Override public String snapshotJson() {
            snapshots.incrementAndGet();
            return "{\"snapshot_id\":\"snap-1\",\"ttl_ms\":8000,\"nodes\":[]}";
        }
    }

    static String initialize(int id, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\",\"params\":" + paramsJson + "}";
    }

    static String initializeWithToken(int id, String token) {
        return initialize(id, "{\"auth\":\"" + token + "\"}");
    }

    static String toolsList(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/list\"}";
    }

    static String callSnapshot(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"ui_snapshot\",\"arguments\":{}}}";
    }

    /** Runs one connection (all lines in, until EOF) through the production client; returns the response lines. */
    static List<String> session(LocalSocketManagerClientBase client, String... lines) throws Exception {
        StringBuilder in = new StringBuilder();
        for (String l : lines) in.append(l).append('\n');
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        client.onClientAccepted(null, new LocalClientSocket(
            new ByteArrayInputStream(in.toString().getBytes(StandardCharsets.UTF_8)), out));
        String body = new String(out.toByteArray(), StandardCharsets.UTF_8);
        List<String> r = new ArrayList<>();
        if (!body.isEmpty()) r.addAll(Arrays.asList(body.split("\n")));
        return r;
    }

    static boolean isError(String response, int code) {
        return response.contains("\"error\"") && response.contains("\"code\":" + code);
    }

    static boolean isResult(String response) {
        return response.contains("\"result\"") && !response.contains("\"error\"");
    }

    /** A raw client of the REAL unix socket (the stand-in manager must be serving). */
    static final class RawClient implements Closeable {
        static final String EOF = "<EOF>";
        private final SocketChannel ch;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        RawClient(String socketPath) throws IOException {
            ch = SocketChannel.open(UnixDomainSocketAddress.of(socketPath));
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(mcpharness.ChannelStreams.in(ch), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) lines.add(l);
                } catch (IOException ignored) {
                }
                lines.add(EOF);
            }, "raw-client-reader");
            t.setDaemon(true);
            t.start();
        }

        void send(String line) throws IOException {
            ByteBuffer b = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
            while (b.hasRemaining()) ch.write(b);
        }

        /** The next line, {@link #EOF} when the server closed, or null on timeout. */
        String recv(long ms) throws InterruptedException {
            return lines.poll(ms, TimeUnit.MILLISECONDS);
        }

        String request(String line) throws Exception {
            send(line);
            return recv(5000);
        }

        void shutdownOutput() throws IOException {
            ch.shutdownOutput();
        }

        @Override public void close() {
            try { ch.close(); } catch (IOException ignored) { }
        }
    }

    /** What a client observed when it tried to get a session. Only the first three are a proof of "no session". */
    enum Attempt {
        /** The connection was refused. */
        REFUSED,
        /** The server closed the connection (EOF observed, or the write hit a closed peer). */
        CLOSED,
        /** A well-formed JSON-RPC error with a denial code (-32001 / -32002) came back. */
        DENIED,
        /** Nothing arrived in time: proves neither a close nor a denial. */
        TIMEOUT,
        /** An answer arrived that is neither a result nor a denial the contract defines. */
        MALFORMED,
        /** A session was opened. */
        SESSION;

        boolean provesNoSession() {
            return this == REFUSED || this == CLOSED || this == DENIED;
        }
    }

    /**
     * Tries to open a session with {@code token} and reports what was OBSERVED. A timeout is
     * inconclusive, not "no session": a live silent listener must never pass as safe.
     */
    static Attempt sessionAttempt(String socketPath, String token, long timeoutMs) throws Exception {
        RawClient c;
        try {
            c = new RawClient(socketPath);
        } catch (IOException refused) {
            return Attempt.REFUSED;
        }
        try {
            String answer;
            try {
                c.send(initializeWithToken(1, token));
                answer = c.recv(timeoutMs);
            } catch (IOException closed) {
                return Attempt.CLOSED;
            }
            if (answer == null) return Attempt.TIMEOUT;
            if (answer.equals(RawClient.EOF)) return Attempt.CLOSED;
            try {
                org.json.JSONObject o = new org.json.JSONObject(answer);
                if (o.has("result") && !o.has("error")) return Attempt.SESSION;
                org.json.JSONObject e = o.optJSONObject("error");
                int code = e == null ? 0 : e.optInt("code");
                if ("2.0".equals(o.optString("jsonrpc")) && (code == -32001 || code == -32002)) return Attempt.DENIED;
                return Attempt.MALFORMED;
            } catch (org.json.JSONException notJson) {
                return Attempt.MALFORMED;
            }
        } finally {
            c.close();
        }
    }

    /** An observed close (EOF) or a valid denial; a timeout (null) or anything else is inconclusive and fails. */
    static void assertClosedOrDenied(String what, String answer) {
        org.junit.Assert.assertNotNull(what + ": nothing arrived (a timeout proves nothing)", answer);
        org.junit.Assert.assertTrue(what + ": closed or denied, never served",
            answer.equals(RawClient.EOF) || isError(answer, -32002) || isError(answer, -32001));
    }

    /** Starts the production server on a REAL unix socket in the sandbox. */
    static void startServing() {
        LocalSocketManager.SERVE = true;
        McpSocketServer.start(new android.content.Context());
    }
}
