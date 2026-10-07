package com.termux.app.terminal.ai.litert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The models on disk: regular {@code .litertlm} files under one directory in the Termux home, never inside the
 * APK. Listing does not open or hash a file. A name that escapes the directory (traversal, a symlink to
 * somewhere else) is rejected.
 */
public final class LitertModelCatalog {
    public static final String EXTENSION = ".litertlm";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final File root;

    public LitertModelCatalog(File root) { this.root = root; }

    public File root() { return root; }

    /** The listing: {@code models} (id, path, size) plus {@code problems} for files that were skipped. */
    public JSONObject list() throws JSONException {
        JSONArray models = new JSONArray();
        JSONArray problems = new JSONArray();
        boolean exists = root.isDirectory();
        if (exists) {
            File[] files = root.listFiles();
            if (files == null) {
                problems.put(problem(root.getName(), "the directory cannot be read"));
                files = new File[0];
            }
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                String name = file.getName();
                if (!name.endsWith(EXTENSION)) continue;
                String id = name.substring(0, name.length() - EXTENSION.length());
                try {
                    File canonical = check(id, file);
                    models.put(new JSONObject().put("id", id).put("path", canonical.getPath()).put("size_bytes", canonical.length()));
                } catch (LitertFailure failure) {
                    problems.put(problem(name, failure.getMessage()));
                }
            }
        }
        return new JSONObject().put("directory", root.getPath()).put("directory_exists", exists)
            .put("models", models).put("problems", problems);
    }

    /** The canonical file of a model id, or a {@link LitertFailure}. */
    public File resolve(String id) throws LitertFailure {
        if (id == null || !ID.matcher(id).matches()) {
            throw LitertFailure.invalid("model must be an id such as qwen3-0.6b (letters, digits . _ -)");
        }
        File file = new File(root, id + EXTENSION);
        if (!file.exists()) {
            throw new LitertFailure(LitertErrorCode.MODEL_NOT_FOUND, null, "catalog", id,
                "no model " + id + EXTENSION + " in " + root.getPath(), null);
        }
        return check(id, file);
    }

    private File check(String id, File file) throws LitertFailure {
        if (!ID.matcher(id).matches()) throw invalidModel(id, "the file name is not a valid model id");
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
