package com.termux.app.mcp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The token of one MCP server epoch: generated, published to a private file and
 * verified before the server is allowed to accept a connection, then checked on
 * every {@code initialize}.
 *
 * <p>Contract (what this is and is not): the token is a <b>contract and a defence
 * against clients that were never provisioned</b> (a process that merely finds the
 * socket). It is <b>not</b> isolation between processes of the same uid: such a
 * process can read the owner-only token file like the legitimate shim does.
 *
 * <p>Token form: exactly {@value #TOKEN_CHARS} lowercase hex characters (32 random
 * bytes from {@link SecureRandom}); anything else is refused without comparing.
 * The comparison is over bytes with {@link MessageDigest#isEqual} (constant time),
 * never {@code equals}.
 *
 * <p>Epoch: one instance per server start. {@link #retire()} ends it (stop or
 * restart); from then on {@link #live()} is false and {@link #ok} refuses, so a
 * connection accepted under this epoch cannot keep acting, and the connections
 * registered with {@link #track} are closed.
 *
 * <p>Publication: the token is written to a fresh {@code 0600} temp file in the
 * same {@code 0700} directory, flushed, moved over the target atomically and read
 * back (regular file, exactly {@code 0600}, same bytes). Any failure leaves no token
 * file at all and an <b>unavailable</b> epoch: there is never a fallback that
 * accepts a connection without a token. Failures are reported as fixed
 * {@code AUTH_*} codes only; the token and exception text are never logged.
 *
 * <p>The token is never put in argv, a log, a message, a manifest or an exported
 * environment variable.
 */
final class McpAuth implements McpDispatch.AuthCheck {

    static final String TOKEN_FILE_NAME = "mcp.token";
    static final int TOKEN_BYTES = 32;
    static final int TOKEN_CHARS = TOKEN_BYTES * 2;

    // ---- fixed diagnostic codes (the only things ever reported) ----
    static final String AUTH_NOT_STARTED = "AUTH_NOT_STARTED";
    static final String AUTH_OK = "AUTH_OK";
    /** The directory is a symlink, not a directory, or cannot be made owner-only. */
    static final String AUTH_DIR_UNSAFE = "AUTH_DIR_UNSAFE";
    /** The platform cannot report or set POSIX modes: fail closed. */
    static final String AUTH_PERM_UNVERIFIABLE = "AUTH_PERM_UNVERIFIABLE";
    /** Creating, writing or moving the token file failed. */
    static final String AUTH_PUBLISH_FAILED = "AUTH_PUBLISH_FAILED";
    /** The published file is not the token that was generated, or not owner-only. */
    static final String AUTH_VERIFY_FAILED = "AUTH_VERIFY_FAILED";

    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR_MODE = PosixFilePermissions.fromString("rwx------");
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Filesystem mutations that tests need to fail on purpose. */
    interface Hooks {
        void write(FileChannel channel, ByteBuffer data) throws IOException;
        void move(Path from, Path to) throws IOException;
        void setPermissions(Path path, Set<PosixFilePermission> mode) throws IOException;
    }

    static final Hooks REAL_HOOKS = new Hooks() {
        @Override public void write(FileChannel channel, ByteBuffer data) throws IOException {
            while (data.hasRemaining()) channel.write(data);
        }
        @Override public void move(Path from, Path to) throws IOException {
            // rename(2): replaces an existing file or symlink itself, never follows it.
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        }
        @Override public void setPermissions(Path path, Set<PosixFilePermission> mode) throws IOException {
            Files.setPosixFilePermissions(path, mode);
        }
    };

    /** Raised inside publication with the fixed code to report. */
    private static final class Fail extends Exception {
        final String code;
        Fail(String code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    private final String status;
    /** The expected token bytes; null for an unavailable epoch. Never logged. */
    private final byte[] expected;
    private volatile boolean live;
    private final Object lock = new Object();
    private final List<Runnable> closers = new ArrayList<>();

    private McpAuth(String status, byte[] expected) {
        this.status = status;
        this.expected = expected;
        this.live = expected != null;
    }

    /** An epoch that refuses everything (nothing was published). */
    static McpAuth unavailable(String status) {
        return new McpAuth(status, null);
    }

    static McpAuth publish(java.io.File dir, String fileName) {
        return publish(dir, fileName, REAL_HOOKS);
    }

    /**
     * Generates a token, publishes it and verifies the publication. Returns an
     * unavailable epoch (and leaves no token file) on any failure.
     */
    static McpAuth publish(java.io.File dir, String fileName, Hooks hooks) {
        Path dirPath = dir.toPath();
        Path target = dirPath.resolve(fileName);
        Path tmp = dirPath.resolve("." + fileName + ".new-" + randomSuffix());
        // An unsafe directory is never touched, not even to delete: it may point elsewhere.
        try {
            ensurePrivateDir(dirPath, hooks);
        } catch (Fail f) {
            return unavailable(f.code);
        } catch (UnsupportedOperationException e) {
            return unavailable(AUTH_PERM_UNVERIFIABLE);
        } catch (IOException | RuntimeException e) {
            return unavailable(AUTH_DIR_UNSAFE);
        }
        byte[] token = newTokenBytes();
        try {
            writeTemp(tmp, token, hooks);
            hooks.move(tmp, target);
            verifyPublished(target, token);
            return new McpAuth(AUTH_OK, token);
        } catch (Fail f) {
            cleanup(tmp, target);
            return unavailable(f.code);
        } catch (UnsupportedOperationException e) {
            cleanup(tmp, target);
            return unavailable(AUTH_PERM_UNVERIFIABLE);
        } catch (IOException | RuntimeException e) {
            cleanup(tmp, target);
            return unavailable(AUTH_PUBLISH_FAILED);
        }
    }

    private static void ensurePrivateDir(Path dir, Hooks hooks) throws IOException, Fail {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir);
        }
        BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isSymbolicLink()) throw new Fail(AUTH_DIR_UNSAFE);
        if (!hasMode(dir, DIR_MODE)) {
            try {
                hooks.setPermissions(dir, DIR_MODE);
            } catch (IOException e) {
                throw new Fail(AUTH_DIR_UNSAFE);
            }
            if (!hasMode(dir, DIR_MODE)) {
                throw new Fail(AUTH_DIR_UNSAFE);
            }
        }
    }

    private static void writeTemp(Path tmp, byte[] token, Hooks hooks) throws IOException, Fail {
        // CREATE_NEW semantics: never reuses or follows anything that already exists.
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(FILE_MODE));
        hooks.setPermissions(tmp, FILE_MODE);
        if (!hasMode(tmp, FILE_MODE)) {
            throw new Fail(AUTH_PERM_UNVERIFIABLE);
        }
        OpenOption[] opts = {StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS};
        try (FileChannel ch = FileChannel.open(tmp, opts)) {
            hooks.write(ch, ByteBuffer.wrap(token));
            ch.force(true);
        }
    }

    private static void verifyPublished(Path target, byte[] token) throws IOException, Fail {
        BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.size() != TOKEN_CHARS) {
            throw new Fail(AUTH_VERIFY_FAILED);
        }
        if (!hasMode(target, FILE_MODE)) throw new Fail(AUTH_VERIFY_FAILED);
        byte[] back = Files.readAllBytes(target);
        if (!MessageDigest.isEqual(back, token)) throw new Fail(AUTH_VERIFY_FAILED);
    }

    /** True when the file (not following a final symlink) has exactly this POSIX mode. */
    private static boolean hasMode(Path path, Set<PosixFilePermission> mode) throws IOException {
        return mode.equals(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    private static void cleanup(Path tmp, Path target) {
        try { Files.deleteIfExists(tmp); } catch (IOException | RuntimeException ignored) { }
        // A token left behind by an earlier epoch must not outlive a failed publication.
        try { Files.deleteIfExists(target); } catch (IOException | RuntimeException ignored) { }
    }

    private static byte[] newTokenBytes() {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        byte[] chars = new byte[TOKEN_CHARS];
        for (int i = 0; i < TOKEN_BYTES; i++) {
            chars[2 * i] = (byte) HEX[(raw[i] >> 4) & 0x0f];
            chars[2 * i + 1] = (byte) HEX[raw[i] & 0x0f];
        }
        java.util.Arrays.fill(raw, (byte) 0);
        return chars;
    }

    private static String randomSuffix() {
        byte[] raw = new byte[6];
        RANDOM.nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(HEX[(b >> 4) & 0x0f]).append(HEX[b & 0x0f]);
        return sb.toString();
    }

    // ---- the epoch ----

    /** One of the fixed {@code AUTH_*} codes. */
    String status() {
        return status;
    }

    /** True when a token was published and verified (the epoch may have been retired since). */
    boolean available() {
        return expected != null;
    }

    @Override
    public boolean live() {
        return live;
    }

    /**
     * True only for this epoch's token, and only while the epoch is live. Any other
     * form (null, wrong length, not lowercase hex) is refused before comparing.
     */
    @Override
    public boolean ok(String presented) {
        byte[] want = expected;
        if (!live || want == null) return false;
        if (presented == null || presented.length() != TOKEN_CHARS) return false;
        byte[] got = new byte[TOKEN_CHARS];
        for (int i = 0; i < TOKEN_CHARS; i++) {
            char c = presented.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
            got[i] = (byte) c;
        }
        return MessageDigest.isEqual(got, want);
    }

    /**
     * Registers an action that closes a connection of this epoch. Returns the
     * action that unregisters it. When the epoch is already over the closer runs
     * immediately.
     */
    Runnable track(Runnable closer) {
        boolean runNow;
        synchronized (lock) {
            runNow = !live;
            if (!runNow) closers.add(closer);
        }
        if (runNow) runQuietly(closer);
        return new Runnable() {
            @Override public void run() {
                synchronized (lock) { closers.remove(closer); }
            }
        };
    }

    /** Ends the epoch (stop or restart): the token stops working and the connections are closed. */
    void retire() {
        List<Runnable> toClose;
        synchronized (lock) {
            live = false;
            toClose = new ArrayList<>(closers);
            closers.clear();
        }
        for (Runnable r : toClose) runQuietly(r);
    }

    private static void runQuietly(Runnable r) {
        try { r.run(); } catch (RuntimeException ignored) { }
    }
}
