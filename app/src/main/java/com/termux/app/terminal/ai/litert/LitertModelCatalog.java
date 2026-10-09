package com.termux.app.terminal.ai.litert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The models on disk: regular {@code .litertlm} files under one directory in the Termux home, never inside the
 * APK, at the top level or in up to two levels of folders (id {@code folder/name}). Listing does not open or hash a
 * file. A name that escapes the directory (traversal, a symlink to somewhere else) is rejected, and a symlinked
 * folder is reported, not followed.
 */
public final class LitertModelCatalog {
    public static final String EXTENSION = ".litertlm";
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    /** Two levels of folders and the file name. */
    static final int MAX_SEGMENTS = 3;

    private final File root;

    public LitertModelCatalog(File root) { this.root = root; }

    public File root() { return root; }

    /** The listing: {@code models} (id, path, size) plus {@code problems} for files and folders that were skipped. */
    public JSONObject list() throws JSONException {
        JSONArray models = new JSONArray();
        JSONArray problems = new JSONArray();
        boolean exists = root.isDirectory();
        if (exists) scan(root, "", 0, models, problems);
        return new JSONObject().put("directory", root.getPath()).put("directory_exists", exists)
            .put("models", models).put("problems", problems);
    }

    /** One folder: its models, then its sub-folders, in name order. {@code prefix} is the folder's id prefix. */
    private void scan(File dir, String prefix, int depth, JSONArray models, JSONArray problems) throws JSONException {
        File[] files = dir.listFiles();
        if (files == null) {
            problems.put(problem(prefix.isEmpty() ? root.getName() : prefix.substring(0, prefix.length() - 1), "the directory cannot be read"));
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            String name = file.getName();
            String relative = prefix + name;
            if (file.isDirectory() && !name.endsWith(EXTENSION)) {
                if (Files.isSymbolicLink(file.toPath())) {
                    problems.put(problem(relative, "a symlink to a folder is not followed"));
                } else if (depth >= MAX_SEGMENTS - 1) {
                    problems.put(problem(relative, "the folder is nested deeper than " + (MAX_SEGMENTS - 1) + " levels"));
                } else if (!SEGMENT.matcher(name).matches()) {
                    problems.put(problem(relative, "the folder name is not valid in a model id"));
                } else {
                    scan(file, relative + "/", depth + 1, models, problems);
                }
                continue;
            }
            if (!name.endsWith(EXTENSION)) continue;
            String id = relative.substring(0, relative.length() - EXTENSION.length());
            try {
                File canonical = check(id, file);
                models.put(new JSONObject().put("id", id).put("path", canonical.getPath()).put("size_bytes", canonical.length()));
            } catch (LitertFailure failure) {
                problems.put(problem(relative, failure.getMessage()));
            }
        }
    }

    /** A model id: one to three segments of letters, digits and . _ - joined by "/"; no segment starts with a dot. */
    static boolean validId(String id) {
        if (id == null) return false;
        String[] parts = id.split("/", -1);
        if (parts.length > MAX_SEGMENTS) return false;
        for (String part : parts) if (!SEGMENT.matcher(part).matches()) return false;
        return true;
    }

    /** The canonical file of a model id, or a {@link LitertFailure}. */
    public File resolve(String id) throws LitertFailure {
        if (!validId(id)) {
            throw LitertFailure.invalid("model must be an id such as qwen3-0.6b or folder/qwen3-0.6b (letters, digits . _ -; up to "
                + (MAX_SEGMENTS - 1) + " folders)");
        }
        File file = new File(root, id + EXTENSION);
        if (!file.exists()) {
            throw new LitertFailure(LitertErrorCode.MODEL_NOT_FOUND, null, "catalog", id,
                "no model " + id + EXTENSION + " in " + root.getPath(), null);
        }
        return check(id, file);
    }

    private File check(String id, File file) throws LitertFailure {
        if (!validId(id)) throw invalidModel(id, "the file name is not a valid model id");
        File canonical;
        File canonicalRoot;
        try {
            canonical = file.getCanonicalFile();
            canonicalRoot = root.getCanonicalFile();
        } catch (IOException e) {
            throw new LitertFailure(LitertErrorCode.MODEL_INVALID, null, "catalog", id, "cannot resolve the model path: " + e.getMessage(), e);
        }
        if (!canonical.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw invalidModel(id, "the model resolves outside " + root.getPath());
        }
        if (!canonical.isFile()) throw invalidModel(id, "the model is not a regular file");
        if (!canonical.canRead()) throw invalidModel(id, "the model is not readable");
        if (canonical.length() == 0) throw invalidModel(id, "the model file is empty");
        return canonical;
    }

    private static LitertFailure invalidModel(String id, String message) {
        return new LitertFailure(LitertErrorCode.MODEL_INVALID, null, "catalog", id, message, null);
    }

    private static JSONObject problem(String file, String message) throws JSONException {
        return new JSONObject().put("file", file).put("problem", message);
    }
}
