package com.termux.app.terminal.ai.litert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The settings of the local-model engine, in one small file of {@code key=value} lines. The {@code :litert}
 * process reads it when it starts (see {@link #describe}). A bad line never stops the engine: its key keeps the
 * default and the line is reported. Writing refuses a bad value and touches nothing.
 */
public final class LitertConfig {
    public static final String KEY_IDLE_UNLOAD_MS = "idle_unload_ms";
    /** The idle check runs every {@link LitertParams#IDLE_TICK_MS}: a shorter timeout would be a lie. */
    public static final long MIN_IDLE_UNLOAD_MS = LitertParams.IDLE_TICK_MS;
    public static final long MAX_IDLE_UNLOAD_MS = 86_400_000L;

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,12}");

    /** What the file says, with defaults for what it does not say or says badly. */
    public static final class Values {
        public long idleUnloadMs = LitertParams.DEFAULT_IDLE_UNLOAD_MS;
        public boolean fromFile;
        public final List<String> problems = new ArrayList<>();
    }

    private final File file;

    public LitertConfig(File file) { this.file = file; }

    public File file() { return file; }

    public Values load() {
        Values values = new Values();
        if (!file.isFile()) return values;
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            values.problems.add("the file cannot be read: " + e.getMessage());
            return values;
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) {
                values.problems.add("line " + (i + 1) + ": expected key=value");
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!KEY_IDLE_UNLOAD_MS.equals(key)) {
                values.problems.add("line " + (i + 1) + ": unknown key '" + key + "'");
                continue;
            }
            Long parsed = parseIdle(value);
            if (parsed == null) {
                values.problems.add("line " + (i + 1) + ": " + idleRule() + " (got '" + value + "')");
                continue;
            }
            values.idleUnloadMs = parsed;
            values.fromFile = true;
        }
        return values;
    }

    /** The settings in force, where the file is and when a change takes effect. */
    public JSONObject describe() throws JSONException {
        Values values = load();
        JSONArray problems = new JSONArray();
        for (String problem : values.problems) problems.put(problem);
        return new JSONObject()
            .put("file", file.getPath())
            .put("file_exists", file.isFile())
            .put(KEY_IDLE_UNLOAD_MS, values.idleUnloadMs)
            .put("source", values.fromFile ? "file" : "default")
            .put("problems", problems)
            .put("applies", "the :litert process reads the file when it starts: run litert restart to apply a change now");
    }

    /**
     * Writes the given keys into the file and returns {@link #describe()}. Every value is checked first; on any
     * error nothing is written.
     */
    public JSONObject set(JSONObject changes) throws LitertFailure, JSONException {
        long idle = -1;
        boolean hasIdle = false;
        for (Iterator<String> keys = changes.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!KEY_IDLE_UNLOAD_MS.equals(key)) throw LitertFailure.invalid("unknown setting '" + key + "' (only " + KEY_IDLE_UNLOAD_MS + ")");
            Object raw = changes.get(key);
            Long parsed = raw instanceof Number && ((Number) raw).doubleValue() == ((Number) raw).longValue()
                ? parseIdle(Long.toString(((Number) raw).longValue())) : null;
            if (parsed == null) throw LitertFailure.invalid(KEY_IDLE_UNLOAD_MS + ": " + idleRule());
            idle = parsed;
            hasIdle = true;
        }
        if (hasIdle) write(idle);
        return describe();
    }

    private void write(long idle) throws LitertFailure {
        String existing = "";
        try {
            if (file.isFile()) existing = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw failure("cannot read " + file.getPath(), e);
        }
        StringBuilder out = new StringBuilder();
        boolean replaced = false;
        for (String line : existing.isEmpty() ? new String[0] : existing.split("\n", -1)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (!trimmed.startsWith("#") && eq > 0 && KEY_IDLE_UNLOAD_MS.equals(trimmed.substring(0, eq).trim())) {
                if (!replaced) out.append(KEY_IDLE_UNLOAD_MS).append('=').append(idle).append('\n');
                replaced = true;
            } else if (!line.isEmpty() || out.length() > 0) {
                out.append(line).append('\n');
            }
        }
        if (!replaced) out.append(KEY_IDLE_UNLOAD_MS).append('=').append(idle).append('\n');
        File parent = file.getParentFile();
        try {
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("cannot create " + parent.getPath());
            }
            File temp = new File(parent, file.getName() + ".tmp");
            Files.write(temp.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw failure("cannot write " + file.getPath(), e);
        }
    }

    private static LitertFailure failure(String message, IOException e) {
        return new LitertFailure(LitertErrorCode.INVALID_ARGUMENT, null, "config", null, message + ": " + e.getMessage(), e);
    }

    private static Long parseIdle(String value) {
        if (!DIGITS.matcher(value).matches()) return null;
        long parsed = Long.parseLong(value);
        if (parsed == 0 || (parsed >= MIN_IDLE_UNLOAD_MS && parsed <= MAX_IDLE_UNLOAD_MS)) return parsed;
        return null;
    }

    private static String idleRule() {
        return KEY_IDLE_UNLOAD_MS + " must be 0 (off) or between " + MIN_IDLE_UNLOAD_MS + " and " + MAX_IDLE_UNLOAD_MS + " milliseconds";
    }
}
