package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-connection auth, re-initialize, epoch end and fixed diagnostics of the REAL
 * McpDispatch against a REAL McpAuth epoch. No token ever appears in a message.
 */
public class McpDispatchAuthTest {

    private static final String S_METHOD = "SENTINEL_METHOD_3b71";
    private static final String S_PARSE = "SENTINEL_PARSE_88c0";
    private static final String S_TOOLERR = "SENTINEL_TOOLERR_d2e4";

    private static final class Epoch {
        final McpAuth auth;
        final String token;
        Epoch() throws Exception {
            File dir = new File(Files.createTempDirectory("mcpdisp").toFile(), "termux-ai");
            auth = McpAuth.publish(dir, McpAuth.TOKEN_FILE_NAME);
            assertTrue("epoch published", auth.available());
            token = new String(Files.readAllBytes(new File(dir, McpAuth.TOKEN_FILE_NAME).toPath()), StandardCharsets.UTF_8);
        }
    }

    private static McpDispatch dispatch(Epoch e, AtomicInteger calls) {
        return new McpDispatch((name, args) -> {
            calls.incrementAndGet();
            if (name.equals("boom")) throw new IllegalStateException(S_TOOLERR + " " + e.token);
            return "{\"ok\":true}";
        }, e.auth);
    }

    private static String init(int id, String auth) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\",\"params\":{\"auth\":" + auth + "}}";
    }

    private static String q(String s) {
        return "\"" + s + "\"";
    }

    private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}";

    private static String call(String name) {
        return "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":{}}}";
    }

    private static int code(String response) throws Exception {
        return new JSONObject(response).getJSONObject("error").getInt("code");
    }

    private static String message(String response) throws Exception {
        return new JSONObject(response).getJSONObject("error").getString("message");
    }

    @Test
    public void nothingIsServedBeforeAGoodInitialize() throws Exception {
        Epoch e = new Epoch();
        AtomicInteger calls = new AtomicInteger();
        McpDispatch d = dispatch(e, calls);
        assertEquals(-32002, code(d.handle(LIST)));
        assertEquals(-32002, code(d.handle(call("ui_snapshot"))));
        assertFalse(d.isAuthed());
        assertEquals("no tool body ran", 0, calls.get());
        // the notification is accepted without a session and answers nothing
        assertEquals("", d.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertEquals(-32002, code(d.handle(LIST)));
    }

    @Test
    public void everyBadAuthFormIsRefusedAndLeavesTheConnectionClosed() throws Exception {
        Epoch e = new Epoch();
        String wrong = "0".repeat(64);
        String[] bad = {
            "\"\"", "null", "12345", "true", "false", "{}", "[]", "[\"x\"]", "{\"auth\":1}", q(wrong), q(e.token.substring(1)),
            q(e.token + "0"), q(e.token.toUpperCase().equals(e.token) ? wrong : e.token.toUpperCase()),
        };
        for (String b : bad) {
            AtomicInteger calls = new AtomicInteger();
            McpDispatch d = dispatch(e, calls);
            String r = d.handle(init(1, b));
            assertEquals("refused: " + (b.length() > 20 ? "long form" : b), -32001, code(r));
            assertFalse(d.isAuthed());
            assertEquals(-32002, code(d.handle(call("ui_snapshot"))));
            assertEquals(0, calls.get());
        }
        // no auth key at all, and params of the wrong type
        for (String line : new String[] {
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":\"x\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":[]}"}) {
            McpDispatch d = dispatch(e, new AtomicInteger());
            assertEquals(-32001, code(d.handle(line)));
            assertFalse(d.isAuthed());
        }
    }

    @Test
    public void theTokenOfTheEpochOpensTheSessionAndOnlyOnThatConnection() throws Exception {
        Epoch e = new Epoch();
        AtomicInteger calls = new AtomicInteger();
        McpDispatch a = dispatch(e, calls);
        McpDispatch b = dispatch(e, calls);
        String r = a.handle(init(1, q(e.token)));
        assertTrue(r.contains("\"result\"") && !r.contains(e.token));
        assertTrue(a.isAuthed());
        assertTrue(a.handle(call("x")).contains("\"result\""));
        assertEquals(1, calls.get());
        // a separate connection of the same epoch has no session of its own
        assertFalse(b.isAuthed());
        assertEquals(-32002, code(b.handle(LIST)));
        assertEquals(-32002, code(b.handle(call("x"))));
        assertEquals(1, calls.get());
    }

    @Test
    public void aFailedReinitializeClosesAnEarlierSession() throws Exception {
        Epoch e = new Epoch();
        AtomicInteger calls = new AtomicInteger();
        McpDispatch d = dispatch(e, calls);
        assertTrue(d.handle(init(1, q(e.token))).contains("\"result\""));
        assertTrue(d.isAuthed());
        assertEquals(-32001, code(d.handle(init(2, q("1".repeat(64))))));
        assertFalse("a failed initialize clears authed", d.isAuthed());
        assertEquals(-32002, code(d.handle(LIST)));
        assertEquals(-32002, code(d.handle(call("x"))));
        assertEquals(0, calls.get());
        // and the good token can open it again
        assertTrue(d.handle(init(3, q(e.token))).contains("\"result\""));
        assertTrue(d.isAuthed());
    }

    @Test
    public void aWronglyTypedReinitializeAlsoClosesTheSession() throws Exception {
        Epoch e = new Epoch();
        McpDispatch d = dispatch(e, new AtomicInteger());
        assertTrue(d.handle(init(1, q(e.token))).contains("\"result\""));
        assertEquals(-32001, code(d.handle(init(2, "42"))));
        assertFalse(d.isAuthed());
    }

    @Test
    public void whenTheEpochEndsTheConnectionStopsBeingAuthenticated() throws Exception {
        Epoch e = new Epoch();
        AtomicInteger calls = new AtomicInteger();
        McpDispatch d = dispatch(e, calls);
        assertTrue(d.handle(init(1, q(e.token))).contains("\"result\""));
        assertTrue(d.handle(call("x")).contains("\"result\""));
        e.auth.retire();
        assertFalse("authed is false once the epoch ended", d.isAuthed());
        assertEquals(-32002, code(d.handle(LIST)));
        assertEquals(-32002, code(d.handle(call("x"))));
        assertEquals("no tool body ran after the end", 1, calls.get());
        // the dead epoch's own token cannot reopen it
        assertEquals(-32001, code(d.handle(init(2, q(e.token)))));
        assertFalse(d.isAuthed());
    }

    @Test
    public void aNewEpochRefusesTheTokenOfThePreviousOne() throws Exception {
        Epoch old = new Epoch();
        Epoch fresh = new Epoch();
        assertFalse(old.token.equals(fresh.token));
        McpDispatch d = dispatch(fresh, new AtomicInteger());
        assertEquals(-32001, code(d.handle(init(1, q(old.token)))));
        assertTrue(d.handle(init(2, q(fresh.token))).contains("\"result\""));
    }

    @Test
    public void diagnosticsAreFixedTextsAndEchoNothing() throws Exception {
        Epoch e = new Epoch();
        AtomicInteger calls = new AtomicInteger();
        McpDispatch d = dispatch(e, calls);

        String parse = d.handle("{ not json " + S_PARSE + " " + e.token);
        assertEquals(-32700, code(parse));
        assertEquals("parse error", message(parse));
        assertFalse(parse.contains(S_PARSE) || parse.contains(e.token));

        String notObject = d.handle("[" + q(S_PARSE) + "]");
        assertEquals("parse error", message(notObject));
        assertFalse(notObject.contains(S_PARSE));

        String unknown = d.handle("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"" + S_METHOD + "\",\"params\":{\"x\":\"" + e.token + "\"}}");
        assertEquals(-32601, code(unknown));
        assertEquals("method not found", message(unknown));
        assertFalse(unknown.contains(S_METHOD) || unknown.contains(e.token));

        String numericMethod = d.handle("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":12345}");
        assertEquals("method not found", message(numericMethod));

        assertTrue(d.handle(init(6, q(e.token))).contains("\"result\""));
        String tool = d.handle(call("boom"));
        assertEquals(-32000, code(tool));
        assertEquals("tool error", message(tool));
        assertFalse(tool.contains(S_TOOLERR) || tool.contains(e.token));

        String denied = new McpDispatch((n, a) -> "{}", e.auth).handle(call(S_METHOD));
        assertEquals("not authenticated", message(denied));
        assertFalse(denied.contains(S_METHOD));

        String refused = d.handle(init(7, q(S_PARSE)));
        assertEquals("authentication failed", message(refused));
        assertFalse(refused.contains(S_PARSE));
    }

    @Test
    public void noDiagnosticInTheServerSourcesConcatenatesAnExceptionMessage() throws Exception {
        String dir = System.getProperty("mcptest.mcpsrc");
        for (String f : new String[] {"McpDispatch.java", "McpSocketServer.java", "McpAuth.java", "McpStdioShim.java"}) {
            File file = new File(dir, f);
            assertTrue(f + " is readable", file.isFile());
            String src = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertFalse(f + " must not use getMessage()", src.contains("getMessage()"));
            assertFalse(f + " must not print stack traces", src.contains("printStackTrace"));
        }
    }

    @Test
    public void authIsReadAsAStringNeverCoerced() throws Exception {
        File file = new File(System.getProperty("mcptest.mcpsrc"), "McpDispatch.java");
        String src = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int at = src.indexOf("private String initialize(");
        assertTrue(at >= 0);
        String body = src.substring(at, src.indexOf("\n    }\n", at));
        assertFalse("initialize must not use optString on auth", body.contains("optString"));
        assertTrue("initialize requires a JSON string", body.contains("instanceof String"));
    }
}
