package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.app.mcp.a11y.AndroidControlService;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.socket.local.LocalClientSocket;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Wiring of the content-free audit into the real McpSocketServer (post-fix only). */
public class McpAuditWiringTest {

    private static List<String> lines(File f) throws Exception {
        String body = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return new ArrayList<>(Arrays.asList(body.split("\n")));
    }

    private static String outcome(String line) {
        String[] p = line.split("\t");
        return p[1] + "/" + p[2];
    }

    @Test
    public void v2LogHoldsOnlyToolAndOutcomeLabels() throws Exception {
        McpAuditLeakTest.reset();
        Files.write(new File(McpSocketServer.ACT_FLAG_PATH).toPath(), new byte[0]);
        AndroidControlService.INSTANCE = new McpAuditLeakTest.FakeService();
        try {
            McpAuditLeakTest.call("ui_snapshot", new JSONObject());
            McpAuditLeakTest.call("type", new JSONObject().put("node_ref", "n0").put("text", McpAuditLeakTest.S_TYPE));
            McpAuditLeakTest.call("tap", new JSONObject().put("node_ref", "err"));
            McpAuditLeakTest.call("launch_app", new JSONObject().put("package", McpAuditLeakTest.S_PKG));
            McpAuditLeakTest.call(McpAuditLeakTest.S_TOOL, new JSONObject());
            McpAuditLeakTest.call("accessibility_status", new JSONObject());
            try {
                McpAuditLeakTest.call("type", new JSONObject().put("node_ref", "boom"));
            } catch (IllegalStateException expected) { }
            new File(McpSocketServer.ACT_FLAG_PATH).delete();
            McpAuditLeakTest.call("wake", new JSONObject());
        } finally {
            AndroidControlService.INSTANCE = null;
        }
        File v2 = new File(McpSocketServer.AUDIT_LOG_V2_PATH);
        assertEquals("mcp-audit-v2.log", v2.getName());
        List<String> got = lines(v2);
        assertEquals(Arrays.asList(
            "ui_snapshot/OK", "type/OK", "tap/ERROR", "launch_app/PACKAGE_NOT_FOUND",
            "unknown/UNKNOWN_TOOL", "accessibility_status/OK", "type/EXCEPTION", "wake/POLICY_DENIED"),
            outcomesOf(got));
        assertEquals("AUDIT_OK", McpSocketServer.auditStatus());
        assertTrue(new File(McpSocketServer.AUDIT_LOG_V2_PATH).toPath().toFile().exists());
        assertFalse(new File(auditDirPath(), "mcp-audit.log").exists());
    }

    private static List<String> outcomesOf(List<String> got) {
        List<String> r = new ArrayList<>();
        for (String l : got) r.add(outcome(l));
        return r;
    }

    private static File auditDirPath() { return McpAuditLeakTest.auditDir(); }

    @Test
    public void auditFailureIsAFixedCodeAndNeverChangesTheToolResult() throws Exception {
        McpAuditLeakTest.reset();
        File dir = auditDirPath();
        File victim = new File(dir.getParentFile(), "victim.txt");
        Files.write(victim.toPath(), "untouched".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(dir, McpAuditLog.FILE_NAME).toPath(), victim.toPath());
        Files.write(new File(McpSocketServer.ACT_FLAG_PATH).toPath(), new byte[0]);
        AndroidControlService.INSTANCE = new McpAuditLeakTest.FakeService();
        Logger.CAPTURED.clear();
        try {
            // observe/act behave exactly as without a working audit
            assertEquals("{\"ok\":true}", McpAuditLeakTest.call("wake", new JSONObject()));
            assertTrue(McpAuditLeakTest.call("ui_snapshot", new JSONObject()).contains(McpAuditLeakTest.S_TEXT));
            assertEquals("{\"ok\":false,\"error\":\"UNKNOWN_TOOL\"}", McpAuditLeakTest.call(McpAuditLeakTest.S_TOOL, new JSONObject()));
            new File(McpSocketServer.ACT_FLAG_PATH).delete();
            assertEquals("{\"ok\":false,\"error\":\"POLICY_DENIED\"}", McpAuditLeakTest.call("tap", new JSONObject().put("node_ref", "n0")));
        } finally {
            AndroidControlService.INSTANCE = null;
        }
        assertEquals("AUDIT_FILE_UNSAFE", McpSocketServer.auditStatus());
        assertEquals("untouched", new String(Files.readAllBytes(victim.toPath()), StandardCharsets.UTF_8));
        int mentions = 0;
        for (String l : Logger.CAPTURED) {
            assertTrue("fixed code only: " + l, l.equals("McpSocketServer: audit AUDIT_FILE_UNSAFE"));
            mentions++;
        }
        assertEquals("one line per change of state, not per event", 1, mentions);
    }

    @Test
    public void clientLoopWithMalformedAndHostileLinesLeavesNoContentInTheAudit() throws Exception {
        McpAuthFixtures.reset();
        // the real client of a real server epoch: the session opens with its token
        LocalSocketManagerClientBase serverClient = McpAuthFixtures.start();
        String token = McpAuthFixtures.readToken();
        Files.write(new File(McpSocketServer.ACT_FLAG_PATH).toPath(), new byte[0]);
        AndroidControlService.INSTANCE = new McpAuditLeakTest.FakeService();
        String s = McpAuditLeakTest.S_TOOL;
        String input =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"auth\":\"" + token + "\"}}\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ui_snapshot\"}}\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"type\",\"arguments\":{\"node_ref\":\"n0\",\"text\":\"" + McpAuditLeakTest.S_TYPE + "\"}}}\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"" + s + "\"}}\n"
            + "{ this is not json " + McpAuditLeakTest.S_EXC + "\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"type\",\"arguments\":{\"node_ref\":\"boom\"}}}\n";
        Logger.CAPTURED.clear();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            serverClient.onClientAccepted(null, new LocalClientSocket(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out));
            String resp = new String(out.toByteArray(), StandardCharsets.UTF_8);
            assertTrue("session worked", resp.contains("android-control") && resp.contains("snap-1"));
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
        }
        List<String> got = lines(new File(McpSocketServer.AUDIT_LOG_V2_PATH));
        assertEquals(Arrays.asList("ui_snapshot/OK", "type/OK", "unknown/UNKNOWN_TOOL", "type/EXCEPTION"), outcomesOf(got));
        for (File f : auditDirPath().listFiles()) {
            if (!f.isFile()) continue;
            String body = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String sent : McpAuditLeakTest.ALL) assertFalse(f.getName() + " leaks " + sent, body.contains(sent));
        }
        for (String l : Logger.CAPTURED) {
            assertFalse("not an audit payload dump", l.contains("audit") && l.length() > 60);
        }
    }
}
