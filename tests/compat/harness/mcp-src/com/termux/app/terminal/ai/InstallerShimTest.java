package com.termux.app.terminal.ai;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;

import com.termux.shared.logger.Logger;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The installed MCP shim is provably the shipped asset or it is gone: the old socat
 * shim is replaced, a failed update leaves no stale copy, other assets keep their
 * plain install. Runs the REAL installer against the shipped asset directory.
 */
public class InstallerShimTest {

    private static final File ASSETS = new File(System.getProperty("mcptest.assets"));
    private static final String SHIM = "termux-ai-mcp";
    private static final String OLD = "#!/data/data/com.termux/files/usr/bin/sh\nexec socat - \"UNIX-CONNECT:$SOCK\"\n";

    private static File bin() throws Exception {
        return Files.createTempDirectory("instbin").toFile();
    }

    private static byte[] asset(String name) throws Exception {
        return Files.readAllBytes(new File(ASSETS, name).toPath());
    }

    private static void useShippedAssets() {
        AssetManager.ROOT = ASSETS;
        AssetManager.HOOK = null;
    }

    @Test
    public void theShippedShimIsTheBridgeLauncherAndNotTheOldSocatScript() throws Exception {
        String text = new String(asset(SHIM), StandardCharsets.UTF_8);
        assertTrue(text.contains("app_process"));
        assertTrue(text.contains("com.termux.app.mcp.McpStdioShim"));
        assertFalse("no socat in the new shim", text.contains("socat"));
        assertFalse("the old script is not shipped", text.equals(OLD));
        assertFalse("no token handling in the shell", text.contains("mcp.token"));
    }

    @Test
    public void installsTheShippedShimExecutableAndByteIdentical() throws Exception {
        useShippedAssets();
        File bin = bin();
        TermuxAiCliInstaller.installNamedAssets(new Context(), bin);
        File installed = new File(bin, SHIM);
        assertTrue(installed.isFile() && installed.canExecute());
        assertArrayEquals(asset(SHIM), Files.readAllBytes(installed.toPath()));
        java.util.List<String> names = new java.util.ArrayList<>(Arrays.asList(bin.list()));
        java.util.Collections.sort(names);
        assertEquals("only the two assets, no temp file next to them", Arrays.asList(SHIM, "termux-ai-serve"), names);
        for (String n : bin.list()) assertFalse("leftover " + n, n.startsWith("."));
    }

    @Test
    public void anOlderShimIsReplacedInPlace() throws Exception {
        useShippedAssets();
        File bin = bin();
        Files.write(new File(bin, SHIM).toPath(), OLD.getBytes(StandardCharsets.UTF_8));
        TermuxAiCliInstaller.installNamedAssets(new Context(), bin);
        byte[] now = Files.readAllBytes(new File(bin, SHIM).toPath());
        assertArrayEquals("the update took effect", asset(SHIM), now);
        assertFalse(new String(now, StandardCharsets.UTF_8).contains("socat"));
    }

    @Test
    public void aFailedInstallRemovesTheStaleShimAndLogsAFixedLine() throws Exception {
        AssetManager.ROOT = Files.createTempDirectory("emptyassets").toFile(); // the asset is missing
        AssetManager.HOOK = null;
        File bin = bin();
        Files.write(new File(bin, SHIM).toPath(), OLD.getBytes(StandardCharsets.UTF_8));
        Logger.CAPTURED.clear();
        boolean ok = TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM));
        assertFalse(ok);
        assertFalse("no stale shim survives a failed update", new File(bin, SHIM).exists());
        assertEquals(Arrays.asList("TermuxAiCliInstaller: install not verified: " + SHIM), Logger.CAPTURED);
        for (String n : bin.list()) assertFalse("leftover " + n, n.startsWith("."));
        useShippedAssets();
    }

    @Test
    public void bytesThatChangeBetweenWriteAndVerifyAreCaught() throws Exception {
        final AtomicInteger opens = new AtomicInteger();
        AssetManager.HOOK = name -> {
            // first open: what gets written; later opens (the comparison): something else
            byte[] b = (opens.incrementAndGet() == 1 ? "#!/bin/sh\necho one\n" : "#!/bin/sh\necho two\n").getBytes(StandardCharsets.UTF_8);
            return new ByteArrayInputStream(b);
        };
        try {
            File bin = bin();
            Files.write(new File(bin, SHIM).toPath(), OLD.getBytes(StandardCharsets.UTF_8));
            final AtomicInteger swaps = new AtomicInteger();
            boolean ok = TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM), (from, to) -> {
                swaps.incrementAndGet();
                Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            });
            assertEquals("a copy that does not match is never swapped in", 0, swaps.get());
            assertFalse("the mismatch is detected", ok);
            assertFalse("and the unverified copy is not left installed", new File(bin, SHIM).exists());
        } finally {
            useShippedAssets();
        }
    }

    @Test
    public void aTruncatedCopyIsCaught() throws Exception {
        // the asset stream ends early on the write but is whole on the comparison
        final AtomicInteger opens = new AtomicInteger();
        final byte[] whole = asset(SHIM);
        AssetManager.HOOK = name -> new ByteArrayInputStream(opens.incrementAndGet() == 1 ? Arrays.copyOf(whole, whole.length / 2) : whole);
        try {
            File bin = bin();
            final AtomicInteger swaps = new AtomicInteger();
            boolean ok = TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM), (from, to) -> {
                swaps.incrementAndGet();
                Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            });
            assertEquals("a truncated copy is never swapped in", 0, swaps.get());
            assertFalse(ok);
            assertFalse(new File(bin, SHIM).exists());
        } finally {
            useShippedAssets();
        }
    }

    @Test
    public void theOtherNamedAssetKeepsItsPlainInstall() throws Exception {
        useShippedAssets();
        File bin = bin();
        TermuxAiCliInstaller.installNamedAssets(new Context(), bin);
        assertArrayEquals(asset("termux-ai-serve"), Files.readAllBytes(new File(bin, "termux-ai-serve").toPath()));
        assertTrue(new File(bin, "termux-ai-serve").canExecute());
        assertTrue(Arrays.asList(TermuxAiCliInstaller.VERIFIED_ASSETS).contains(SHIM));
        assertFalse(Arrays.asList(TermuxAiCliInstaller.VERIFIED_ASSETS).contains("termux-ai-serve"));
    }

    // ---- the temporary file and the target are never followed, never foreign ----

    private static final String FIXED_ASIDE = ".termux-ai-mcp.new";
    private static final java.util.Set<java.nio.file.attribute.PosixFilePermission> MODE_755 =
        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");

    private static File victim(File bin) throws Exception {
        File v = new File(bin.getParentFile(), "victim-" + bin.getName() + ".txt");
        Files.write(v.toPath(), "victim-before".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(v.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        return v;
    }

    private static void assertVictimIntact(File v) throws Exception {
        assertEquals("the foreign file keeps its bytes", "victim-before", new String(Files.readAllBytes(v.toPath()), StandardCharsets.UTF_8));
        assertEquals("the foreign file keeps its mode", java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(v.toPath()));
    }

    private static void assertInstalledOk(File bin) throws Exception {
        java.nio.file.Path t = new File(bin, SHIM).toPath();
        assertTrue("a regular file, not a link", Files.isRegularFile(t, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals(MODE_755, Files.getPosixFilePermissions(t, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(asset(SHIM), Files.readAllBytes(t));
    }

    @Test
    public void aSymlinkAtTheOldFixedTemporaryNameIsNeverFollowed() throws Exception {
        useShippedAssets();
        File bin = bin();
        File v = victim(bin);
        Files.write(new File(bin, SHIM).toPath(), OLD.getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(bin, FIXED_ASIDE).toPath(), v.toPath());
        Logger.CAPTURED.clear();
        boolean ok = TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM));
        assertTrue("the install itself succeeds, on a fresh private temporary", ok);
        assertVictimIntact(v);
        assertTrue("the planted link is left exactly as found",
            Files.isSymbolicLink(new File(bin, FIXED_ASIDE).toPath()) && Files.readSymbolicLink(new File(bin, FIXED_ASIDE).toPath()).equals(v.toPath()));
        assertInstalledOk(bin);
        assertEquals(new java.util.ArrayList<String>(), Logger.CAPTURED);
    }

    @Test
    public void aForeignFileOrDirectoryAtTheOldTemporaryNameIsLeftAlone() throws Exception {
        useShippedAssets();
        for (String kind : new String[] {"file", "dangling", "dir"}) {
            File bin = bin();
            File planted = new File(bin, FIXED_ASIDE);
            if (kind.equals("file")) Files.write(planted.toPath(), "foreign".getBytes(StandardCharsets.UTF_8));
            if (kind.equals("dangling")) Files.createSymbolicLink(planted.toPath(), new File(bin.getParentFile(), "nowhere-" + bin.getName()).toPath());
            if (kind.equals("dir")) {
                assertTrue(planted.mkdir());
                Files.write(new File(planted, "keep").toPath(), new byte[3]);
            }
            assertTrue(kind, TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM)));
            assertInstalledOk(bin);
            assertTrue(kind + ": still there", Files.exists(planted.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            if (kind.equals("file")) assertEquals("foreign", new String(Files.readAllBytes(planted.toPath()), StandardCharsets.UTF_8));
            if (kind.equals("dangling")) assertFalse("nothing was created behind the dangling link",
                new File(bin.getParentFile(), "nowhere-" + bin.getName()).exists());
            if (kind.equals("dir")) assertTrue(new File(planted, "keep").isFile());
        }
    }

    @Test
    public void aTargetThatIsALinkOrADirectoryIsRefusedAndLeftExactlyAsFound() throws Exception {
        useShippedAssets();
        // a link to a foreign file
        File bin = bin();
        File v = victim(bin);
        Files.createSymbolicLink(new File(bin, SHIM).toPath(), v.toPath());
        Logger.CAPTURED.clear();
        assertFalse(TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM)));
        assertVictimIntact(v);
        assertTrue(Files.isSymbolicLink(new File(bin, SHIM).toPath()));
        assertEquals(Arrays.asList("TermuxAiCliInstaller: install refused, target is not a regular file: " + SHIM), Logger.CAPTURED);
        assertEquals("no temporary left", Arrays.asList(SHIM), Arrays.asList(bin.list()));

        // a dangling link
        File bin2 = bin();
        File nowhere = new File(bin2.getParentFile(), "nowhere2-" + bin2.getName());
        Files.createSymbolicLink(new File(bin2, SHIM).toPath(), nowhere.toPath());
        assertFalse(TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin2, SHIM)));
        assertFalse("nothing created behind the link", nowhere.exists());
        assertTrue(Files.isSymbolicLink(new File(bin2, SHIM).toPath()));

        // a directory
        File bin3 = bin();
        assertTrue(new File(bin3, SHIM).mkdir());
        Files.write(new File(bin3, SHIM + "/keep").toPath(), new byte[2]);
        assertFalse(TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin3, SHIM)));
        assertTrue(new File(bin3, SHIM + "/keep").isFile());
        assertEquals(Arrays.asList(SHIM), Arrays.asList(bin3.list()));
    }

    @Test
    public void aFailedCopyLeavesNoTemporaryAndAFixedLine() throws Exception {
        useShippedAssets();
        File bin = bin();
        Files.write(new File(bin, SHIM).toPath(), OLD.getBytes(StandardCharsets.UTF_8));
        // the asset cannot be read half way: the copy fails after the temporary was created
        final AtomicInteger opens = new AtomicInteger();
        AssetManager.HOOK = name -> new InputStream() {
            int n;
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("copy failed"); }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                opens.incrementAndGet();
                throw new java.io.IOException("copy failed");
            }
        };
        Logger.CAPTURED.clear();
        try {
            assertFalse(TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM)));
        } finally {
            useShippedAssets();
        }
        assertEquals(Arrays.asList("TermuxAiCliInstaller: install not verified: " + SHIM), Logger.CAPTURED);
        assertEquals("no temporary and no stale shim", new java.util.ArrayList<String>(), Arrays.asList(bin.list()));
    }

    @Test
    public void aFailedSwapRemovesTheStaleShimAndTheTemporary() throws Exception {
        useShippedAssets();
        File bin = bin();
        Files.write(new File(bin, SHIM).toPath(), OLD.getBytes(StandardCharsets.UTF_8));
        Logger.CAPTURED.clear();
        boolean ok = TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM),
            (from, to) -> { throw new java.io.IOException("swap failed"); });
        assertFalse(ok);
        assertEquals(Arrays.asList("TermuxAiCliInstaller: install not verified: " + SHIM), Logger.CAPTURED);
        assertEquals(new java.util.ArrayList<String>(), Arrays.asList(bin.list()));
    }

    @Test
    public void aSwapThatPutsSomethingElseInPlaceIsCaughtOnTheFinalQualification() throws Exception {
        useShippedAssets();
        File bin = bin();
        File v = victim(bin);
        boolean ok = TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM), (from, to) -> {
            Files.delete(from);
            Files.createSymbolicLink(to, v.toPath()); // a link where the verified copy should be
        });
        assertFalse("a link is never a verified install", ok);
        // what somebody else put at the target is not ours to delete: it is left, and the install says "not verified"
        assertTrue(Files.isSymbolicLink(new File(bin, SHIM).toPath()));
        assertVictimIntact(v);
    }

    @Test
    public void aStaleShimThatCannotBeRemovedIsReportedNotCalledAbsent() throws Exception {
        useShippedAssets();
        File bin = bin();
        File shim = new File(bin, SHIM);
        Files.write(shim.toPath(), OLD.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(bin.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
        Logger.CAPTURED.clear();
        try {
            boolean ok = TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, shim);
            assertFalse(ok);
            assertTrue("the old copy is still there and the log says so",
                shim.exists() && Logger.CAPTURED.contains("TermuxAiCliInstaller: stale shim not removed: " + SHIM));
            assertTrue(Logger.CAPTURED.contains("TermuxAiCliInstaller: install not verified: " + SHIM));
        } finally {
            Files.setPosixFilePermissions(bin.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    public void repeatedInstallsLeaveNoTemporaries() throws Exception {
        useShippedAssets();
        File bin = bin();
        for (int i = 0; i < 3; i++) assertTrue(TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM)));
        assertEquals(Arrays.asList(SHIM), Arrays.asList(bin.list()));
        assertInstalledOk(bin);
    }

    // ---- a name that is already taken is never ours to remove ----

    /** A SecureRandom provider whose first draws are all zero bytes (predictable suffixes), later draws are 0xff. */
    public static class ScriptedRandom extends java.security.SecureRandomSpi {
        static volatile int zeroDraws;
        static final AtomicInteger draws = new AtomicInteger();
        @Override protected void engineSetSeed(byte[] seed) { }
        @Override protected void engineNextBytes(byte[] bytes) {
            Arrays.fill(bytes, (byte) (draws.getAndIncrement() < zeroDraws ? 0x00 : 0xff));
        }
        @Override protected byte[] engineGenerateSeed(int n) { return new byte[n]; }
    }

    private static final String ZERO_NAME = ".termux-ai-mcp.new-0000000000000000";

    private static void withScriptedRandom(int zeroDraws, Runnable body) {
        java.security.Provider provider = new java.security.Provider("TempNameCollision", 1.0, "scripted suffixes") { };
        provider.put("SecureRandom.TempNameCollision", ScriptedRandom.class.getName());
        ScriptedRandom.zeroDraws = zeroDraws;
        ScriptedRandom.draws.set(0);
        java.security.Security.insertProviderAt(provider, 1);
        try {
            body.run();
        } finally {
            java.security.Security.removeProvider("TempNameCollision");
        }
    }

    @Test
    public void aCollidingTemporaryNameIsRetriedAndTheForeignFileSurvives() throws Exception {
        useShippedAssets();
        for (String kind : new String[] {"file", "symlink", "dangling", "dir"}) {
            final File bin = bin();
            final File v = victim(bin);
            File planted = new File(bin, ZERO_NAME);
            if (kind.equals("file")) Files.write(planted.toPath(), "pre-existing-foreign".getBytes(StandardCharsets.UTF_8));
            if (kind.equals("symlink")) Files.createSymbolicLink(planted.toPath(), v.toPath());
            if (kind.equals("dangling")) Files.createSymbolicLink(planted.toPath(), new File(bin.getParentFile(), "gone-" + bin.getName()).toPath());
            if (kind.equals("dir")) assertTrue(planted.mkdir());
            Files.write(new File(bin, SHIM).toPath(), OLD.getBytes(StandardCharsets.UTF_8));
            final boolean[] ok = new boolean[1];
            withScriptedRandom(1, () -> ok[0] = TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM)));
            assertTrue(kind + ": the second name is free, the install succeeds", ok[0]);
            assertInstalledOk(bin);
            assertTrue(kind + ": the foreign entry is still there", Files.exists(planted.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            if (kind.equals("file")) assertEquals("pre-existing-foreign", new String(Files.readAllBytes(planted.toPath()), StandardCharsets.UTF_8));
            if (kind.equals("symlink")) {
                assertTrue(Files.isSymbolicLink(planted.toPath()));
                assertVictimIntact(v);
            }
            if (kind.equals("dangling")) assertFalse(new File(bin.getParentFile(), "gone-" + bin.getName()).exists());
            if (kind.equals("dir")) assertTrue(planted.isDirectory());
        }
    }

    @Test
    public void whenEveryTryCollidesTheInstallFailsAndNothingForeignIsRemoved() throws Exception {
        useShippedAssets();
        final File bin = bin();
        File planted = new File(bin, ZERO_NAME);
        Files.write(planted.toPath(), "pre-existing-foreign".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(bin, SHIM).toPath(), OLD.getBytes(StandardCharsets.UTF_8));
        Logger.CAPTURED.clear();
        final boolean[] ok = new boolean[1];
        withScriptedRandom(1000, () -> ok[0] = TermuxAiCliInstaller.installVerifiedAsset(new Context(), SHIM, new File(bin, SHIM)));
        assertFalse("a bounded number of tries, then a refusal", ok[0]);
        assertEquals("the colliding foreign file is intact", "pre-existing-foreign", new String(Files.readAllBytes(planted.toPath()), StandardCharsets.UTF_8));
        assertEquals("the stale shim is removed, nothing else", Arrays.asList(ZERO_NAME), Arrays.asList(bin.list()));
        assertEquals(Arrays.asList("TermuxAiCliInstaller: install not verified: " + SHIM), Logger.CAPTURED);
        assertEquals("exactly the bounded number of tries", 4, ScriptedRandom.draws.get());
    }
}
