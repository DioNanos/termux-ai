package com.termux.app.terminal.ai;

import org.json.JSONObject;

/** Validated parameters of one generation. Invalid values are rejected, not ignored. */
public final class GenParams {

    public static final int DEFAULT_MAX_TOKENS = 256;
    public static final int MIN_MAX_TOKENS = 1;
    public static final int MAX_MAX_TOKENS = 4096;
    public static final float DEFAULT_TEMPERATURE = 0.2f;
    public static final float MIN_TEMPERATURE = 0.0f;
    /** The SDK builder rejects anything above 1 (measured on genai-prompt 1.0.0-beta4). */
    public static final float MAX_TEMPERATURE = 1.0f;
    public static final int MIN_TOP_K = 1;
    /** The SDK only requires a positive top-k. */
    public static final int MAX_TOP_K = Integer.MAX_VALUE;

    public final String prompt;
    public final int maxTokens;
    public final float temperature;
    /** Null when the caller did not ask for one: the SDK default applies. */
    public final Integer topK;

    public GenParams(String prompt, int maxTokens, float temperature, Integer topK) {
        this.prompt = prompt;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.topK = topK;
    }

    /** Parses {@code prompt}, {@code max_tokens}, {@code temperature} and {@code top_k}. */
    public static GenParams fromArgs(JSONObject args) {
        Object prompt = args.opt("prompt");
        if (!(prompt instanceof String) || ((String) prompt).trim().isEmpty()) {
            throw new IllegalArgumentException("prompt is required");
        }
        int maxTokens = integer(args, "max_tokens", DEFAULT_MAX_TOKENS, MIN_MAX_TOKENS, MAX_MAX_TOKENS);
        float temperature = DEFAULT_TEMPERATURE;
        if (args.has("temperature")) {
            Object raw = args.opt("temperature");
            if (!(raw instanceof Number)) throw new IllegalArgumentException("temperature must be a number");
            double value = ((Number) raw).doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value) || value < MIN_TEMPERATURE || value > MAX_TEMPERATURE) {
                throw new IllegalArgumentException("temperature must be between " + MIN_TEMPERATURE + " and " + MAX_TEMPERATURE);
            }
            temperature = (float) value;
        }
        Integer topK = null;
        if (args.has("top_k")) topK = integer(args, "top_k", 0, MIN_TOP_K, MAX_TOP_K);
        return new GenParams(((String) prompt).trim(), maxTokens, temperature, topK);
    }

    private static int integer(JSONObject args, String name, int fallback, int min, int max) {
        if (!args.has(name)) return fallback;
        Object raw = args.opt(name);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(name + " must be an integer");
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || value != Math.rint(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must be an integer between " + min + " and " + max);
        }
        return (int) value;
    }
}
