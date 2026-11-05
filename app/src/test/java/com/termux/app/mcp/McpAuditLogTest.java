package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Plain-JUnit tests of the content-free, size-bounded audit log (no Android
 * classes). Synthetic sentinels stand in for UI text, typed text, ids and
 * error text; none of them may ever reach the log files.
 */
public class McpAuditLogTest {

    private static final long CAP = McpAuditLog.MAX_FILE_BYTES;
    private static final Pattern LINE = Pattern.compile(
        "[0-9]+\t(accessibility_status|ui_snapshot|tap|type|launch_app|wake|unknown)\t"
            + "(OK|ERROR|EXCEPTION|SERVICE_OFF|POLICY_DENIED|UNKNOWN_TOOL|STALE_REF|NODE_NOT_FOUND|"
            + "ACTION_UNSUPPORTED|GESTURE_FAILED|NO_ACTIVE_WINDOW|PACKAGE_NOT_FOUND|LAUNCH_FAILED|WAKE_FAILED)");

    private static final String SENTINEL_TEXT = "SENTINEL_UI_TEXT_7f3a";
    private static final String SENTINEL_DESC = "SENTINEL_DESC_91c2";
    private static final String SENTINEL_TYPE = "SENTINEL_TYPED_55d0";
    private static final String SENTINEL_ERR = "SENTINEL_ERR_0b8e";
    private static final String SENTINEL_TOOL = "SENTINEL_TOOLNAME_c41d";
    private static final String SENTINEL_JSON = "SENTINEL_MALFORMED_e9a7";
    private static final String[] ALL_SENTINELS = {
        SENTINEL_TEXT, SENTINEL_DESC, SENTINEL_TYPE, SENTINEL_ERR, SENTINEL_TOOL, SENTINEL_JSON
    };

    private static final McpAuditLog.Clock FIXED_CLOCK = new McpAuditLog.Clock() {
        @Override public long nowMs() { return 1L; }
    };

    private static final McpAuditLog.Hooks REAL = new McpAuditLog.Hooks() {
        @Override public void move(Path a, Path b) throws IOException {
            Files.move(a, b, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        @Override public void setPermissions(Path p, Set<PosixFilePermission> m) throws IOException {
            Files.setPosixFilePermissions(p, m);
        }
        @Override public void write(FileChannel c, ByteBuffer d) throws IOException {
            while (d.hasRemaining()) c.write(d);
        }
    };

    /** One recorded event of "tap"/OK is exactly this many bytes with FIXED_CLOCK. */
    private static final int LINE_BYTES = "1\ttap\tOK\n".length();

    private static File tmp(String label) throws IOException {
        Path base = Files.createTempDirectory("termuxai-audit-" + label + "-");
        return base.toFile();
    }

    private static File auditDir(File root) throws IOException {
        File d = new File(root, "termux-ai");
        Files.createDirectories(d.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        return d;
    }

    private static void rmTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null && !Files.isSymbolicLink(f.toPath())) {
            for (File k : kids) rmTree(k);
        }
        try { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rwx------")); }
        catch (Exception ignored) { }
        f.delete();
    }

    private static byte[] readAll(File f) throws IOException {
        return Files.readAllBytes(f.toPath());
    }

    /** Fills the file with exactly {@code bytes} bytes of whole, in-schema lines (or leaves it empty). */
    private static void seed(File f, long bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (bytes > 0) {
            if (bytes < LINE_BYTES) throw new IllegalArgumentException("too small to hold a line");
            long full = bytes / LINE_BYTES;
            int extra = (int) (bytes % LINE_BYTES);
            for (long i = 0; i < full - 1; i++) sb.append("1\ttap\tOK\n");
            sb.append('1');
            for (int i = 0; i < extra; i++) sb.append('1');
            sb.append("\ttap\tOK\n");
        }
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"));
    }

    private static void write(File f, String content) throws IOException {
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"));
    }

    /** Every line of the file is whole and in schema: the file never holds a broken line. */
    private static void assertWholeLines(File f) throws IOException {
        String body = new String(readAll(f), StandardCharsets.UTF_8);
        if (body.isEmpty()) return;
        assertTrue("ends with LF", body.endsWith("\n"));
        for (String line : body.split("\n")) assertTrue("schema: " + line, LINE.matcher(line).matches());
    }

    private static McpAuditLog log(File dir) {
        return new McpAuditLog(dir, FIXED_CLOCK, REAL);
    }

    private static String tapOk(McpAuditLog l) {
        return l.record("tap", "{\"ok\":true}");
    }

    private static void eq(String m, long expected, long actual) {
        assertTrue(m + ": expected " + expected + " got " + actual, expected == actual);
    }

    private static Set<PosixFilePermission> mode(File f) throws IOException {
        return Files.getPosixFilePermissions(f.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    // ---- schema / labels ----------------------------------------------------

    @Test
    public void toolNameComesFromAllowlistOnly() {
        for (String t : new String[] {"accessibility_status", "ui_snapshot", "tap", "type", "launch_app", "wake"}) {
            assertEquals(t, McpAuditLog.toolLabel(t));
        }
        assertEquals("unknown", McpAuditLog.toolLabel(SENTINEL_TOOL));
        assertEquals("unknown", McpAuditLog.toolLabel("tap\n1\ttap\tOK"));
        assertEquals("unknown", McpAuditLog.toolLabel("tap "));
        assertEquals("unknown", McpAuditLog.toolLabel("TAP"));
        assertEquals("unknown", McpAuditLog.toolLabel("täp€😀"));
        assertEquals("unknown", McpAuditLog.toolLabel(""));
        assertEquals("unknown", McpAuditLog.toolLabel(null));
    }

    @Test
    public void outcomeIsFromAClosedVocabularyAndNeverCopiesTheResult() {
        assertEquals("OK", McpAuditLog.outcomeLabel("tap", "{\"ok\":true}"));
        assertEquals("POLICY_DENIED", McpAuditLog.outcomeLabel("tap", "{\"ok\":false,\"error\":\"POLICY_DENIED\"}"));
        assertEquals("UNKNOWN_TOOL", McpAuditLog.outcomeLabel("unknown", "{\"error\":\"UNKNOWN_TOOL\",\"ok\":false}"));
        // an error code outside the vocabulary is only "ERROR", whatever it says
        assertEquals("ERROR", McpAuditLog.outcomeLabel("tap", "{\"ok\":false,\"error\":\"" + SENTINEL_ERR + "\"}"));
        assertEquals("ERROR", McpAuditLog.outcomeLabel("ui_snapshot", "{\"ok\":false,\"error\":\"" + SENTINEL_ERR + "\"}"));
        assertEquals("ERROR", McpAuditLog.outcomeLabel("tap", null));
        assertEquals("ERROR", McpAuditLog.outcomeLabel("tap", SENTINEL_JSON + "{{{"));
        assertEquals("ERROR", McpAuditLog.outcomeLabel("type", "{\"ok\":true,\"text\":\"" + SENTINEL_TYPE + "\"}"));
        // data tools: a UI tree is OK, regardless of what the text nodes say
        String tree = "{\"snapshot_id\":\"snap-1\",\"nodes\":[{\"text\":\"" + SENTINEL_TEXT
            + "\",\"desc\":\"" + SENTINEL_DESC + "\"}]}";
        assertEquals("OK", McpAuditLog.outcomeLabel("ui_snapshot", tree));
        assertEquals("OK", McpAuditLog.outcomeLabel("accessibility_status", "{\"enabled\":true}"));
    }

    @Test
    public void noLineCanExceedTheLineLimit() {
        StringBuilder worst = new StringBuilder();
        worst.append(Long.MAX_VALUE).append('\t').append("accessibility_status").append('\t').append("ACTION_UNSUPPORTED").append('\n');
        assertTrue(worst.toString().getBytes(StandardCharsets.UTF_8).length <= McpAuditLog.MAX_LINE_BYTES);
    }

    @Test
    public void everyLineFollowsTheClosedSchema() throws Exception {
        File root = tmp("schema");
        try {
            File dir = auditDir(root);
            McpAuditLog l = new McpAuditLog(dir);
            assertEquals("AUDIT_OK", l.record("ui_snapshot", "{\"snapshot_id\":\"x\"}"));
            assertEquals("AUDIT_OK", l.record("tap", "{\"ok\":false,\"error\":\"STALE_REF\"}"));
            assertEquals("AUDIT_OK", l.record(SENTINEL_TOOL, "{\"ok\":false,\"error\":\"UNKNOWN_TOOL\"}"));
            assertEquals("AUDIT_OK", l.recordException("type"));
            List<String> lines = Arrays.asList(new String(readAll(new File(dir, McpAuditLog.FILE_NAME)),
                StandardCharsets.UTF_8).split("\n", -1));
            eq("lines + trailing empty", 5, lines.size());
            assertEquals("", lines.get(4));
            for (int i = 0; i < 4; i++) assertTrue("schema: " + lines.get(i), LINE.matcher(lines.get(i)).matches());
            assertTrue(lines.get(2).endsWith("\tunknown\tUNKNOWN_TOOL"));
            assertTrue(lines.get(3).endsWith("\ttype\tEXCEPTION"));
        } finally {
            rmTree(root);
        }
    }

    // ---- no content, ever -----------------------------------------------------

    @Test
    public void syntheticSentinelsNeverReachAnyFileOrStatus() throws Exception {
        File root = tmp("sentinels");
        try {
            File dir = auditDir(root);
            // a tiny cap is not configurable; force rotations with many events so
            // both generations are checked
            McpAuditLog l = new McpAuditLog(dir);
            List<String> statuses = new ArrayList<>();
            String tree = "{\"snapshot_id\":\"snap-9\",\"nodes\":[{\"node_ref\":\"n0\",\"text\":\"" + SENTINEL_TEXT
                + "\",\"desc\":\"" + SENTINEL_DESC + "\",\"id\":\"" + SENTINEL_TEXT + "\"}]}";
            for (int i = 0; i < 12000; i++) {
                statuses.add(l.record("ui_snapshot", tree));
                statuses.add(l.record("type", "{\"ok\":true}"));                       // argument text never passed
                statuses.add(l.record("type\t" + SENTINEL_TYPE, "{\"ok\":true}"));    // sentinel in the tool slot
                statuses.add(l.record(SENTINEL_TOOL, "{\"ok\":false,\"error\":\"UNKNOWN_TOOL\"}"));
                statuses.add(l.record("tap", "{\"ok\":false,\"error\":\"" + SENTINEL_ERR + "\"}"));
                statuses.add(l.record("tap", SENTINEL_JSON + "{{{ not json"));
                statuses.add(l.recordException(SENTINEL_TOOL));
            }
            statuses.add(l.status());
            for (String st : statuses) {
                assertTrue("fixed status: " + st, st.startsWith("AUDIT_"));
                for (String s : ALL_SENTINELS) assertFalse(st.contains(s));
            }
            File rotated = new File(dir, McpAuditLog.ROTATED_NAME);
            assertTrue("rotation happened", rotated.exists());
            for (File f : dir.listFiles()) {
                String body = new String(readAll(f), StandardCharsets.UTF_8);
                for (String s : ALL_SENTINELS) assertFalse(f.getName() + " leaks " + s, body.contains(s));
                for (String line : body.split("\n")) assertTrue("schema: " + line, LINE.matcher(line).matches());
            }
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void utf8ArgumentsProduceOnlyAsciiLines() throws Exception {
        File root = tmp("utf8");
        try {
            File dir = auditDir(root);
            McpAuditLog l = new McpAuditLog(dir);
            assertEquals("AUDIT_OK", l.record("täp€😀", "{\"ok\":false,\"error\":\"€😀\"}"));
            byte[] b = readAll(new File(dir, McpAuditLog.FILE_NAME));
            for (byte x : b) assertTrue("ascii only", x >= 0);
            assertTrue(b.length <= McpAuditLog.MAX_LINE_BYTES);
            assertTrue(LINE.matcher(new String(b, StandardCharsets.UTF_8).trim()).matches());
        } finally {
            rmTree(root);
        }
    }

    // ---- modes, symlinks, anomalies ------------------------------------------

    @Test
    public void createsFileAs0600InDirectory0700() throws Exception {
        File root = tmp("modes");
        try {
            File dir = new File(root, "termux-ai-new");
            McpAuditLog l = new McpAuditLog(dir);
            assertEquals("AUDIT_OK", tapOk(l));
            assertEquals(PosixFilePermissions.fromString("rwx------"), mode(dir));
            assertEquals(PosixFilePermissions.fromString("rw-------"), mode(new File(dir, McpAuditLog.FILE_NAME)));
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void widerModesAreTightenedAndVerified() throws Exception {
        File root = tmp("tighten");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            seed(f, 0);
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-rw-rw-"));
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
            assertEquals("AUDIT_OK", tapOk(new McpAuditLog(dir)));
            assertEquals(PosixFilePermissions.fromString("rwx------"), mode(dir));
            assertEquals(PosixFilePermissions.fromString("rw-------"), mode(f));
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void unreadableFileIsRepairedWhenTheModeCanBeSet() throws Exception {
        File root = tmp("unreadable");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            seed(f, 0);
            Files.setPosixFilePermissions(f.toPath(), new HashSet<PosixFilePermission>());
            assertEquals("AUDIT_OK", tapOk(log(dir)));
            assertEquals(PosixFilePermissions.fromString("rw-------"), mode(f));
            eq("one line", LINE_BYTES, f.length());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void modeThatCannotBeFixedSuspendsTheLogWithoutWriting() throws Exception {
        File root = tmp("nofix");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            seed(f, 0);
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-rw-rw-"));
            McpAuditLog l = new McpAuditLog(dir, FIXED_CLOCK, new McpAuditLog.Hooks() {
                @Override public void move(Path a, Path b) throws IOException { REAL.move(a, b); }
                @Override public void setPermissions(Path p, Set<PosixFilePermission> m) throws IOException {
                    throw new IOException("denied");
                }
                @Override public void write(FileChannel c, ByteBuffer d) throws IOException { REAL.write(c, d); }
            });
            assertEquals("AUDIT_FILE_UNSAFE", tapOk(l));
            assertEquals("AUDIT_FILE_UNSAFE", l.status());
            eq("nothing written", 0, f.length());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void directoryModeThatCannotBeFixedIsRefused() throws Exception {
        File root = tmp("dirnofix");
        try {
            File dir = auditDir(root);
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
            McpAuditLog l = new McpAuditLog(dir, FIXED_CLOCK, new McpAuditLog.Hooks() {
                @Override public void move(Path a, Path b) throws IOException { REAL.move(a, b); }
                @Override public void setPermissions(Path p, Set<PosixFilePermission> m) throws IOException {
                    throw new IOException("denied");
                }
                @Override public void write(FileChannel c, ByteBuffer d) throws IOException { REAL.write(c, d); }
            });
            assertEquals("AUDIT_DIR_UNSAFE", tapOk(l));
            assertFalse(new File(dir, McpAuditLog.FILE_NAME).exists());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void symlinkedActiveFileIsRefusedAndTargetUntouched() throws Exception {
        File root = tmp("symfile");
        try {
            File dir = auditDir(root);
            File victim = new File(root, "victim.txt");
            Files.write(victim.toPath(), "untouched".getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(new File(dir, McpAuditLog.FILE_NAME).toPath(), victim.toPath());
            assertEquals("AUDIT_FILE_UNSAFE", tapOk(log(dir)));
            assertEquals("untouched", new String(readAll(victim), StandardCharsets.UTF_8));
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void danglingSymlinkIsNotCreatedThrough() throws Exception {
        File root = tmp("dangling");
        try {
            File dir = auditDir(root);
            File target = new File(root, "not-yet.txt");
            Files.createSymbolicLink(new File(dir, McpAuditLog.FILE_NAME).toPath(), target.toPath());
            assertEquals("AUDIT_FILE_UNSAFE", tapOk(log(dir)));
            assertFalse(target.exists());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void symlinkedDirectoryIsRefused() throws Exception {
        File root = tmp("symdir");
        try {
            File real = auditDir(root);
            File link = new File(root, "link");
            Files.createSymbolicLink(link.toPath(), real.toPath());
            assertEquals("AUDIT_DIR_UNSAFE", tapOk(log(link)));
            eq("real dir stays empty", 0, real.listFiles().length);
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void directoryOrSpecialInPlaceOfTheFileIsRefused() throws Exception {
        File root = tmp("anomaly");
        try {
            File dir = auditDir(root);
            assertTrue(new File(dir, McpAuditLog.FILE_NAME).mkdir());
            assertEquals("AUDIT_FILE_UNSAFE", tapOk(log(dir)));
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void oversizedActiveFileIsRefusedNotGrown() throws Exception {
        File root = tmp("oversize");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            seed(f, CAP + 1);
            assertEquals("AUDIT_FILE_UNSAFE", tapOk(log(dir)));
            eq("not grown", CAP + 1, f.length());
            assertFalse(new File(dir, McpAuditLog.ROTATED_NAME).exists());
        } finally {
            rmTree(root);
        }
    }

    // ---- rotation ----------------------------------------------------------------

    @Test
    public void capMinusOneEventFitsAndTheFileNeverExceedsTheCap() throws Exception {
        File root = tmp("capfit");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            seed(f, CAP - LINE_BYTES - 1);                  // cap-1 after the event
            McpAuditLog l = log(dir);
            assertEquals("AUDIT_OK", tapOk(l));
            eq("cap-1", CAP - 1, f.length());
            assertFalse(new File(dir, McpAuditLog.ROTATED_NAME).exists());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void eventEndingExactlyAtTheCapDoesNotRotate() throws Exception {
        File root = tmp("capexact");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            seed(f, CAP - LINE_BYTES);
            assertEquals("AUDIT_OK", tapOk(log(dir)));
            eq("exactly cap", CAP, f.length());
            assertFalse(new File(dir, McpAuditLog.ROTATED_NAME).exists());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void eventThatWouldCrossTheCapRotatesBeforeAppending() throws Exception {
        File root = tmp("capcross");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            File old = new File(dir, McpAuditLog.ROTATED_NAME);
            seed(f, CAP - LINE_BYTES + 1);                  // cap+1 if appended in place
            assertEquals("AUDIT_OK", tapOk(log(dir)));
            eq("rotated generation", CAP - LINE_BYTES + 1, old.length());
            eq("fresh active holds only the new event", LINE_BYTES, f.length());
            assertEquals(PosixFilePermissions.fromString("rw-------"), mode(f));
            assertEquals(PosixFilePermissions.fromString("rw-------"), mode(old));
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void fileAtExactlyTheCapRotatesOnTheNextEvent() throws Exception {
        File root = tmp("capfull");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            seed(f, CAP);
            assertEquals("AUDIT_OK", tapOk(log(dir)));
            eq("rotated", CAP, new File(dir, McpAuditLog.ROTATED_NAME).length());
            eq("active", LINE_BYTES, f.length());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void onlyOnePreviousGenerationIsKeptAndItIsReplaced() throws Exception {
        File root = tmp("onegen");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            File old = new File(dir, McpAuditLog.ROTATED_NAME);
            seed(old, 77);
            seed(f, CAP);
            assertEquals("AUDIT_OK", tapOk(log(dir)));
            eq("old generation replaced", CAP, old.length());
            seed(f, CAP);
            assertEquals("AUDIT_OK", tapOk(log(dir)));
            Set<String> names = new HashSet<>(Arrays.asList(dir.list()));
            assertEquals(new HashSet<>(Arrays.asList(McpAuditLog.FILE_NAME, McpAuditLog.ROTATED_NAME)), names);
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void restartResumesFromTheSizeOnDisk() throws Exception {
        File root = tmp("restart");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            McpAuditLog first = log(dir);
            for (int i = 0; i < 10; i++) assertEquals("AUDIT_OK", tapOk(first));
            long before = f.length();
            McpAuditLog second = log(dir);                   // "restart"
            assertEquals("AUDIT_OK", tapOk(second));
            eq("appended, not truncated", before + LINE_BYTES, f.length());
            seed(f, CAP - 1);
            assertEquals("AUDIT_OK", tapOk(log(dir)));       // another restart, near the cap
            eq("rotated on the persisted size", CAP - 1, new File(dir, McpAuditLog.ROTATED_NAME).length());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void symlinkedPreviousGenerationIsRefusedNotReplaced() throws Exception {
        File root = tmp("symrot");
        try {
            File dir = auditDir(root);
            File victim = new File(root, "victim.txt");
            Files.write(victim.toPath(), "untouched".getBytes(StandardCharsets.UTF_8));
            File old = new File(dir, McpAuditLog.ROTATED_NAME);
            Files.createSymbolicLink(old.toPath(), victim.toPath());
            File f = new File(dir, McpAuditLog.FILE_NAME);
            seed(f, CAP);
            assertEquals("AUDIT_ROTATED_UNSAFE", tapOk(log(dir)));
            assertEquals("untouched", new String(readAll(victim), StandardCharsets.UTF_8));
            assertTrue(Files.isSymbolicLink(old.toPath()));
            eq("active not grown or rotated", CAP, f.length());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void failedRenameKeepsTheCapAndSuspendsWithAFixedCode() throws Exception {
        File root = tmp("renamefail");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            seed(f, CAP);
            final boolean[] failing = {true};
            McpAuditLog l = new McpAuditLog(dir, FIXED_CLOCK, new McpAuditLog.Hooks() {
                @Override public void move(Path a, Path b) throws IOException {
                    if (failing[0]) throw new IOException("rename: " + SENTINEL_ERR);
                    REAL.move(a, b);
                }
                @Override public void setPermissions(Path p, Set<PosixFilePermission> m) throws IOException { REAL.setPermissions(p, m); }
                @Override public void write(FileChannel c, ByteBuffer d) throws IOException { REAL.write(c, d); }
            });
            for (int i = 0; i < 5; i++) assertEquals("AUDIT_ROTATE_FAILED", tapOk(l));
            eq("cap respected", CAP, f.length());
            assertFalse(new File(dir, McpAuditLog.ROTATED_NAME).exists());
            failing[0] = false;                              // recovers, still no old writer
            assertEquals("AUDIT_OK", tapOk(l));
            eq("rotated after recovery", CAP, new File(dir, McpAuditLog.ROTATED_NAME).length());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void unmovablePreviousGenerationSuspendsWithoutGrowth() throws Exception {
        File root = tmp("dirgen");
        try {
            File dir = auditDir(root);
            File old = new File(dir, McpAuditLog.ROTATED_NAME);
            assertTrue(old.mkdir());
            assertTrue(new File(old, "keep").createNewFile());
            File f = new File(dir, McpAuditLog.FILE_NAME);
            seed(f, CAP);
            assertEquals("AUDIT_ROTATED_UNSAFE", tapOk(log(dir)));
            eq("cap respected", CAP, f.length());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void simulatedNoSpaceLeavesNoPartialLineAndIsReported() throws Exception {
        File root = tmp("enospc");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            McpAuditLog healthy = log(dir);
            assertEquals("AUDIT_OK", tapOk(healthy));
            byte[] before = readAll(f);
            McpAuditLog l = new McpAuditLog(dir, FIXED_CLOCK, new McpAuditLog.Hooks() {
                @Override public void move(Path a, Path b) throws IOException { REAL.move(a, b); }
                @Override public void setPermissions(Path p, Set<PosixFilePermission> m) throws IOException { REAL.setPermissions(p, m); }
                @Override public void write(FileChannel c, ByteBuffer d) throws IOException {
                    ByteBuffer half = d.duplicate();
                    half.limit(half.position() + 3);
                    c.write(half);                           // a partial write, then the disk is full
                    throw new IOException("No space left on device " + SENTINEL_ERR);
                }
            });
            assertEquals("AUDIT_WRITE_FAILED", tapOk(l));
            assertEquals("AUDIT_WRITE_FAILED", l.status());
            assertTrue("no partial line", Arrays.equals(before, readAll(f)));
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void legacyAuditFileIsNeverTouched() throws Exception {
        File root = tmp("legacy");
        try {
            File dir = auditDir(root);
            File legacy = new File(dir, "mcp-audit.log");
            byte[] old = ("1\ttap\t" + SENTINEL_TEXT + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(legacy.toPath(), old);
            long mtime = legacy.lastModified();
            Files.setPosixFilePermissions(legacy.toPath(), PosixFilePermissions.fromString("rw-rw-r--"));
            McpAuditLog l = log(dir);
            assertEquals("AUDIT_OK", tapOk(l));
            seed(new File(dir, McpAuditLog.FILE_NAME), CAP);
            assertEquals("AUDIT_OK", tapOk(l));              // forces a rotation too
            assertTrue(Arrays.equals(old, readAll(legacy)));
            eq("mtime", mtime, legacy.lastModified());
            assertEquals(PosixFilePermissions.fromString("rw-rw-r--"), mode(legacy));
            assertFalse("v2 name differs from the legacy one", legacy.getName().equals(McpAuditLog.FILE_NAME));
        } finally {
            rmTree(root);
        }
    }

    // ---- concurrency ----------------------------------------------------------

    @Test
    public void concurrentAppendsKeepEveryLineWholeAndTheCapIntact() throws Exception {
        File root = tmp("concurrent");
        try {
            final File dir = auditDir(root);
            final McpAuditLog l = new McpAuditLog(dir);
            final int threads = 8;
            final int perThread = 6000;                      // > 2 caps worth of lines
            final CountDownLatch go = new CountDownLatch(1);
            final AtomicInteger notOk = new AtomicInteger();
            final AtomicInteger sizeViolations = new AtomicInteger();
            List<Thread> ts = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Thread th = new Thread(new Runnable() {
                    @Override public void run() {
                        try { go.await(); } catch (InterruptedException e) { return; }
                        for (int i = 0; i < perThread; i++) {
                            if (!"AUDIT_OK".equals(l.record("ui_snapshot", "{\"snapshot_id\":\"" + SENTINEL_TEXT + "\"}"))) {
                                notOk.incrementAndGet();
                            }
                            if (new File(dir, McpAuditLog.FILE_NAME).length() > CAP) sizeViolations.incrementAndGet();
                        }
                    }
                });
                ts.add(th);
                th.start();
            }
            go.countDown();
            for (Thread th : ts) th.join();
            eq("no failed append", 0, notOk.get());
            eq("never above the cap", 0, sizeViolations.get());
            for (String name : new String[] {McpAuditLog.FILE_NAME, McpAuditLog.ROTATED_NAME}) {
                File f = new File(dir, name);
                assertTrue(name + " exists", f.exists());
                assertTrue(name + " <= cap", f.length() <= CAP);
                String body = new String(readAll(f), StandardCharsets.UTF_8);
                assertTrue(body.endsWith("\n"));
                for (String line : body.split("\n")) assertTrue("whole line: " + line, LINE.matcher(line).matches());
                assertFalse(body.contains(SENTINEL_TEXT));
            }
        } finally {
            rmTree(root);
        }
    }

    // ---- previous generation is qualified too ------------------------------------

    private static McpAuditLog failingPerms(File dir) {
        return new McpAuditLog(dir, FIXED_CLOCK, new McpAuditLog.Hooks() {
            @Override public void move(Path a, Path b) throws IOException { REAL.move(a, b); }
            @Override public void setPermissions(Path p, Set<PosixFilePermission> m) throws IOException {
                throw new IOException("denied");
            }
            @Override public void write(FileChannel c, ByteBuffer d) throws IOException { REAL.write(c, d); }
        });
    }

    @Test
    public void previousGenerationAtTheCapIsAccepted() throws Exception {
        File root = tmp("rotcap");
        try {
            File dir = auditDir(root);
            seed(new File(dir, McpAuditLog.ROTATED_NAME), CAP);
            assertEquals("AUDIT_OK", tapOk(log(dir)));
            assertEquals("AUDIT_OK", log(dir).probe());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void oversizedPreviousGenerationSuspendsAppendProbeAndRestart() throws Exception {
        File root = tmp("rotbig");
        try {
            File dir = auditDir(root);
            File old = new File(dir, McpAuditLog.ROTATED_NAME);
            seed(old, CAP + 1);
            Files.setPosixFilePermissions(old.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
            assertEquals("AUDIT_ROTATED_UNSAFE", tapOk(log(dir)));
            assertEquals("AUDIT_ROTATED_UNSAFE", log(dir).probe());          // restart
            assertEquals("AUDIT_ROTATED_UNSAFE", tapOk(new McpAuditLog(dir)));
            eq("untouched", CAP + 1, old.length());
            assertFalse("no event written", new File(dir, McpAuditLog.FILE_NAME).length() > 0);
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void widePreviousGenerationIsTightenedAndVerified() throws Exception {
        File root = tmp("rotmode");
        try {
            File dir = auditDir(root);
            File old = new File(dir, McpAuditLog.ROTATED_NAME);
            seed(old, 90);
            Files.setPosixFilePermissions(old.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
            assertEquals("AUDIT_OK", tapOk(log(dir)));
            assertEquals(PosixFilePermissions.fromString("rw-------"), mode(old));
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void previousGenerationModeThatCannotBeFixedSuspends() throws Exception {
        File root = tmp("rotnofix");
        try {
            File dir = auditDir(root);
            File old = new File(dir, McpAuditLog.ROTATED_NAME);
            seed(old, 90);
            Files.setPosixFilePermissions(old.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
            assertEquals("AUDIT_ROTATED_UNSAFE", tapOk(failingPerms(dir)));
            assertEquals("AUDIT_ROTATED_UNSAFE", failingPerms(dir).probe());
            assertFalse(new File(dir, McpAuditLog.FILE_NAME).length() > 0);
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void directoryOrDanglingSymlinkAsPreviousGenerationSuspends() throws Exception {
        File root = tmp("rotkinds");
        try {
            File dir = auditDir(root);
            File old = new File(dir, McpAuditLog.ROTATED_NAME);
            assertTrue(old.mkdir());
            assertEquals("AUDIT_ROTATED_UNSAFE", tapOk(log(dir)));
            assertEquals("AUDIT_ROTATED_UNSAFE", log(dir).probe());
            assertTrue(old.delete());
            File target = new File(root, "not-yet.txt");
            Files.createSymbolicLink(old.toPath(), target.toPath());
            assertEquals("AUDIT_ROTATED_UNSAFE", tapOk(log(dir)));
            assertEquals("AUDIT_ROTATED_UNSAFE", log(dir).probe());
            assertFalse(target.exists());
        } finally {
            rmTree(root);
        }
    }

    // ---- a broken tail is never reused as healthy -----------------------------------

    /** Writes four bytes of the line, then breaks the channel so the rollback fails too. */
    private static McpAuditLog.Hooks partialThenBrokenRollback(final boolean[] armed) {
        return new McpAuditLog.Hooks() {
            @Override public void move(Path a, Path b) throws IOException { REAL.move(a, b); }
            @Override public void setPermissions(Path p, Set<PosixFilePermission> m) throws IOException { REAL.setPermissions(p, m); }
            @Override public void write(FileChannel c, ByteBuffer d) throws IOException {
                if (armed[0]) {
                    armed[0] = false;
                    ByteBuffer part = d.duplicate();
                    part.limit(part.position() + 4);
                    while (part.hasRemaining()) c.write(part);
                    c.close();                                // the rollback truncate will fail
                    throw new IOException("synthetic partial write " + SENTINEL_ERR);
                }
                REAL.write(c, d);
            }
        };
    }

    @Test
    public void failedRollbackIsRepairedBeforeTheNextAppend() throws Exception {
        File root = tmp("tail1");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            assertEquals("AUDIT_OK", tapOk(log(dir)));
            McpAuditLog l = new McpAuditLog(dir, FIXED_CLOCK, partialThenBrokenRollback(new boolean[] {true}));
            assertEquals("AUDIT_WRITE_FAILED", tapOk(l));
            assertTrue("a broken tail is on disk", readAll(f).length > LINE_BYTES);
            assertEquals("AUDIT_OK", tapOk(l));                    // repaired, then appended
            assertWholeLines(f);
            eq("two whole events, no joined line", 2 * LINE_BYTES, f.length());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void brokenTailIsRepairedAcrossARestartByProbeAndAppend() throws Exception {
        File root = tmp("tail2");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            assertEquals("AUDIT_OK", tapOk(log(dir)));
            McpAuditLog l = new McpAuditLog(dir, FIXED_CLOCK, partialThenBrokenRollback(new boolean[] {true}));
            assertEquals("AUDIT_WRITE_FAILED", tapOk(l));
            McpAuditLog restarted = log(dir);
            assertEquals("AUDIT_OK", restarted.probe());
            assertWholeLines(f);
            eq("partial bytes cut back to the last whole line", LINE_BYTES, f.length());
            assertEquals("AUDIT_OK", tapOk(restarted));
            assertWholeLines(f);
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void failureDuringRepairSuspendsThenResumes() throws Exception {
        File root = tmp("tail3");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            write(f, "1\ttap\tOK\n1\tta");
            final boolean[] truncateFails = {true};
            McpAuditLog.Hooks h = new McpAuditLog.Hooks() {
                @Override public void move(Path a, Path b) throws IOException { REAL.move(a, b); }
                @Override public void setPermissions(Path p, Set<PosixFilePermission> m) throws IOException { REAL.setPermissions(p, m); }
                @Override public void write(FileChannel c, ByteBuffer d) throws IOException { REAL.write(c, d); }
                @Override public void truncate(FileChannel c, long size) throws IOException {
                    if (truncateFails[0]) throw new IOException("truncate: " + SENTINEL_ERR);
                    c.truncate(size);
                }
            };
            byte[] before = readAll(f);
            assertEquals("AUDIT_TAIL_CORRUPT", tapOk(new McpAuditLog(dir, FIXED_CLOCK, h)));
            assertEquals("AUDIT_TAIL_CORRUPT", new McpAuditLog(dir, FIXED_CLOCK, h).probe());   // restart
            assertTrue("file untouched while suspended", Arrays.equals(before, readAll(f)));
            truncateFails[0] = false;
            assertEquals("AUDIT_OK", tapOk(new McpAuditLog(dir, FIXED_CLOCK, h)));
            assertWholeLines(f);
            eq("kept the whole first line, dropped the stub, added one", 2 * LINE_BYTES, f.length());
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void outOfSchemaLastLineSuspendsAndIsNeverRotatedAsHealthy() throws Exception {
        File root = tmp("tail4");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            byte[] bad = "1\tta1\ttap\tOK\n".getBytes(StandardCharsets.UTF_8);   // four fields, as in the probe
            seed(f, CAP - bad.length);
            byte[] ok = readAll(f);
            byte[] all = new byte[ok.length + bad.length];
            System.arraycopy(ok, 0, all, 0, ok.length);
            System.arraycopy(bad, 0, all, ok.length, bad.length);
            Files.write(f.toPath(), all);                   // exactly the cap: the next event would rotate
            assertEquals("AUDIT_TAIL_CORRUPT", tapOk(log(dir)));
            assertEquals("AUDIT_TAIL_CORRUPT", log(dir).probe());
            assertFalse("not rotated into a healthy generation", new File(dir, McpAuditLog.ROTATED_NAME).exists());
            assertTrue(Arrays.equals(all, readAll(f)));
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void unterminatedTailBeyondAnyLegalLineIsRefusedNotCut() throws Exception {
        File root = tmp("tail5");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            StringBuilder sb = new StringBuilder("1\ttap\tOK\n");
            for (int i = 0; i < 600; i++) sb.append('x');
            write(f, sb.toString());
            byte[] before = readAll(f);
            assertEquals("AUDIT_TAIL_CORRUPT", tapOk(log(dir)));
            assertTrue(Arrays.equals(before, readAll(f)));
        } finally {
            rmTree(root);
        }
    }

    @Test
    public void partialTailWithNoWholeLineBeforeItIsCutToEmpty() throws Exception {
        File root = tmp("tail6");
        try {
            File dir = auditDir(root);
            File f = new File(dir, McpAuditLog.FILE_NAME);
            write(f, "1\tta");
            assertEquals("AUDIT_OK", tapOk(log(dir)));
            eq("only the new event", LINE_BYTES, f.length());
            assertWholeLines(f);
        } finally {
            rmTree(root);
        }
    }

    // ---- probe ----------------------------------------------------------------

    @Test
    public void probeChecksWithoutAppendingAnEvent() throws Exception {
        File root = tmp("probe");
        try {
            File dir = auditDir(root);
            McpAuditLog l = log(dir);
            assertEquals("AUDIT_NOT_STARTED", l.status());
            assertEquals("AUDIT_OK", l.probe());
            eq("no event", 0, new File(dir, McpAuditLog.FILE_NAME).length());
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"));
        } finally {
            rmTree(root);
        }
    }
}
