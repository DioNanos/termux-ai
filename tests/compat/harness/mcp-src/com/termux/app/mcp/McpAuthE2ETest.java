package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.termux.app.mcp.a11y.AndroidControlService;
import com.termux.shared.net.socket.local.LocalSocketManager;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * End to end on a JDK: thin wrapper (sh, with a fake pm / app_process pair) -> the real
 * McpStdioShim main -> a REAL unix socket -> the real McpSocketServer client loop.
 * Plus the direct-socket negative and the OLD socat shim, which must fail.
 *
 * Not an Android proof: ART's app_process, the base APK from the real pm, the platform
 * LocalSocket and R8 belong to the device CI.
 */
public class McpAuthE2ETest {

    private static final String WORK = System.getProperty("mcptest.work");
    private static final String ASSETS = System.getProperty("mcptest.assets");
    private static final String SHIM_CLASSES = System.getProperty("mcptest.shimclasses");
    private static final String OLD_SHIM = System.getProperty("mcptest.oldshim");
    private static final String REAL_DIR = System.getProperty("mcptest.realdir");
    private static final String SENTINEL_ENV = "SENTINEL_LD_9e41";

    // ---- helpers ----

    private static File e2eDir() throws IOException {
        File d = new File(WORK, "e2e");
        d.mkdirs();
        return d;
    }

    private static File script(String name, String body) throws IOException {
        File f = new File(e2eDir(), name);
        Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rwx------"));
        return f;
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static String javaBin() {
        return System.getProperty("java.home") + "/bin/java";
    }

    private static final String APK = "/data/app/~~sandbox==/com.termux-1/base.apk";

    /** pm prints a base APK and a split; app_process records what it got, then runs the shim on the JDK. */
    private static int pmCount;

    private static File fakePm(String output, int rc) throws IOException {
        return script("pm" + (pmCount++), "#!/bin/sh\n"
            + "env > '" + e2eDir() + "/pm.env'\n"
            + "printf '%s\\n' \"$@\" > '" + e2eDir() + "/pm.args'\n"
            + "cat <<'EOT'\n" + output + "EOT\n"
            + "exit " + rc + "\n");
    }

    private static File fakeAppProcess() throws IOException {
        return script("app_process", "#!/bin/sh\n"
            + "env > '" + e2eDir() + "/ap.env'\n"
            + "printf '%s\\n' \"$@\" > '" + e2eDir() + "/ap.args'\n"
            + "printf '%s' \"$CLASSPATH\" > '" + e2eDir() + "/ap.cp'\n"
            + "exec '" + javaBin() + "' -cp '" + SHIM_CLASSES + File.pathSeparator + System.getProperty("java.class.path")
            + "' \"$2\"\n");
    }

    private static File wrapper(File pm, File appProcess) throws IOException {
        String src = read(ASSETS + "/termux-ai-mcp");
        String out = src.replace("PM=\"/system/bin/pm\"", "PM=\"" + pm + "\"")
            .replace("APP_PROCESS=\"/system/bin/app_process\"", "APP_PROCESS=\"" + appProcess + "\"");
        assertTrue("both runtime paths were substituted", !out.equals(src) && out.contains(pm.getPath()) && out.contains(appProcess.getPath()));
        return script("wrapper.sh", out);
    }

    private static File goodWrapper() throws IOException {
        return wrapper(fakePm("package:" + APK + "\npackage:/data/app/~~sandbox==/com.termux-1/split_config.arm64_v8a.apk\n", 0), fakeAppProcess());
    }

    /** A child process with its output read on threads, killed in {@link #close()}. */
    static final class Child implements AutoCloseable {
        final Process p;
        final BlockingQueue<String> out = new LinkedBlockingQueue<>();
        final StringBuilder err = new StringBuilder();
        final List<String> allOut = new ArrayList<>();
        final OutputStream in;

        Child(List<String> cmd, File dir, String... extraEnv) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();
            pb.environment().put("PATH", "/usr/bin:/bin");
            pb.environment().put("HOME", WORK + "/e2e/home");
            for (int i = 0; i + 1 < extraEnv.length; i += 2) pb.environment().put(extraEnv[i], extraEnv[i + 1]);
            pb.directory(dir);
            p = pb.start();
            in = p.getOutputStream();
            Thread o = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) { synchronized (allOut) { allOut.add(l); } out.add(l); }
                } catch (IOException ignored) { }
            });
            Thread e = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) synchronized (err) { err.append(l).append('\n'); }
                } catch (IOException ignored) { }
            });
            o.setDaemon(true);
            e.setDaemon(true);
            o.start();
            e.start();
        }

        void send(String line) throws IOException {
            in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        }

        /** For children that may already be gone: a broken pipe is not a test failure. */
        void trySend(String line) {
            try { send(line); } catch (IOException ignored) { }
        }

        String recv() throws InterruptedException {
            String l = out.poll(10, TimeUnit.SECONDS);
            if (l == null) throw new AssertionError("no answer within 10s; child alive=" + p.isAlive() + " stderr=" + stderr());
            return l;
        }

        String request(String line) throws Exception {
            send(line);
            return recv();
        }

        int finish() throws Exception {
            try { in.close(); } catch (IOException ignored) { }
            assertTrue("child exited", p.waitFor(15, TimeUnit.SECONDS));
            Thread.sleep(100);
            return p.exitValue();
        }

        String stderr() { synchronized (err) { return err.toString(); } }
        List<String> stdoutLines() { synchronized (allOut) { return new ArrayList<>(allOut); } }

        @Override public void close() {
            if (p.isAlive()) p.destroyForcibly();
        }
    }

    private static List<String> cmd(File script) {
        List<String> c = new ArrayList<>();
        c.add("sh");
        c.add(script.getPath());
        return c;
    }

    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"e2e\",\"version\":\"1\"}}}";

    // ---- tests ----

    @Test
    public void aDirectSocketClientWithoutTheTokenGetsNoSessionAndNoTool() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        AndroidControlService.INSTANCE = svc;
        try {
            McpAuthFixtures.startServing();
            try (McpAuthFixtures.RawClient c = new McpAuthFixtures.RawClient(McpSocketServer.SOCKET_FILE_PATH)) {
                assertTrue(McpAuthFixtures.isError(c.request(INIT), -32001));
                assertTrue(McpAuthFixtures.isError(c.request(McpAuthFixtures.toolsList(2)), -32002));
                assertTrue(McpAuthFixtures.isError(c.request(McpAuthFixtures.callSnapshot(3)), -32002));
                assertTrue(McpAuthFixtures.isError(c.request(McpAuthFixtures.initializeWithToken(4, McpAuthFixtures.ZERO_TOKEN)), -32001));
                assertTrue(McpAuthFixtures.isError(c.request(McpAuthFixtures.callSnapshot(5)), -32002));
            }
            assertEquals("no tool ran for a direct client", 0, svc.snapshots.get());
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
            LocalSocketManager.SERVE = false;
        }
    }

    @Test
    public void theLegitimateShimThroughTheWrapperOpensASessionAndRunsATool() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        AndroidControlService.INSTANCE = svc;
        try {
            McpAuthFixtures.startServing();
            String token = McpAuthFixtures.readToken();
            File w = goodWrapper();
            try (Child c = new Child(cmd(w), e2eDir(), "LD_LIBRARY_PATH", "/nonexistent/" + SENTINEL_ENV)) {
                String init = c.request(INIT);
                assertNotNull("initialize answered (stderr: " + c.stderr() + ")", init);
                assertTrue("session opened through the shim", init.contains("\"result\"") && init.contains("android-control"));
                c.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                String list = c.request(McpAuthFixtures.toolsList(2));
                assertTrue(list != null && list.contains("ui_snapshot") && list.contains("\"tools\""));
                String call = c.request(McpAuthFixtures.callSnapshot(3));
                assertTrue("tool ran", call != null && call.contains("snap-1"));
                assertEquals(1, svc.snapshots.get());

                // the token is on no command line and in no environment of the running shim
                String cmdline = new String(Files.readAllBytes(new File("/proc/" + c.p.pid() + "/cmdline").toPath()), StandardCharsets.ISO_8859_1);
                String environ = new String(Files.readAllBytes(new File("/proc/" + c.p.pid() + "/environ").toPath()), StandardCharsets.ISO_8859_1);
                assertFalse("token not in argv", cmdline.contains(token));
                assertFalse("token not in the environment", environ.contains(token));

                assertEquals(0, c.finish());
                assertEquals("stdout is exactly the three responses", 3, c.stdoutLines().size());
                for (String l : c.stdoutLines()) assertFalse("token never reaches the client", l.contains(token));
                assertEquals("no stderr noise on success", "", c.stderr());
            }
            // what the wrapper handed to app_process
            assertEquals("/system/bin\ncom.termux.app.mcp.McpStdioShim\n", read(e2eDir() + "/ap.args"));
            assertEquals("the base apk, not the split", APK, read(e2eDir() + "/ap.cp"));
            assertFalse("the token was never in the app_process environment", read(e2eDir() + "/ap.env").contains(token));
            assertFalse("the token was never in the app_process argv", read(e2eDir() + "/ap.args").contains(token));
            assertFalse("system tools do not inherit the Termux libraries", read(e2eDir() + "/ap.env").contains(SENTINEL_ENV));
            assertFalse(read(e2eDir() + "/pm.env").contains(SENTINEL_ENV));
            assertEquals("path\ncom.termux\n", read(e2eDir() + "/pm.args"));
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
            LocalSocketManager.SERVE = false;
        }
    }

    @Test
    public void aRestartWithAChangedTokenEndsTheOldShimAndANewShimWorks() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        AndroidControlService.INSTANCE = svc;
        try {
            McpAuthFixtures.startServing();
            String t1 = McpAuthFixtures.readToken();
            File w = goodWrapper();
            Child old = new Child(cmd(w), e2eDir());
            try {
                assertTrue(old.request(INIT).contains("\"result\""));
                assertTrue(old.request(McpAuthFixtures.callSnapshot(2)).contains("snap-1"));

                McpAuthFixtures.startServing(); // restart: new token
                String t2 = McpAuthFixtures.readToken();
                assertFalse(t1.equals(t2));

                // the old shim's server went away: it ends with a fixed error, it does not hang or reconnect
                assertTrue("old shim exited", old.p.waitFor(15, TimeUnit.SECONDS));
                assertEquals(McpStdioShim.EXIT_IO, old.p.exitValue());
                Thread.sleep(100);
                assertTrue(old.stderr().contains("android-control: E_IO"));
                assertEquals("the old connection ran nothing more", 1, svc.snapshots.get());
            } finally {
                old.close();
            }
            try (Child fresh = new Child(cmd(w), e2eDir())) {
                assertTrue(fresh.request(INIT).contains("\"result\""));
                assertTrue(fresh.request(McpAuthFixtures.callSnapshot(2)).contains("snap-1"));
                assertEquals(0, fresh.finish());
            }
            assertEquals(2, svc.snapshots.get());
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
            LocalSocketManager.SERVE = false;
        }
    }

    @Test
    public void theOldSocatShimIsRefusedAndRunsNothing() throws Exception {
        McpAuthFixtures.reset();
        McpAuthFixtures.CountingService svc = new McpAuthFixtures.CountingService();
        AndroidControlService.INSTANCE = svc;
        try {
            McpAuthFixtures.startServing();
            String old = read(OLD_SHIM);
            String needle = "SOCK=\"/data/data/com.termux/files/apps/com.termux/termux-ai/mcp.sock\"";
            assertTrue("the fixture is the old shim", old.contains(needle) && old.contains("socat - \"UNIX-CONNECT:$SOCK\""));
            File oldScript = script("old-shim.sh", old.replace(needle, "SOCK=\"" + McpSocketServer.SOCKET_FILE_PATH + "\""));
            try (Child c = new Child(cmd(oldScript), e2eDir())) {
                // what an MCP client does through the old shim: no token anywhere
                assertTrue(McpAuthFixtures.isError(c.request(INIT), -32001));
                assertTrue(McpAuthFixtures.isError(c.request(McpAuthFixtures.toolsList(2)), -32002));
                assertTrue(McpAuthFixtures.isError(c.request(McpAuthFixtures.callSnapshot(3)), -32002));
                c.finish();
            }
            assertEquals("the old shim executed no tool: no silent compatibility", 0, svc.snapshots.get());
        } finally {
            AndroidControlService.INSTANCE = null;
            McpSocketServer.stop();
            LocalSocketManager.SERVE = false;
        }
    }

    @Test
    public void withoutATokenOrAServerTheShimSaysSoLocallyAndExits() throws Exception {
        McpAuthFixtures.reset();
        File w = goodWrapper();

        // no token file at all
        try (Child c = new Child(cmd(w), e2eDir())) {
            c.trySend(INIT);
            assertEquals(McpStdioShim.EXIT_NO_TOKEN, c.finish());
            assertEquals("android-control: E_NO_TOKEN\n", c.stderr());
            assertEquals("nothing on stdout", 0, c.stdoutLines().size());
        }

        // a well-formed token but nobody listening
        String bogus = "ab".repeat(32);
        Files.write(new File(McpSocketServer.TOKEN_FILE_PATH).toPath(), bogus.getBytes(StandardCharsets.UTF_8));
        try (Child c = new Child(cmd(w), e2eDir())) {
            c.trySend(INIT);
            assertEquals(McpStdioShim.EXIT_NO_SERVER, c.finish());
            assertEquals("android-control: E_NO_SERVER\n", c.stderr());
            assertEquals(0, c.stdoutLines().size());
            assertFalse(c.stderr().contains(bogus));
        }
    }

    @Test
    public void theWrapperFailsWithFixedLinesAndNeverPrintsOnStdout() throws Exception {
        File ap = fakeAppProcess();
        String apkLine = "package:" + APK + "\n";
        Object[][] cases = {
            {fakePm("", 0), ap, "E_NO_APK"},
            {fakePm("package:/data/app/x/split_config.xxhdpi.apk\n", 0), ap, "E_NO_APK"},
            {fakePm(apkLine, 1), ap, "E_NO_APK"},
            {fakePm("package:/data/app/a:b/base.apk\n", 0), ap, "E_NO_APK"},
            {fakePm("package:/data/app/a b/base.apk\n", 0), ap, "E_NO_APK"},
            {fakePm("package:/data/app/a\tb/base.apk\n", 0), ap, "E_NO_APK"},
            {new File(e2eDir(), "missing-pm"), ap, "E_NO_RUNTIME"},
            {fakePm(apkLine, 0), new File(e2eDir(), "missing-app-process"), "E_NO_RUNTIME"},
        };
        for (Object[] k : cases) {
            File w = wrapper((File) k[0], (File) k[1]);
            try (Child c = new Child(cmd(w), e2eDir())) {
                assertEquals(1, c.finish());
                assertEquals("android-control: " + k[2] + "\n", c.stderr());
                assertEquals("nothing on stdout", 0, c.stdoutLines().size());
            }
        }
    }

    @Test
    public void shimAndServerAgreeOnNamesLengthsAndTheRealDirectory() throws Exception {
        assertEquals(McpAuth.TOKEN_CHARS, McpStdioShim.TOKEN_CHARS);
        assertEquals(McpAuth.TOKEN_FILE_NAME, McpStdioShim.TOKEN_NAME);
        assertTrue(McpSocketServer.TOKEN_FILE_PATH.endsWith("/termux-ai/" + McpStdioShim.TOKEN_NAME));
        assertTrue(McpSocketServer.SOCKET_FILE_PATH.endsWith("/termux-ai/" + McpStdioShim.SOCKET_NAME));
        assertNotNull("the harness evaluated the real TermuxConstants", REAL_DIR);
        assertEquals("the shim's built-in directory is the server's real one", REAL_DIR, McpStdioShim.DEFAULT_DIR);
    }
}
