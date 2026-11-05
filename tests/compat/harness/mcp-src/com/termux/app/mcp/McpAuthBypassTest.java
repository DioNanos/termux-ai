package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.app.mcp.a11y.AndroidControlService;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;

import org.junit.Test;

import java.util.List;

/**
 * The bypass: a connection that never presented the server token must not get a
 * session. These tests drive the REAL McpSocketServer (start -> the client the
 * platform manager would call -> McpDispatch -> runTool) and use only API that
 * exists on the base, so they are RED on the base writer ({@code t -> true}) and
 * GREEN with the verified token.
 */
public class McpAuthBypassTest {

    private static void assertRefusedSession(List<String> r, McpAuthFixtures.CountingService svc, String what) {
        assertEquals(what + ": one response per request", 3, r.size());
        assertTrue(what + ": initialize refused", McpAuthFixtures.isError(r.get(0), -32001));
        assertTrue(what + ": tools/list denied", McpAuthFixtures.isError(r.get(1), -32002));
        assertTrue(what + ": tools/call denied", McpAuthFixtures.isError(r.get(2), -32002));
        assertEquals(what + ": no tool body ran", 0, svc.snapshots.get());
    }

    private static List<String> refused(String paramsJson, McpAuthFixtures.CountingService svc) throws Exception {
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        AndroidControlService.INSTANCE = svc;
        try {
            return McpAuthFixtures.session(client,
                McpAuthFixtures.initialize(1, paramsJson),
                McpAuthFixtures.toolsList(2),
                McpAuthFixtures.callSnapshot(3));
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
        }
    }

    @Test
    public void connectionWithoutAnyTokenIsRefused() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        assertRefusedSession(refused("{}", svc), svc, "no params.auth");
    }

    @Test
    public void connectionWithNoParamsAtAllIsRefused() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        AndroidControlService.INSTANCE = svc;
        try {
            List<String> r = McpAuthFixtures.session(client,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}",
                McpAuthFixtures.toolsList(2),
                McpAuthFixtures.callSnapshot(3));
            assertRefusedSession(r, svc, "no params");
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
        }
    }

    @Test
    public void connectionWithAWrongTokenIsRefused() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        assertRefusedSession(refused("{\"auth\":\"" + McpAuthFixtures.ZERO_TOKEN + "\"}", svc), svc, "wrong token");
    }

    @Test
    public void connectionWithAnEmptyTokenIsRefused() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        assertRefusedSession(refused("{\"auth\":\"\"}", svc), svc, "empty token");
    }

    @Test
    public void connectionWithAWrongTypeTokenIsRefused() throws Exception {
        for (String bad : new String[] {"12345", "null", "true", "{\"x\":1}", "[\"a\"]"}) {
            McpAuthFixtures.reset();
            McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
            assertRefusedSession(refused("{\"auth\":" + bad + "}", svc), svc, "auth of type " + bad);
        }
    }

    @Test
    public void theLiteralTokenFromTheFileOpensASession() throws Exception {
        // control: the legitimate path still works (a shim presenting the published token)
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        LocalSocketManagerClientBase client = McpAuthFixtures.start();
        String token = McpAuthFixtures.readToken();
        assertTrue("a token is published", token != null && !token.isEmpty());
        AndroidControlService.INSTANCE = svc;
        try {
            List<String> r = McpAuthFixtures.session(client,
                McpAuthFixtures.initializeWithToken(1, token),
                McpAuthFixtures.callSnapshot(2));
            assertEquals(2, r.size());
            assertTrue("good token opens the session", McpAuthFixtures.isResult(r.get(0)));
            assertTrue("tool ran after a good initialize", McpAuthFixtures.isResult(r.get(1)));
            assertEquals(1, svc.snapshots.get());
            assertFalse("the token never comes back to the client", r.get(0).contains(token) || r.get(1).contains(token));
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
        }
    }
}
