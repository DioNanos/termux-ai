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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.SecureRandom;

/**
 * In-APK MCP host for the {@code android-control} module.
 *
 * <p>Serves newline-delimited JSON-RPC 2.0 over a private Unix domain socket
 * (filesystem namespace, app sandbox), reusing Termux's {@link LocalSocketManager}.
 * Unlike the one-shot {@code TermuxAiSocketServer}, each connection runs a
 * <b>persistent</b> read-loop so one MCP session can exchange many messages.
 *
 * <p>Security: same-uid socket isolation + a random per-start token written to a
 * {@code 0600} file; the client must present it in {@code initialize}. Default
 * mode is <b>observe</b> (snapshot/status only); acting tools (tap/type) require
 * an explicit act-mode flag file.
 */
public final class McpSocketServer {

    public static final String LOG_TAG = "McpSocketServer";
    public static final String TITLE = "AndroidControlMcp";

    private static final String DIR_PATH = TermuxConstants.TERMUX_APP.APPS_DIR_PATH + "/termux-ai";
    public static final String SOCKET_FILE_PATH = DIR_PATH + "/mcp.sock";
    public static final String TOKEN_FILE_PATH = DIR_PATH + "/mcp.token";
    /** Touch this file to allow acting tools (tap/type). Absent = observe-only. */
    public static final String ACT_FLAG_PATH = DIR_PATH + "/mcp-act-enabled";
    public static final String AUDIT_LOG_PATH = DIR_PATH + "/mcp-audit.log";

    private static LocalSocketManager socketServer;
    private static volatile String token;

    private McpSocketServer() {}

    public static synchronized void setup(@NonNull Context context) {
        start(context.getApplicationContext());
    }

    public static synchronized void start(@NonNull Context context) {
        stop();
        new File(DIR_PATH).mkdirs();
        File socketFile = new File(SOCKET_FILE_PATH);
        if (socketFile.exists()) socketFile.delete();

        token = newToken();
        writeOwnerOnly(new File(TOKEN_FILE_PATH), token);

        socketServer = new LocalSocketManager(context,
            new LocalSocketRunConfig(TITLE, SOCKET_FILE_PATH, new Client()));
        Error error = socketServer.start();
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.getErrorLogString());
            socketServer = null;
        } else {
            try {
                File sf = new File(SOCKET_FILE_PATH);
                sf.setReadable(false, false); sf.setReadable(true, true);
                sf.setWritable(false, false); sf.setWritable(true, true);
            } catch (Exception ignored) {}
        }
    }

    public static synchronized void stop() {
        if (socketServer != null) {
            socketServer.stop();
            socketServer = null;
        }
    }

    private static boolean actEnabled() {
        return new File(ACT_FLAG_PATH).exists();
    }

    private static String newToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void writeOwnerOnly(File f, String content) {
        try {
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(content.getBytes("UTF-8"));
            }
            f.setReadable(false, false); f.setReadable(true, true);
            f.setWritable(false, false); f.setWritable(true, true);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "token write failed: " + e.getMessage());
        }
    }

    private static synchronized void audit(String name, String result) {
        try (OutputStream os = new FileOutputStream(new File(AUDIT_LOG_PATH), true)) {
            String snippet = result == null ? "" : (result.length() > 200 ? result.substring(0, 200) : result);
            os.write((System.currentTimeMillis() + "\t" + name + "\t" + snippet.replace("\n", " ") + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    /** Routes a tool call to the AccessibilityService, enforcing observe/act. */
    private static String runTool(String name, JSONObject args) {
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
            default:
                out = errJson("UNKNOWN_TOOL");
        }
        audit(name, out);
        return out;
    }

    private static String errJson(String code) {
        try { return new JSONObject().put("ok", false).put("error", code).toString(); }
        catch (Exception e) { return "{\"ok\":false,\"error\":\"" + code + "\"}"; }
    }

    private static final class Client extends LocalSocketManagerClientBase {
        @Override
        protected String getLogTag() {
            return LOG_TAG;
        }

        @Override
        public void onClientAccepted(@NonNull LocalSocketManager manager,
                                     @NonNull LocalClientSocket clientSocket) {
            McpDispatch dispatch = new McpDispatch(McpSocketServer::runTool,
                t -> token != null && token.equals(t));
            try (BufferedReader reader = new BufferedReader(clientSocket.getInputStreamReader())) {
                OutputStream out = clientSocket.getOutputStream();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    String response = dispatch.handle(line);
                    if (response != null && !response.isEmpty()) {
                        out.write((response + "\n").getBytes("UTF-8"));
                        out.flush();
                    }
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "client loop: " + e.getMessage());
            } finally {
                clientSocket.closeClientSocket(false);
            }
        }
    }
}
