package com.termux.app.terminal.ai.litert;

import org.json.JSONArray;
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
    /** The context the engine is opened with when the request does not say; the output limit is separate. */
    public static final int DEFAULT_CONTEXT_TOKENS = 4096;
    /** How long an engine may sit unused before the {@code :litert} process unloads it (5 minutes). */
    public static final long DEFAULT_IDLE_UNLOAD_MS = 300_000L;
    /** How often the idle check runs; an unload can be late by up to this much. */
    public static final long IDLE_TICK_MS = 10_000L;
    /** How often the :litert process checks that the caller of a running generation is still alive. */
    public static final long CALLER_PING_MS = 3_000L;
    /** The request crosses a Binder transaction (about 1 MiB for the whole process): keep the prompt well inside it. */
    public static final int MAX_PROMPT_BYTES = 256 * 1024;

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public final String requestId;
    public final String model;
    public final LitertBackend backend;
    /** The flat prompt, or null when the request carries {@link #messages} instead. */
    public final String prompt;
    /** The chat messages (roles kept), or null when the request carries a flat prompt. */
    public final JSONArray messages;
    /** The OpenAI tool definitions the model may call, or null when there are none. Only with messages. */
    public final JSONArray tools;
    public final int maxTokens;
    /** The context the engine is opened with for this request; an engine per distinct context is kept by the runner. */
    public final int contextTokens;
    public final double temperature;
    public final int topK;
    /** The activation precision asked for; DEFAULT when the request does not say, and then nothing is sent on. */
    public final LitertActivation activation;

    private LitertParams(String requestId, String model, LitertBackend backend, String prompt, JSONArray messages, JSONArray tools,
                         int maxTokens, int contextTokens, double temperature, int topK, LitertActivation activation) {
        this.activation = activation;
        this.requestId = requestId;
        this.model = model;
        this.backend = backend;
        this.prompt = prompt;
        this.messages = messages;
        this.tools = tools;
        this.maxTokens = maxTokens;
        this.contextTokens = contextTokens;
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
        boolean hasMessages = args.has("messages") && !args.isNull("messages");
        String prompt = null;
        JSONArray messages = null;
        JSONArray tools = null;
        if (hasMessages) {
            if (args.has("prompt") && !args.isNull("prompt")) {
                throw LitertFailure.invalid("give either a prompt or messages, not both");
            }
            messages = args.optJSONArray("messages");
            if (messages == null) throw LitertFailure.invalid("messages must be an array");
            if (args.has("tools") && !args.isNull("tools")) {
                tools = args.optJSONArray("tools");
                if (tools == null) throw LitertFailure.invalid("tools must be an array");
                if (tools.length() == 0) tools = null;
            }
            int size = messages.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + (tools == null ? 0 : tools.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            if (size > MAX_PROMPT_BYTES) {
                throw LitertFailure.invalid("messages are larger than " + MAX_PROMPT_BYTES + " bytes");
            }
            // Checked here, so a chat that cannot be sent is refused before the worker is touched.
            LitertChat.parse(messages, tools);
        } else {
            if (args.has("tools") && !args.isNull("tools")) throw LitertFailure.invalid("tools need messages, not a flat prompt");
            prompt = string(args, "prompt", false);
            if (prompt == null || prompt.trim().isEmpty()) throw LitertFailure.invalid("prompt is required unless messages are given");
            if (prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) {
                throw LitertFailure.invalid("prompt is larger than " + MAX_PROMPT_BYTES + " bytes");
            }
            prompt = prompt.trim();
        }
        int maxTokens = integer(args, "max_tokens", DEFAULT_MAX_TOKENS, 1, MAX_MAX_TOKENS);
        // No upper cap: the maximum is what the model file accepts, and an engine that cannot
        // open with the asked context fails with its own typed error (never a silent clamp).
        int contextTokens = integer(args, "context_tokens", DEFAULT_CONTEXT_TOKENS, 1, Integer.MAX_VALUE);
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
        LitertActivation activation = LitertActivation.parse(string(args, "activation", false));
        return new LitertParams(requestId, model, backend, prompt, messages, tools, maxTokens, contextTokens, temperature, topK, activation);
    }

    /** Whether a text is a valid request id (1 to 64 characters of A-Z a-z 0-9 . _ -). */
    static boolean validRequestId(String id) { return id != null && REQUEST_ID.matcher(id).matches(); }

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
