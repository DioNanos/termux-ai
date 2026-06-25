package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.app.mcp.a11y.UiSnapshot;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class McpDispatchTest {

    private McpDispatch dispatcher(final String[] lastCall) {
        McpDispatch.ToolHandler h = (name, args) -> {
            lastCall[0] = name;
            return "{\"ok\":true}";
        };
        McpDispatch.AuthCheck a = token -> "secret".equals(token);
        return new McpDispatch(h, a);
    }

    @Test
    public void initializeRejectsBadToken() {
        McpDispatch d = dispatcher(new String[1]);
        JSONObject r = parse(d.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"auth\":\"wrong\"}}"));
        assertEquals(-32001, r.getJSONObject("error").getInt("code"));
        assertFalse(d.isAuthed());
    }

    @Test
    public void initializeAcceptsGoodToken() {
        McpDispatch d = dispatcher(new String[1]);
        JSONObject r = parse(d.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"auth\":\"secret\"}}"));
        assertEquals("android-control", r.getJSONObject("result").getJSONObject("serverInfo").getString("name"));
        assertTrue(d.isAuthed());
    }

    @Test
    public void toolsListRequiresAuth() {
        McpDispatch d = dispatcher(new String[1]);
        JSONObject r = parse(d.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));
        assertEquals(-32002, r.getJSONObject("error").getInt("code"));
    }

    @Test
    public void toolsListReturnsFourTools() {
        McpDispatch d = dispatcher(new String[1]);
        d.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"auth\":\"secret\"}}");
        JSONObject r = parse(d.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));
        assertEquals(4, r.getJSONObject("result").getJSONArray("tools").length());
    }

    @Test
    public void toolsCallDelegatesToHandler() {
        String[] last = new String[1];
        McpDispatch d = dispatcher(last);
        d.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"auth\":\"secret\"}}");
        JSONObject r = parse(d.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"tap\",\"arguments\":{\"node_ref\":\"n1\"}}}"));
        assertEquals("tap", last[0]);
        assertFalse(r.getJSONObject("result").getBoolean("isError"));
    }

    @Test
    public void snapshotSerializesNodes() {
        UiSnapshot.UiNode n = new UiSnapshot.UiNode("n1", "Button", "OK", "ok_btn", null, "[0,0][10,10]", true, false, false);
        UiSnapshot s = new UiSnapshot("snap-1", 5000L, List.of(n));
        String j = s.toJson();
        assertTrue(j.contains("\"snapshot_id\":\"snap-1\""));
        assertTrue(j.contains("\"node_ref\":\"n1\""));
        assertTrue(j.contains("\"clickable\":true"));
    }

    private static JSONObject parse(String s) {
        return new JSONObject(s);
    }
}
