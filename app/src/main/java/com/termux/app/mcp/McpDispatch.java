package com.termux.app.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Minimal MCP-compatible JSON-RPC 2.0 dispatcher for the {@code android-control}
 * module. One instance per client connection: it holds the per-connection
 * {@code authed} flag set by a successful {@code initialize}.
 *
 * <p>Newline-delimited JSON-RPC: one request line in, one response line out.
 * Methods: {@code initialize}, {@code tools/list}, {@code tools/call}.
 */
public final class McpDispatch {

    public interface ToolHandler {
        /** @return a JSON string with the tool result payload. */
        String call(String name, JSONObject args) throws Exception;
    }

    public interface AuthCheck {
        boolean ok(String token);
    }

    public static final String SERVER_NAME = "android-control";
    public static final String SERVER_VERSION = "0.1.0";

    private final ToolHandler handler;
    private final AuthCheck auth;
    private boolean authed = false;

    public McpDispatch(ToolHandler handler, AuthCheck auth) {
        this.handler = handler;
        this.auth = auth;
    }

    public boolean isAuthed() {
        return authed;
    }

    /** Process one JSON-RPC line and return one JSON-RPC response line. */
    public String handle(String line) {
        Object id = JSONObject.NULL;
        try {
            JSONObject req = new JSONObject(line);
            id = req.opt("id");
            if (id == null) id = JSONObject.NULL;
            String method = req.optString("method", "");
            JSONObject params = req.optJSONObject("params");
            if (params == null) params = new JSONObject();

            switch (method) {
                case "initialize":
                    return initialize(id, params);
                case "notifications/initialized":
                    // notification, no response expected; return empty ack
                    return "";
                case "tools/list":
                    if (!authed) return err(id, -32002, "not authenticated");
                    return toolsList(id);
                case "tools/call":
                    if (!authed) return err(id, -32002, "not authenticated");
                    return toolsCall(id, params);
                default:
                    return err(id, -32601, "method not found: " + method);
            }
        } catch (Exception e) {
            return err(id, -32700, "parse error: " + e.getMessage());
        }
    }

    private String initialize(Object id, JSONObject params) {
        String token = params.optString("auth", "");
        if (auth != null && !auth.ok(token)) {
            return err(id, -32001, "authentication failed");
        }
        authed = true;
        try {
            JSONObject caps = new JSONObject().put("tools", new JSONObject());
            JSONObject info = new JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION);
            JSONObject result = new JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", caps)
                .put("serverInfo", info);
            return ok(id, result);
        } catch (Exception e) {
            return err(id, -32603, "internal: " + e.getMessage());
        }
    }

    private String toolsList(Object id) {
        try {
            JSONArray tools = new JSONArray();
            tools.put(tool("accessibility_status",
                "Report whether the android-control accessibility service is enabled.",
                new JSONObject().put("type", "object").put("properties", new JSONObject())));
            tools.put(tool("ui_snapshot",
                "Return the interactable accessibility tree as nodes with stable refs (TTL-bound).",
                new JSONObject().put("type", "object").put("properties", new JSONObject())));
            tools.put(tool("tap",
                "Tap the element identified by node_ref from the latest ui_snapshot.",
                schemaOneString("node_ref")));
            tools.put(tool("type",
                "Set text on the editable element identified by node_ref.",
                schemaTypeArgs()));
            JSONObject result = new JSONObject().put("tools", tools);
            return ok(id, result);
        } catch (Exception e) {
            return err(id, -32603, "internal: " + e.getMessage());
        }
    }

    private String toolsCall(Object id, JSONObject params) {
        try {
            String name = params.optString("name", "");
            JSONObject args = params.optJSONObject("arguments");
            if (args == null) args = new JSONObject();
            String payload = handler.call(name, args);
            JSONObject content = new JSONObject().put("type", "text").put("text", payload);
            JSONObject result = new JSONObject()
                .put("content", new JSONArray().put(content))
                .put("isError", false);
            return ok(id, result);
        } catch (Exception e) {
            return err(id, -32000, "tool error: " + e.getMessage());
        }
    }

    private static JSONObject tool(String name, String desc, JSONObject schema) throws Exception {
        return new JSONObject().put("name", name).put("description", desc).put("inputSchema", schema);
    }

    private static JSONObject schemaOneString(String prop) throws Exception {
        JSONObject props = new JSONObject().put(prop, new JSONObject().put("type", "string"));
        return new JSONObject().put("type", "object").put("properties", props)
            .put("required", new JSONArray().put(prop));
    }

    private static JSONObject schemaTypeArgs() throws Exception {
        JSONObject props = new JSONObject()
            .put("node_ref", new JSONObject().put("type", "string"))
            .put("text", new JSONObject().put("type", "string"));
        return new JSONObject().put("type", "object").put("properties", props)
            .put("required", new JSONArray().put("node_ref").put("text"));
    }

    private static String ok(Object id, JSONObject result) {
        try {
            return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString();
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"encode\"}}";
        }
    }

    private static String err(Object id, int code, String message) {
        try {
            JSONObject e = new JSONObject().put("code", code).put("message", message);
            return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", e).toString();
        } catch (Exception ex) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"encode\"}}";
        }
    }
}
