package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.app.mcp.a11y.AndroidControlService;
import com.termux.shared.logger.Logger;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Behavioural leak check on the REAL McpSocketServer.runTool: it must hold on
 * the base (red: the old writer copies result snippets and raw tool names) and
 * after the fix (green). It only touches APIs that exist on both.
 */
public class McpAuditLeakTest {

    static final String S_TEXT = "SENTINEL_UI_TEXT_7f3a";
    static final String S_DESC = "SENTINEL_DESC_91c2";
    static final String S_TYPE = "SENTINEL_TYPED_55d0";
    static final String S_ERR = "SENTINEL_ERR_0b8e";
    static final String S_TOOL = "SENTINEL_TOOLNAME_c41d";
    static final String S_PKG = "SENTINEL_PKG_2d6b";
    static final String S_REF = "SENTINEL_REF_a09c";
    static final String S_EXC = "SENTINEL_EXC_6e51";
    static final String[] ALL = {S_TEXT, S_DESC, S_TYPE, S_ERR, S_TOOL, S_PKG, S_REF, S_EXC};

    static final String LEGACY_MARKER = "LEGACY-LINE-DO-NOT-TOUCH";

    static File auditDir() {
        return new File(McpSocketServer.ACT_FLAG_PATH).getParentFile();
    }

    /** Empties the sandbox audit directory (it is a private /tmp tree of the run). */
    static void reset() throws Exception {
        File dir = auditDir();
        Files.createDirectories(dir.toPath());
        Files.setPosixFilePermissions(dir.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory() && !Files.isSymbolicLink(k.toPath())) {
                File[] sub = k.listFiles();
                if (sub != null) for (File x : sub) x.delete();
            }
            k.delete();
        }
    }

    static class FakeService extends AndroidControlService {
        @Override public String snapshotJson() {
            return "{\"snapshot_id\":\"snap-1\",\"ttl_ms\":8000,\"nodes\":[{\"node_ref\":\"n0\",\"role\":\"EditText\","
                + "\"text\":\"" + S_TEXT + "\",\"id\":\"" + S_TEXT + "\",\"desc\":\"" + S_DESC + "\"}]}";
        }
        @Override public String tapJson(String ref) {
            if (ref.equals("err")) return "{\"ok\":false,\"error\":\"" + S_ERR + "\"}";
            return "{\"ok\":true}";
        }
        @Override public String typeJson(String ref, String text) {
            if (ref.equals("boom")) throw new IllegalStateException(S_EXC);
            return "{\"ok\":true}";
        }
        @Override public String launchAppJson(String pkg) { return "{\"ok\":false,\"error\":\"PACKAGE_NOT_FOUND\"}"; }
    }

    static String call(String tool, JSONObject args) throws Exception {
        Method m = McpSocketServer.class.getDeclaredMethod("runTool", String.class, JSONObject.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(null, tool, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
            throw e;
        }
    }

    @Test
    public void toolContentNeverReachesAnyAuditFileLogcatOrStdout() throws Exception {
        reset();
        File dir = auditDir();
        File legacy = new File(dir, "mcp-audit.log");
        Files.write(legacy.toPath(), (LEGACY_MARKER + "\n").getBytes(StandardCharsets.UTF_8));
        long legacyMtime = legacy.lastModified();
        Files.write(new File(McpSocketServer.ACT_FLAG_PATH).toPath(), new byte[0]);
        AndroidControlService.INSTANCE = new FakeService();
        Logger.CAPTURED.clear();

        PrintStream realOut = System.out, realErr = System.err;
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(cap, true, "UTF-8");
        System.setOut(sink);
        System.setErr(sink);
        try {
            call("ui_snapshot", new JSONObject());
            call("type", new JSONObject().put("node_ref", S_REF).put("text", S_TYPE));
            call("tap", new JSONObject().put("node_ref", S_REF));
            call("tap", new JSONObject().put("node_ref", "err"));
            call("launch_app", new JSONObject().put("package", S_PKG));
            call("wake", new JSONObject());
            call(S_TOOL, new JSONObject().put("text", S_TYPE));
            call("type\t" + S_TYPE + "\n1\ttap\tOK", new JSONObject());
            call("accessibility_status", new JSONObject());
            try {
                call("type", new JSONObject().put("node_ref", "boom").put("text", S_TYPE));
                throw new AssertionError("the tool exception must still propagate");
            } catch (IllegalStateException expected) {
                assertEquals(S_EXC, expected.getMessage());
            }
            new File(McpSocketServer.ACT_FLAG_PATH).delete();
            assertEquals("{\"ok\":false,\"error\":\"POLICY_DENIED\"}", call("type", new JSONObject().put("node_ref", S_REF).put("text", S_TYPE)));
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
            AndroidControlService.INSTANCE = null;
        }

        String console = new String(cap.toByteArray(), StandardCharsets.UTF_8);
        for (String s : ALL) {
            assertFalse("stdout/stderr leaks " + s, console.contains(s));
            for (String l : Logger.CAPTURED) assertFalse("logcat leaks " + s + " in " + l, l.contains(s));
        }
        File[] files = dir.listFiles();
        for (File f : files) {
            if (!f.isFile()) continue;
            String body = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String s : ALL) assertFalse(f.getName() + " leaks " + s, body.contains(s));
        }
        // the legacy file is neither read-modified nor rotated nor removed
        assertTrue(legacy.exists());
        assertEquals(LEGACY_MARKER + "\n", new String(Files.readAllBytes(legacy.toPath()), StandardCharsets.UTF_8));
        assertEquals(legacyMtime, legacy.lastModified());
        // and the old writer's file name carries no new data: legacy is the only
        // file that may predate us, everything else is the v2 pair
        for (File f : files) {
            String n = f.getName();
            assertTrue("unexpected file " + n,
                n.equals("mcp-audit.log") || n.equals("mcp-act-enabled") || n.equals("mcp-audit-v2.log")
                    || n.equals("mcp-audit-v2.log.1"));
        }
    }
}
