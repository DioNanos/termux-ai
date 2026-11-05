package com.termux.app.mcp;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.app.mcp.a11y.AndroidControlService;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.socket.local.LocalClientSocket;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;
import com.termux.shared.net.socket.local.LocalSocketRunConfig;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * In-APK MCP host for the {@code android-control} module.
 *
 * <p>Serves newline-delimited JSON-RPC 2.0 over a private Unix domain socket
 * (filesystem namespace, app sandbox), reusing Termux's {@link LocalSocketManager}.
 * Unlike the one-shot {@code TermuxAiSocketServer}, each connection runs a
 * <b>persistent</b> read-loop so one MCP session can exchange many messages.
 *
 * <p>Security: the socket is private to the app's uid (the platform manager admits the
 * app's own uid or root), and every server start (an <i>epoch</i>) publishes a random
 * token in a {@code 0600} file, verified before the socket is opened. A connection must
 * present that token in {@code params.auth} of its {@code initialize}
 * ({@link McpAuth}); the {@code termux-ai-mcp} shim does that on behalf of a
 * legitimate stdio client. If the token cannot be published and verified the server
 * does not start at all: there is no fallback that accepts a connection without it.
 * The token is a contract and a defence against clients that were never provisioned;
 * it is <b>not</b> isolation between processes of the same uid, which can read the
 * owner-only token file like the shim does. Default mode is <b>observe</b>
 * (snapshot/status only); acting tools (tap/type) require an explicit act-mode flag
 * file.
 *
 * <p>Framing: every connection is read through {@link McpFrameReader}, with a hard limit of
 * {@value McpFrameReader#MAX_FRAME_BYTES} bytes per line applied while the line accumulates and
 * therefore before any parse or authentication. A longer line, or a CR inside a line, is answered
 * once with a fixed {@code frame rejected} error and the connection is closed; nothing of it is
 * interpreted, echoed or logged.
 */
public final class McpSocketServer {

    public static final String LOG_TAG = "McpSocketServer";
    public static final String TITLE = "AndroidControlMcp";

    private static final String DIR_PATH = TermuxConstants.TERMUX_APP.APPS_DIR_PATH + "/termux-ai";
    public static final String SOCKET_FILE_PATH = DIR_PATH + "/mcp.sock";
    public static final String TOKEN_FILE_PATH = DIR_PATH + "/" + McpAuth.TOKEN_FILE_NAME;
    /** Touch this file to allow acting tools (tap/type). Absent = observe-only. */
    public static final String ACT_FLAG_PATH = DIR_PATH + "/mcp-act-enabled";
    /**
     * Content-free audit log (v2). The legacy {@code mcp-audit.log} of earlier
     * builds is deliberately a different name: it is never read, rotated or deleted here.
     */
    public static final String AUDIT_LOG_V2_PATH = DIR_PATH + "/" + McpAuditLog.FILE_NAME;

    private static LocalSocketManager socketServer;
    /** The current server epoch; retired on stop/restart. Guarded by the class lock. */
    private static McpAuth epoch = McpAuth.unavailable(McpAuth.AUTH_NOT_STARTED);
    private static final McpAuditLog AUDIT = new McpAuditLog(new File(DIR_PATH));
    private static volatile String lastAuditStatus = McpAuditLog.AUDIT_NOT_STARTED;
    private static volatile String lastAuthStatus = McpAuth.AUTH_NOT_STARTED;

    private McpSocketServer() {}

    public static synchronized void setup(@NonNull Context context) {
        start(context.getApplicationContext());
    }

    public static synchronized void start(@NonNull Context context) {
        stop();
        new File(DIR_PATH).mkdirs();
        File socketFile = new File(SOCKET_FILE_PATH);
        if (socketFile.exists()) socketFile.delete();

        noteAuditStatus(AUDIT.probe());

        // The token is generated, published privately and atomically and read back
        // BEFORE the socket exists. A failure means no server, never an open one.
        McpAuth fresh = McpAuth.publish(new File(DIR_PATH), McpAuth.TOKEN_FILE_NAME);
        noteAuthStatus(fresh.status());
        if (!fresh.available()) {
            epoch = fresh;
            return;
        }

        LocalSocketManager server = new LocalSocketManager(context,
            new LocalSocketRunConfig(TITLE, SOCKET_FILE_PATH, new Client(fresh)));
        Error error = server.start();
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.getErrorLogString());
            fresh.retire();
            deleteToken();
            noteAuthStatus(McpAuth.AUTH_NOT_STARTED);
            return;
        }
        epoch = fresh;
        socketServer = server;
        try {
            File sf = new File(SOCKET_FILE_PATH);
            sf.setReadable(false, false); sf.setReadable(true, true);
            sf.setWritable(false, false); sf.setWritable(true, true);
        } catch (Exception ignored) {}
    }

    /** Ends the epoch: the token stops working, its connections are closed and the token file is removed. */
    public static synchronized void stop() {
        epoch.retire();
        epoch = McpAuth.unavailable(McpAuth.AUTH_NOT_STARTED);
        if (socketServer != null) {
            socketServer.stop();
            socketServer = null;
        }
        deleteToken();
    }

    private static void deleteToken() {
        //noinspection ResultOfMethodCallIgnored
        new File(TOKEN_FILE_PATH).delete();
    }

    private static boolean actEnabled() {
        return new File(ACT_FLAG_PATH).exists();
    }

    /** Last audit diagnostic: one of the fixed {@code AUDIT_*} codes, never payload. */
    public static String auditStatus() {
        return lastAuditStatus;
    }

    /** Last token-publication diagnostic: one of the fixed {@code AUTH_*} codes, never the token. */
    public static String authStatus() {
        return lastAuthStatus;
    }

    /** Surfaces a token problem as a fixed code only, once per change of state. */
    private static void noteAuthStatus(String status) {
        if (status.equals(lastAuthStatus)) return;
        lastAuthStatus = status;
        if (!McpAuth.AUTH_OK.equals(status)) {
            Logger.logError(LOG_TAG, "auth " + status);
        }
    }

    /** Surfaces an audit problem as a fixed code only, once per change of state. */
    private static void noteAuditStatus(String status) {
        if (status.equals(lastAuditStatus)) return;
        lastAuditStatus = status;
        if (!McpAuditLog.AUDIT_OK.equals(status)) {
            Logger.logError(LOG_TAG, "audit " + status);
        }
    }

    /** Routes a tool call to the AccessibilityService, enforcing observe/act. */
    private static String runTool(String name, JSONObject args) {
        String out;
        try {
            out = dispatchTool(name, args);
        } catch (RuntimeException e) {
            noteAuditStatus(AUDIT.recordException(name));
            throw e;
        }
        noteAuditStatus(AUDIT.record(name, out));
        return out;
    }

    private static String dispatchTool(String name, JSONObject args) {
        String out;
        switch (name) {
            case "accessibility_status":
                out = AndroidControlService.statusJson();
                break;
            case "ui_snapshot": {
                AndroidControlService svc = AndroidControlService.INSTANCE;
                out = (svc == null) ? errJson("SERVICE_OFF") : svc.snapshotJson();
                break;
            }
            case "tap": {
                AndroidControlService svc = AndroidControlService.INSTANCE;
                if (svc == null) out = errJson("SERVICE_OFF");
                else if (!actEnabled()) out = errJson("POLICY_DENIED");
                else out = svc.tapJson(args.optString("node_ref", ""));
                break;
            }
            case "type": {
                AndroidControlService svc = AndroidControlService.INSTANCE;
                if (svc == null) out = errJson("SERVICE_OFF");
                else if (!actEnabled()) out = errJson("POLICY_DENIED");
                else out = svc.typeJson(args.optString("node_ref", ""), args.optString("text", ""));
                break;
            }
            case "launch_app": {
                AndroidControlService svc = AndroidControlService.INSTANCE;
                if (svc == null) out = errJson("SERVICE_OFF");
                else if (!actEnabled()) out = errJson("POLICY_DENIED");
                else out = svc.launchAppJson(args.optString("package", ""));
                break;
            }
            case "wake": {
                AndroidControlService svc = AndroidControlService.INSTANCE;
                if (svc == null) out = errJson("SERVICE_OFF");
                else if (!actEnabled()) out = errJson("POLICY_DENIED");
                else out = svc.wakeJson();
                break;
            }
            default:
                out = errJson("UNKNOWN_TOOL");
        }
        return out;
    }

    private static String errJson(String code) {
        try { return new JSONObject().put("ok", false).put("error", code).toString(); }
        catch (Exception e) { return "{\"ok\":false,\"error\":\"" + code + "\"}"; }
    }

    /** The only answer to a frame that was too long or malformed at the framing level. */
    private static final byte[] FRAME_REJECTED =
        "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"frame rejected\"}}\n"
            .getBytes(StandardCharsets.UTF_8);

    /** The per-connection handler of one server epoch. */
    static final class Client extends LocalSocketManagerClientBase {
        private final McpAuth auth;

        Client(McpAuth auth) {
            this.auth = auth;
        }

        @Override
        protected String getLogTag() {
            return LOG_TAG;
        }

        @Override
        public void onClientAccepted(@NonNull LocalSocketManager manager,
                                     @NonNull LocalClientSocket clientSocket) {
            // The socket only narrows who can connect (app uid or root). Who may DO
            // anything is decided here: no tools/list or tools/call before an
            // initialize carrying this epoch's token, and nothing after the epoch ends.
            McpDispatch dispatch = new McpDispatch(McpSocketServer::runTool, auth);
            Runnable untrack = auth.track(() -> clientSocket.closeClientSocket(false));
            try (InputStream in = clientSocket.getInputStream()) {
                OutputStream out = clientSocket.getOutputStream();
                McpFrameReader frames = new McpFrameReader(in);
                for (;;) {
                    byte[] frame;
                    try {
                        frame = frames.next();
                    } catch (McpFrameReader.Rejected tooLong) {
                        // Before or after auth alike: nothing is interpreted, nothing is echoed,
                        // one fixed answer, then the connection ends.
                        if (auth.live()) Logger.logError(LOG_TAG, "frame rejected");
                        try {
                            out.write(FRAME_REJECTED);
                            out.flush();
                        } catch (IOException ignored) { }
                        break;
                    }
                    if (frame == null) break;
                    String line = new String(frame, StandardCharsets.UTF_8);
                    if (line.trim().isEmpty()) continue;
                    String response = dispatch.handle(line);
                    if (response != null && !response.isEmpty()) {
                        out.write((response + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } catch (Exception e) {
                // Fixed text only: an exception message may carry the frame.
                if (auth.live()) Logger.logError(LOG_TAG, "client loop failed");
            } finally {
                untrack.run();
                clientSocket.closeClientSocket(false);
            }
        }
    }
}
