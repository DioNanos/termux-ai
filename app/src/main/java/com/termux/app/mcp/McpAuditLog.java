package com.termux.app.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;

/**
 * Content-free, size-bounded audit log of the MCP tool calls (v2 schema).
 *
 * <p>The log never carries content: no arguments, no results, no UI text, no node
 * or snapshot ids, no package names, no tokens, no frames, no paths, no raw
 * exception text and no hashes of any of those. A line is exactly
 * <pre>{@code <epoch-ms> TAB <tool> TAB <outcome> LF}</pre>
 * where {@code tool} comes from a closed allowlist (anything else is written as
 * {@code unknown}) and {@code outcome} comes from a closed vocabulary. There is
 * no debug mode that widens this schema.
 *
 * <p>Storage: {@value #FILE_NAME} (mode 0600) in a 0700 directory, rotated to
 * {@value #ROTATED_NAME} before an append that would push the active file past
 * {@value #MAX_FILE_BYTES} bytes, under the same lock as the append. The legacy
 * {@code mcp-audit.log} is a different name and is never opened, read, rotated
 * or deleted by this class.
 *
 * <p>Failure model: every problem maps to a fixed status code returned by
 * {@link #record} and {@link #status()}; the event that could not be written is
 * dropped (never reported as written), the size cap is never exceeded and there
 * is no fallback to another writer. Nothing here logs payload anywhere.
 *
 * <p>Out of scope (same-uid model): a process with the app's uid can still
 * rewrite or delete these files; the cap is a storage bound, not forensic
 * integrity.
 */
final class McpAuditLog {

    static final String FILE_NAME = "mcp-audit-v2.log";
    static final String ROTATED_NAME = "mcp-audit-v2.log.1";
    static final long MAX_FILE_BYTES = 256L * 1024L;
    static final int MAX_LINE_BYTES = 512;

    // ---- fixed diagnostic codes (the only things ever reported about failures) ----
    static final String AUDIT_NOT_STARTED = "AUDIT_NOT_STARTED";
    static final String AUDIT_OK = "AUDIT_OK";
    /** Directory is a symlink, not a directory, or its mode cannot be made 0700. */
    static final String AUDIT_DIR_UNSAFE = "AUDIT_DIR_UNSAFE";
    /** Active file is a symlink/anomalous/oversized or its mode cannot be made 0600. */
    static final String AUDIT_FILE_UNSAFE = "AUDIT_FILE_UNSAFE";
    /** The platform cannot report or set POSIX modes: fail closed, do not write. */
    /** The previous generation is a symlink/anomalous/oversized or its mode cannot be made 0600. */
    static final String AUDIT_ROTATED_UNSAFE = "AUDIT_ROTATED_UNSAFE";
    /** The active file ends in a broken line that could not be cut back to the last whole line. */
    static final String AUDIT_TAIL_CORRUPT = "AUDIT_TAIL_CORRUPT";
    static final String AUDIT_PERM_UNVERIFIABLE = "AUDIT_PERM_UNVERIFIABLE";
    static final String AUDIT_ROTATE_FAILED = "AUDIT_ROTATE_FAILED";
    static final String AUDIT_WRITE_FAILED = "AUDIT_WRITE_FAILED";
    static final String AUDIT_LINE_REJECTED = "AUDIT_LINE_REJECTED";

    // ---- closed vocabularies ----
    static final String TOOL_UNKNOWN = "unknown";
    private static final String[] TOOLS = {
        "accessibility_status", "ui_snapshot", "tap", "type", "launch_app", "wake"
    };

    static final String OUTCOME_OK = "OK";
    static final String OUTCOME_ERROR = "ERROR";
    static final String OUTCOME_EXCEPTION = "EXCEPTION";
    private static final String[] ERROR_CODES = {
        "SERVICE_OFF", "POLICY_DENIED", "UNKNOWN_TOOL", "STALE_REF", "NODE_NOT_FOUND",
        "ACTION_UNSUPPORTED", "GESTURE_FAILED", "NO_ACTIVE_WINDOW", "PACKAGE_NOT_FOUND",
        "LAUNCH_FAILED", "WAKE_FAILED"
    };

    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR_MODE = PosixFilePermissions.fromString("rwx------");

    /** Time source seam. */
    interface Clock {
        long nowMs();
    }

    /** Filesystem mutations that tests need to fail on purpose. */
    interface Hooks {
        void move(Path from, Path to) throws IOException;
        void setPermissions(Path path, Set<PosixFilePermission> mode) throws IOException;
        void write(FileChannel channel, ByteBuffer data) throws IOException;
        /** Rolls a failed append back, and cuts back a broken tail. */
        default void truncate(FileChannel channel, long size) throws IOException {
            channel.truncate(size);
        }
    }

    private static final Hooks REAL_HOOKS = new Hooks() {
        @Override public void move(Path from, Path to) throws IOException {
            // rename(2): replaces an existing target file or symlink itself, never follows it.
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        }
        @Override public void setPermissions(Path path, Set<PosixFilePermission> mode) throws IOException {
            Files.setPosixFilePermissions(path, mode);
        }
        @Override public void write(FileChannel channel, ByteBuffer data) throws IOException {
            while (data.hasRemaining()) channel.write(data);
        }
    };

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long nowMs() { return System.currentTimeMillis(); }
    };

    private final Path dir;
    private final Path active;
    private final Path rotated;
    private final Clock clock;
    private final Hooks hooks;
    private String status = AUDIT_NOT_STARTED;

    McpAuditLog(File directory) {
        this(directory, SYSTEM_CLOCK, REAL_HOOKS);
    }

    McpAuditLog(File directory, Clock clock, Hooks hooks) {
        this.dir = directory.toPath();
        this.active = dir.resolve(FILE_NAME);
        this.rotated = dir.resolve(ROTATED_NAME);
        this.clock = clock;
        this.hooks = hooks;
    }

    synchronized String status() {
        return status;
    }

    /** Records one finished tool call: only the label of the tool and of the outcome. */
    synchronized String record(String tool, String result) {
        String label = toolLabel(tool);
        return append(label, outcomeLabel(label, result));
    }

    /** Records a tool call that ended with an exception (its text is never kept). */
    synchronized String recordException(String tool) {
        return append(toolLabel(tool), OUTCOME_EXCEPTION);
    }

    /** Checks directory and active file without appending an event. */
    synchronized String probe() {
        return status = probeChecked();
    }

    // ---- labels -------------------------------------------------------------

    static String toolLabel(String name) {
        if (name != null) {
            for (String t : TOOLS) {
                if (t.equals(name)) return t;
            }
        }
        return TOOL_UNKNOWN;
    }

    /**
     * Maps a tool result onto the closed outcome vocabulary by exact comparison
     * with the known fixed result shapes; the result's content is never copied.
     */
    static String outcomeLabel(String tool, String result) {
        if (result == null) return OUTCOME_ERROR;
        for (String code : ERROR_CODES) {
            if (result.equals("{\"ok\":false,\"error\":\"" + code + "\"}")
                || result.equals("{\"error\":\"" + code + "\",\"ok\":false}")) {
                return code;
            }
        }
        if (result.equals("{\"ok\":true}")) return OUTCOME_OK;
        boolean dataTool = "ui_snapshot".equals(tool) || "accessibility_status".equals(tool);
        if (dataTool && !result.startsWith("{\"ok\":false") && !result.startsWith("{\"error\":")) {
            return OUTCOME_OK;
        }
        return OUTCOME_ERROR;
    }

    // ---- append / rotate ----------------------------------------------------

    private String append(String tool, String outcome) {
        byte[] line = (clock.nowMs() + "\t" + tool + "\t" + outcome + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.length > MAX_LINE_BYTES) return status = AUDIT_LINE_REJECTED;
        return status = appendLine(line);
    }

    /** Failure carrying only a fixed status code (no message, no cause, no stack). */
    private static final class Fail extends Exception {
        final String code;
        Fail(String code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    private String appendLine(byte[] line) {
        FileChannel ch = null;
        try {
            ch = openChecked();
            long size = ch.size();
            if (size + line.length > MAX_FILE_BYTES) {
                ch.close();
                ch = null;
                try {
                    hooks.move(active, rotated);
                } catch (IOException | RuntimeException e) {
                    return AUDIT_ROTATE_FAILED;
                }
                ch = openChecked();
                size = ch.size();
                if (size + line.length > MAX_FILE_BYTES) return AUDIT_FILE_UNSAFE;
            }
            try {
                hooks.write(ch, ByteBuffer.wrap(line));
            } catch (IOException | RuntimeException e) {
                try {
                    hooks.truncate(ch, size);
                } catch (IOException | RuntimeException ignored) {
                    // not attested: the next open finds the broken tail and repairs or refuses it
                }
                return AUDIT_WRITE_FAILED;
            }
            return AUDIT_OK;
        } catch (Fail f) {
            return f.code;
        } catch (IOException | RuntimeException e) {
            return AUDIT_WRITE_FAILED;
        } finally {
            closeQuietly(ch);
        }
    }

    /** Directory and active file checks without appending anything. */
    private String probeChecked() {
        FileChannel ch = null;
        try {
            ch = openChecked();
            return AUDIT_OK;
        } catch (Fail f) {
            return f.code;
        } finally {
            closeQuietly(ch);
        }
    }

    /**
     * Verifies the directory (0700, not a symlink) and the active file (plain
     * regular file, 0600, within the cap) and returns an append channel opened
     * without following symlinks. The caller closes it.
     */
    private FileChannel openChecked() throws Fail {
        try {
            checkDir();
            checkFile();
            checkRotated();
            Set<OpenOption> opts = new HashSet<>();
            opts.add(StandardOpenOption.CREATE);
            opts.add(StandardOpenOption.WRITE);
            opts.add(StandardOpenOption.APPEND);
            opts.add(LinkOption.NOFOLLOW_LINKS);
            FileChannel ch = FileChannel.open(active, opts, PosixFilePermissions.asFileAttribute(FILE_MODE));
            try {
                checkFile();
                if (ch.size() > MAX_FILE_BYTES) throw new Fail(AUDIT_FILE_UNSAFE);
                qualifyTail(ch);
                FileChannel out = ch;
                ch = null;
                return out;
            } finally {
                closeQuietly(ch);
            }
        } catch (UnsupportedOperationException | LinkageError e) {
            throw new Fail(AUDIT_PERM_UNVERIFIABLE);
        } catch (IOException | RuntimeException e) {
            throw new Fail(AUDIT_FILE_UNSAFE);
        }
    }

    private void checkDir() throws IOException, Fail {
        if (Files.isSymbolicLink(dir)) throw new Fail(AUDIT_DIR_UNSAFE);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_MODE));
        }
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new Fail(AUDIT_DIR_UNSAFE);
        }
        if (!hasMode(dir, DIR_MODE)) {
            try { hooks.setPermissions(dir, DIR_MODE); } catch (IOException | RuntimeException ignored) { }
            if (!hasMode(dir, DIR_MODE)) throw new Fail(AUDIT_DIR_UNSAFE);
        }
    }

    /** An existing active file must be a plain 0600 file; a missing one is fine. */
    private void checkFile() throws IOException, Fail {
        if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attrs = Files.readAttributes(active, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new Fail(AUDIT_FILE_UNSAFE);
        if (!hasMode(active, FILE_MODE)) {
            try { hooks.setPermissions(active, FILE_MODE); } catch (IOException | RuntimeException ignored) { }
            if (!hasMode(active, FILE_MODE)) throw new Fail(AUDIT_FILE_UNSAFE);
        }
    }

    /**
     * The previous generation, when present, gets the same qualification as the
     * active file: no-follow, plain regular file, within the cap, mode 0600
     * (repaired and re-read when wider). Anything else suspends the log: it is
     * never replaced or written over, and the pair is never reported OK.
     */
    private void checkRotated() throws IOException, Fail {
        if (!Files.exists(rotated, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attrs = Files.readAttributes(rotated, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.size() > MAX_FILE_BYTES) {
            throw new Fail(AUDIT_ROTATED_UNSAFE);
        }
        if (!hasMode(rotated, FILE_MODE)) {
            try { hooks.setPermissions(rotated, FILE_MODE); } catch (IOException | RuntimeException ignored) { }
            if (!hasMode(rotated, FILE_MODE)) throw new Fail(AUDIT_ROTATED_UNSAFE);
        }
    }

    /**
     * Qualifies the end of the active file before anything is appended. A file
     * must be empty or end in a whole, in-schema line. A trailing partial line
     * (an interrupted write whose rollback was not attested, possibly before a
     * restart) is cut back to the last LF; if that cannot be done, or the last
     * whole line is out of schema, the log is suspended. The state lives in the
     * file, so it survives a restart. Only the last line is inspected.
     */
    private void qualifyTail(FileChannel append) throws IOException, Fail {
        for (int attempt = 0; attempt < 2; attempt++) {
            long size = append.size();
            if (size == 0) return;
            byte[] w = readTail(size);
            int n = w.length;
            if (w[n - 1] == '\n') {
                int prev = lastLf(w, n - 2);
                if (prev < 0 && size > n) throw new Fail(AUDIT_TAIL_CORRUPT);
                if (isSchemaLine(w, prev + 1, n - 1)) return;
                throw new Fail(AUDIT_TAIL_CORRUPT);
            }
            if (attempt == 1) throw new Fail(AUDIT_TAIL_CORRUPT);
            int lf = lastLf(w, n - 1);
            long keep;
            if (lf >= 0) keep = size - n + lf + 1;
            else if (size == n) keep = 0;
            else throw new Fail(AUDIT_TAIL_CORRUPT);
            try {
                hooks.truncate(append, keep);
            } catch (IOException | RuntimeException e) {
                throw new Fail(AUDIT_TAIL_CORRUPT);
            }
        }
    }

    private byte[] readTail(long size) throws IOException {
        int n = (int) Math.min(size, MAX_LINE_BYTES + 1L);
        byte[] w = new byte[n];
        try (FileChannel rd = FileChannel.open(active, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer b = ByteBuffer.wrap(w);
            long pos = size - n;
            while (b.hasRemaining()) {
                int r = rd.read(b, pos + b.position());
                if (r < 0) throw new IOException("short read");
            }
        }
        return w;
    }

    private static int lastLf(byte[] w, int from) {
        for (int i = from; i >= 0; i--) {
            if (w[i] == '\n') return i;
        }
        return -1;
    }

    /** Exactly {@code <digits> TAB <tool> TAB <outcome>} in {@code w[from, to)}. */
    private static boolean isSchemaLine(byte[] w, int from, int to) {
        if (to - from < 5 || to - from > MAX_LINE_BYTES) return false;
        String line = new String(w, from, to - from, StandardCharsets.UTF_8);
        String[] f = line.split("\t", -1);
        if (f.length != 3) return false;
        if (f[0].isEmpty() || f[0].length() > 19) return false;
        for (int i = 0; i < f[0].length(); i++) {
            char c = f[0].charAt(i);
            if (c < '0' || c > '9') return false;
        }
        if (!TOOL_UNKNOWN.equals(f[1]) && !contains(TOOLS, f[1])) return false;
        return OUTCOME_OK.equals(f[2]) || OUTCOME_ERROR.equals(f[2]) || OUTCOME_EXCEPTION.equals(f[2])
            || contains(ERROR_CODES, f[2]);
    }

    private static boolean contains(String[] set, String v) {
        for (String x : set) {
            if (x.equals(v)) return true;
        }
        return false;
    }

    private static boolean hasMode(Path p, Set<PosixFilePermission> expected) throws IOException {
        return expected.equals(Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS));
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch == null) return;
        try { ch.close(); } catch (IOException ignored) { }
    }
}
