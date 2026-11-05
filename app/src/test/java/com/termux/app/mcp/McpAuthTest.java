package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The token of one server epoch: form, constant-time compare, private atomic
 * publication, fixed failure codes, epoch end. Tokens are never put in an assertion
 * message: every check is a boolean with a fixed text.
 */
public class McpAuthTest {

    private static final Pattern TOKEN_FORM = Pattern.compile("[0-9a-f]{64}");

    private static File freshDir() throws IOException {
        Path root = Files.createTempDirectory("mcpauth");
        File dir = new File(root.toFile(), "termux-ai");
        assertTrue(dir.mkdirs());
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"));
        return dir;
    }

    private static String tokenOf(File dir) throws IOException {
        return new String(Files.readAllBytes(new File(dir, McpAuth.TOKEN_FILE_NAME).toPath()), StandardCharsets.UTF_8);
    }

    private static List<String> names(File dir) {
        List<String> r = new ArrayList<>();
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) r.add(k.getName());
        return r;
    }

    private static McpAuth publish(File dir) {
        return McpAuth.publish(dir, McpAuth.TOKEN_FILE_NAME);
    }

    /** Hooks that behave like the real ones except for what a test overrides. */
    private static class PassHooks implements McpAuth.Hooks {
        @Override public void write(FileChannel channel, ByteBuffer data) throws IOException { McpAuth.REAL_HOOKS.write(channel, data); }
        @Override public void move(Path from, Path to) throws IOException { McpAuth.REAL_HOOKS.move(from, to); }
        @Override public void setPermissions(Path path, Set<PosixFilePermission> mode) throws IOException {
            McpAuth.REAL_HOOKS.setPermissions(path, mode);
        }
    }

    // ---- form, uniqueness, publication ----

    @Test
    public void tokenIsSixtyFourLowercaseHexAndNewForEveryEpoch() throws Exception {
        File dir = freshDir();
        McpAuth a = publish(dir);
        assertEquals(McpAuth.AUTH_OK, a.status());
        assertTrue(a.available() && a.live());
        String first = tokenOf(dir);
        assertTrue("form is 64 lowercase hex", TOKEN_FORM.matcher(first).matches());
        a.retire();
        McpAuth b = publish(dir);
        String second = tokenOf(dir);
        assertTrue("form is 64 lowercase hex", TOKEN_FORM.matcher(second).matches());
        assertFalse("a new epoch gets a new token", first.equals(second));
        assertFalse("the old epoch's token is dead", b.ok(first));
        assertTrue("the new epoch's token works", b.ok(second));
    }

    @Test
    public void publishedFileIsRegularOwnerOnlyAndNothingElseIsLeftBehind() throws Exception {
        File dir = freshDir();
        McpAuth a = publish(dir);
        assertTrue(a.available());
        Path f = new File(dir, McpAuth.TOKEN_FILE_NAME).toPath();
        assertTrue(Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f, LinkOption.NOFOLLOW_LINKS));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir.toPath(), LinkOption.NOFOLLOW_LINKS));
        assertEquals(64L, Files.size(f));
        assertEquals(Arrays.asList(McpAuth.TOKEN_FILE_NAME), names(dir));
    }

    @Test
    public void aTooOpenDirectoryIsTightenedAndVerified() throws Exception {
        File dir = freshDir();
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
        McpAuth a = publish(dir);
        assertTrue(a.available());
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir.toPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void aMissingDirectoryIsCreatedPrivate() throws Exception {
        File dir = new File(freshDir(), "deeper/termux-ai");
        McpAuth a = publish(dir);
        assertTrue(a.available());
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir.toPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void aSymlinkAtTheTargetIsReplacedAndNeverFollowed() throws Exception {
        File dir = freshDir();
        File victim = new File(dir.getParentFile(), "victim.txt");
        Files.write(victim.toPath(), "untouched".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(dir, McpAuth.TOKEN_FILE_NAME).toPath(), victim.toPath());
        McpAuth a = publish(dir);
        assertTrue(a.available());
        assertEquals("untouched", new String(Files.readAllBytes(victim.toPath()), StandardCharsets.UTF_8));
        assertFalse(Files.isSymbolicLink(new File(dir, McpAuth.TOKEN_FILE_NAME).toPath()));
    }

    // ---- the compare ----

    @Test
    public void onlyTheExactTokenIsAccepted() throws Exception {
        File dir = freshDir();
        McpAuth a = publish(dir);
        String t = tokenOf(dir);
        assertTrue(a.ok(t));
        assertFalse("null", a.ok(null));
        assertFalse("empty", a.ok(""));
        assertFalse("63 chars", a.ok(t.substring(0, 63)));
        assertFalse("65 chars", a.ok(t + "0"));
        assertFalse("a hundred times longer", a.ok(t + t + t + t + t + t + t + t + t + t));
        assertFalse("trailing newline", a.ok(t + "\n"));
        assertFalse("leading space and 63 chars", a.ok(" " + t.substring(0, 63)));
        assertFalse("upper case", a.ok(t.toUpperCase()) && !t.equals(t.toUpperCase()));
        assertFalse("not hex, right length", a.ok("g" + t.substring(1)));
        assertFalse("NUL padding", a.ok(new String(new char[64])));
        assertFalse("non ASCII, right char count", a.ok("é" + t.substring(1)));
        assertFalse("all zero", a.ok("0000000000000000000000000000000000000000000000000000000000000000"));
        // a char whose low byte is the right hex digit must not alias to it
        char c0 = t.charAt(0);
        assertFalse("a char above U+00FF aliasing the first digit", a.ok((char) (c0 + 0x100) + t.substring(1)));
    }

    @Test
    public void everyPositionOfTheTokenMatters() throws Exception {
        File dir = freshDir();
        McpAuth a = publish(dir);
        String t = tokenOf(dir);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            char other = c == 'a' ? 'b' : 'a';
            String bad = t.substring(0, i) + other + t.substring(i + 1);
            assertFalse("a token differing at position " + i + " must be refused", a.ok(bad));
        }
    }

    @Test
    public void anUnavailableEpochRefusesEverything() throws Exception {
        McpAuth u = McpAuth.unavailable(McpAuth.AUTH_PUBLISH_FAILED);
        assertFalse(u.available());
        assertFalse(u.live());
        assertFalse(u.ok(null));
        assertFalse(u.ok(""));
        assertFalse(u.ok("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals(McpAuth.AUTH_PUBLISH_FAILED, u.status());
    }

    // ---- the epoch ----

    @Test
    public void retireEndsTheEpochAndClosesItsConnectionsOnce() throws Exception {
        File dir = freshDir();
        McpAuth a = publish(dir);
        String t = tokenOf(dir);
        AtomicInteger closedA = new AtomicInteger();
        AtomicInteger closedB = new AtomicInteger();
        Runnable untrackA = a.track(closedA::incrementAndGet);
        a.track(closedB::incrementAndGet);
        untrackA.run();
        assertTrue(a.live() && a.ok(t));
        a.retire();
        a.retire();
        assertFalse("epoch is over", a.live());
        assertFalse("its own token no longer works", a.ok(t));
        assertEquals("an unregistered connection is left alone", 0, closedA.get());
        assertEquals("a registered connection is closed exactly once", 1, closedB.get());
        AtomicInteger late = new AtomicInteger();
        a.track(late::incrementAndGet);
        assertEquals("a connection registered after the end is closed at once", 1, late.get());
    }

    // ---- publication failures: fixed codes, no token file, no leftovers, never available ----

    private static void assertFailed(File dir, McpAuth a, String code) {
        assertEquals(code, a.status());
        assertFalse("never available", a.available());
        assertFalse("never live", a.live());
        assertFalse("no token file", new File(dir, McpAuth.TOKEN_FILE_NAME).exists());
        assertEquals("nothing left behind", new ArrayList<String>(), names(dir));
        assertFalse(a.ok("0000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test
    public void aFailedWriteLeavesNoTokenAndAFixedCode() throws Exception {
        File dir = freshDir();
        McpAuth a = McpAuth.publish(dir, McpAuth.TOKEN_FILE_NAME, new PassHooks() {
            @Override public void write(FileChannel c, ByteBuffer d) throws IOException { throw new IOException("disk full"); }
        });
        assertFailed(dir, a, McpAuth.AUTH_PUBLISH_FAILED);
    }

    @Test
    public void aFailedMoveLeavesNoTokenAndAFixedCode() throws Exception {
        File dir = freshDir();
        McpAuth a = McpAuth.publish(dir, McpAuth.TOKEN_FILE_NAME, new PassHooks() {
            @Override public void move(Path f, Path t) throws IOException { throw new IOException("rename failed"); }
        });
        assertFailed(dir, a, McpAuth.AUTH_PUBLISH_FAILED);
    }

    @Test
    public void aStaleTokenOfAnEarlierEpochDoesNotSurviveAFailedPublication() throws Exception {
        File dir = freshDir();
        McpAuth first = publish(dir);
        assertTrue(first.available());
        McpAuth a = McpAuth.publish(dir, McpAuth.TOKEN_FILE_NAME, new PassHooks() {
            @Override public void write(FileChannel c, ByteBuffer d) throws IOException { throw new IOException("x"); }
        });
        assertFailed(dir, a, McpAuth.AUTH_PUBLISH_FAILED);
    }

    @Test
    public void aWriteThatStoresDifferentBytesIsCaughtByTheReadBack() throws Exception {
        File dir = freshDir();
        McpAuth a = McpAuth.publish(dir, McpAuth.TOKEN_FILE_NAME, new PassHooks() {
            @Override public void write(FileChannel c, ByteBuffer d) throws IOException {
                byte[] b = new byte[d.remaining()];
                d.get(b);
                b[5] = (byte) (b[5] == 'a' ? 'b' : 'a');
                McpAuth.REAL_HOOKS.write(c, ByteBuffer.wrap(b));
            }
        });
        assertFailed(dir, a, McpAuth.AUTH_VERIFY_FAILED);
    }

    @Test
    public void aTruncatedWriteIsCaughtByTheReadBack() throws Exception {
        File dir = freshDir();
        McpAuth a = McpAuth.publish(dir, McpAuth.TOKEN_FILE_NAME, new PassHooks() {
            @Override public void write(FileChannel c, ByteBuffer d) throws IOException {
                byte[] b = new byte[d.remaining()];
                d.get(b);
                McpAuth.REAL_HOOKS.write(c, ByteBuffer.wrap(Arrays.copyOf(b, 40)));
            }
        });
        assertFailed(dir, a, McpAuth.AUTH_VERIFY_FAILED);
    }

    @Test
    public void aPublishedFileThatIsNotOwnerOnlyIsRefused() throws Exception {
        File dir = freshDir();
        McpAuth a = McpAuth.publish(dir, McpAuth.TOKEN_FILE_NAME, new PassHooks() {
            @Override public void move(Path from, Path to) throws IOException {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                Files.setPosixFilePermissions(to, PosixFilePermissions.fromString("rw-r-----"));
            }
        });
        assertFailed(dir, a, McpAuth.AUTH_VERIFY_FAILED);
    }

    @Test
    public void aModeThatCannotBeSetOrVerifiedFailsClosed() throws Exception {
        File dir = freshDir();
        McpAuth a = McpAuth.publish(dir, McpAuth.TOKEN_FILE_NAME, new PassHooks() {
            @Override public void setPermissions(Path p, Set<PosixFilePermission> m) throws IOException {
                if (p.getFileName().toString().startsWith(".")) {
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-rw-rw-"));
                } else {
                    McpAuth.REAL_HOOKS.setPermissions(p, m);
                }
            }
        });
        assertFailed(dir, a, McpAuth.AUTH_PERM_UNVERIFIABLE);

        File dir2 = freshDir();
        McpAuth b = McpAuth.publish(dir2, McpAuth.TOKEN_FILE_NAME, new PassHooks() {
            @Override public void setPermissions(Path p, Set<PosixFilePermission> m) throws IOException {
                throw new UnsupportedOperationException();
            }
        });
        assertFailed(dir2, b, McpAuth.AUTH_PERM_UNVERIFIABLE);
    }

    @Test
    public void anUnsafeDirectoryIsRefusedAndNeverTouched() throws Exception {
        // a symlink in place of the directory
        Path root = Files.createTempDirectory("mcpauth-dir");
        File real = new File(root.toFile(), "real");
        assertTrue(real.mkdirs());
        Files.setPosixFilePermissions(real.toPath(), PosixFilePermissions.fromString("rwx------"));
        File linked = new File(root.toFile(), "termux-ai");
        Files.createSymbolicLink(linked.toPath(), real.toPath());
        Files.write(new File(real, McpAuth.TOKEN_FILE_NAME).toPath(), "someone else's file".getBytes(StandardCharsets.UTF_8));
        McpAuth a = publish(linked);
        assertEquals(McpAuth.AUTH_DIR_UNSAFE, a.status());
        assertFalse(a.available());
        assertEquals("a file behind the symlink is not deleted", "someone else's file",
            new String(Files.readAllBytes(new File(real, McpAuth.TOKEN_FILE_NAME).toPath()), StandardCharsets.UTF_8));

        // a regular file in place of the directory
        File notADir = new File(root.toFile(), "plain");
        Files.write(notADir.toPath(), new byte[0]);
        McpAuth b = publish(notADir);
        assertEquals(McpAuth.AUTH_DIR_UNSAFE, b.status());
        assertFalse(b.available());

        // a directory that cannot be made owner-only
        File dir = freshDir();
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
        McpAuth c = McpAuth.publish(dir, McpAuth.TOKEN_FILE_NAME, new PassHooks() {
            @Override public void setPermissions(Path p, Set<PosixFilePermission> m) throws IOException {
                throw new IOException("denied");
            }
        });
        assertEquals(McpAuth.AUTH_DIR_UNSAFE, c.status());
        assertFalse(c.available());
        assertEquals(new ArrayList<String>(), names(dir));
    }

    @Test
    public void aTokenPathThatCannotBeReplacedFailsWithoutAFallback() throws Exception {
        File dir = freshDir();
        File blocker = new File(dir, McpAuth.TOKEN_FILE_NAME);
        assertTrue(blocker.mkdir());
        Files.write(new File(blocker, "keep").toPath(), new byte[1]);
        McpAuth a = publish(dir);
        assertEquals(McpAuth.AUTH_PUBLISH_FAILED, a.status());
        assertFalse(a.available());
        assertEquals("only the blocker remains", Arrays.asList(McpAuth.TOKEN_FILE_NAME), names(dir));
    }

    // ---- statuses never carry a token ----

    @Test
    public void statusIsAlwaysOneOfTheFixedCodes() throws Exception {
        List<String> fixed = Arrays.asList(McpAuth.AUTH_NOT_STARTED, McpAuth.AUTH_OK, McpAuth.AUTH_DIR_UNSAFE,
            McpAuth.AUTH_PERM_UNVERIFIABLE, McpAuth.AUTH_PUBLISH_FAILED, McpAuth.AUTH_VERIFY_FAILED);
        File dir = freshDir();
        assertTrue(fixed.contains(publish(dir).status()));
        McpAuth failed = McpAuth.publish(dir, McpAuth.TOKEN_FILE_NAME, new PassHooks() {
            @Override public void write(FileChannel c, ByteBuffer d) throws IOException { throw new IOException(); }
        });
        assertTrue(fixed.contains(failed.status()));
        assertNotNull(failed.status());
    }

    // ---- form check of the source: constant-time compare, never equals ----

    private static String source() throws IOException {
        String dir = System.getProperty("mcptest.mcpsrc");
        File f = dir != null ? new File(dir, "McpAuth.java") : new File("src/main/java/com/termux/app/mcp/McpAuth.java");
        assertTrue("McpAuth.java source is readable for the form check", f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String bodyOf(String src, String signature) {
        int at = src.indexOf(signature);
        assertTrue("method " + signature + " exists", at >= 0);
        int end = src.indexOf("\n    }\n", at);
        assertTrue(end > at);
        return src.substring(at, end);
    }

    @Test
    public void everyTokenCompareIsConstantTimeAndNeverEquals() throws Exception {
        String src = source();
        for (String sig : new String[] {"public boolean ok(String presented)", "private static void verifyPublished("}) {
            String body = bodyOf(src, sig);
            assertTrue(sig + " compares with MessageDigest.isEqual", body.contains("MessageDigest.isEqual("));
            assertFalse(sig + " must not use equals", body.contains(".equals(") || body.contains("Arrays.equals(")
                || body.contains("contentEquals(") || body.contains("compareTo("));
        }
        assertFalse("no Arrays.equals anywhere", src.contains("Arrays.equals("));
    }
}
