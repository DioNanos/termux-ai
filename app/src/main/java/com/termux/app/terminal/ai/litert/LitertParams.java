package com.termux.app.terminal.ai.litert;

import org.json.JSONObject;

import java.util.regex.Pattern;

/** The validated arguments of {@code litert.generate}. Invalid values are rejected, never clamped. */
public final class LitertParams {
    public static final int DEFAULT_MAX_TOKENS = 256;
    public static final int MAX_MAX_TOKENS = 4096;
    public static final double DEFAULT_TEMPERATURE = 0.2;
    public static final double MAX_TEMPERATURE = 1.0;
    public static final int DEFAULT_TOP_K = 40;
    public static final int MAX_TOP_K = 1000;
    /** Fixed by the contract (the SDK needs one): reported back, not requested. */
    public static final double TOP_P = 1.0;
    public static final int SEED = 0;
    /** The context the engine is opened with; the output limit is separate. */
    public static final int CONTEXT_TOKENS = 4096;
    /** The request crosses a Binder transaction (about 1 MiB for the whole process): keep the prompt well inside it. */
    public static final int MAX_PROMPT_BYTES = 256 * 1024;

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public final String requestId;
    public final String model;
    public final LitertBackend backend;
    public final String prompt;
    public final int maxTokens;
    public final double temperature;
    public final int topK;

    private LitertParams(String requestId, String model, LitertBackend backend, String prompt,
                         int maxTokens, double temperature, int topK) {
        this.requestId = requestId;
        this.model = model;
        this.backend = backend;
        this.prompt = prompt;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.topK = topK;
    }

    public static LitertParams fromArgs(JSONObject args) throws LitertFailure {
        // AICore selectors do not exist here: the backend replaces them.
        if (args.has("stage") || args.has("preference")) {
            throw LitertFailure.invalid("stage and preference are AICore options; use backend (cpu, gpu or npu)");
        }
        String requestId = string(args, "request_id", true);
        if (!REQUEST_ID.matcher(requestId).matches()) {
            throw LitertFailure.invalid("request_id must be 1 to 64 characters of A-Z a-z 0-9 . _ -");
        }
        String model = string(args, "model", true);
        LitertBackend backend = LitertBackend.parse(args.has("backend") && !args.isNull("backend") ? string(args, "backend", true) : null);
        String prompt = string(args, "prompt", true);
        if (prompt.trim().isEmpty()) throw LitertFailure.invalid("prompt is required");
        if (prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) {
            throw LitertFailure.invalid("prompt is larger than " + MAX_PROMPT_BYTES + " bytes");
        }
        int maxTokens = integer(args, "max_tokens", DEFAULT_MAX_TOKENS, 1, MAX_MAX_TOKENS);
        int topK = integer(args, "top_k", DEFAULT_TOP_K, 1, MAX_TOP_K);
        double temperature = DEFAULT_TEMPERATURE;
        if (args.has("temperature")) {
            Object raw = args.opt("temperature");
            if (!(raw instanceof Number)) throw LitertFailure.invalid("temperature must be a number");
            temperature = ((Number) raw).doubleValue();
            if (Double.isNaN(temperature) || Double.isInfinite(temperature) || temperature < 0 || temperature > MAX_TEMPERATURE) {
                throw LitertFailure.invalid("temperature must be between 0 and " + MAX_TEMPERATURE);
            }
        }
        return new LitertParams(requestId, model, backend, prompt.trim(), maxTokens, temperature, topK);
    }

    private static String string(JSONObject args, String name, boolean required) throws LitertFailure {
        Object raw = args.opt(name);
        if (raw == null || JSONObject.NULL.equals(raw)) {
            if (required) throw LitertFailure.invalid(name + " is required");
            return null;
        }
        if (!(raw instanceof String)) throw LitertFailure.invalid(name + " must be a string");
        return (String) raw;
    }

    private static int integer(JSONObject args, String name, int fallback, int min, int max) throws LitertFailure {
        if (!args.has(name)) return fallback;
        Object raw = args.opt(name);
        if (!(raw instanceof Number)) throw LitertFailure.invalid(name + " must be an integer");
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || value != Math.rint(value) || value < min || value > max) {
            throw LitertFailure.invalid(name + " must be an integer between " + min + " and " + max);
        }
        return (int) value;
    }
}
