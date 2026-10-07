package com.termux.app.terminal.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * The AICore logic of the app, independent of Android and of ML Kit: it asks a
 * {@link GenAiPort} and builds the JSON the socket answers with.
 */
public final class AiCoreEngine {

    /** AICore needs Android 12 (API 31). */
    public static final int MIN_SDK = 31;
    /** The ML Kit GenAI Prompt version this app is built with (see app/build.gradle). */
    public static final String SDK_VERSION = "1.0.0-beta4";

    static final int STATUS_UNAVAILABLE = 0;
    static final int STATUS_DOWNLOADABLE = 1;
    static final int STATUS_DOWNLOADING = 2;
    static final int STATUS_AVAILABLE = 3;

    private final GenAiPort port;
    private final IntSupplier sdkInt;
    private final LongSupplier clockMs;
    private final AtomicReference<String> lastError = new AtomicReference<>(null);
    private final ConcurrentHashMap<String, Future<?>> inflight = new ConcurrentHashMap<>();
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "termux-ai-stream");
        t.setDaemon(true);
        return t;
    });

    public AiCoreEngine(GenAiPort port, IntSupplier sdkInt, LongSupplier clockMs) {
        this.port = port;
        this.sdkInt = sdkInt;
        this.clockMs = clockMs;
    }

    public boolean isSdkSupported() { return sdkInt.getAsInt() >= MIN_SDK; }

    public String lastInitError() { return lastError.get(); }

    static String statusName(int status) {
        switch (status) {
            case STATUS_DOWNLOADING: return "DOWNLOADING";
            case STATUS_DOWNLOADABLE: return "DOWNLOADABLE";
            case STATUS_AVAILABLE: return "AVAILABLE";
            default: return "UNAVAILABLE";
        }
    }

    private static String statusError(int status) {
        switch (status) {
            case STATUS_DOWNLOADING: return "model downloading";
            case STATUS_DOWNLOADABLE: return "model downloadable but not downloaded";
            case STATUS_AVAILABLE: return null;
            default: return "unavailable (status=" + status + ")";
        }
    }

    /** The selection the request asked for, with what the SDK reports for it. */
    public JSONObject info(ModelSelection selection) throws Exception {
        boolean sdkOk = isSdkSupported();
        JSONObject json = new JSONObject()
            .put("sdk_int", sdkInt.getAsInt())
            .put("backend", "mlkit-genai-prompt")
            .put("sdk_version", SDK_VERSION)
            .put("supports_streaming", true)
            .put("supports_tools", false)
            .put("requested", new JSONObject()
                .put("stage", selection.stage.wire)
                .put("preference", selection.preference.wire));
        if (!sdkOk) {
            lastError.set("Android < 12 (API " + sdkInt.getAsInt() + ")");
            return json.put("available", false).put("error", lastError.get());
        }
        int status;
        try {
            status = port.checkStatus(selection);
        } catch (Exception e) {
            lastError.set(describe(e));
            return json.put("available", false).put("status", "UNAVAILABLE").put("error", lastError.get());
        }
        String error = statusError(status);
        lastError.set(error);
        json.put("available", status == STATUS_AVAILABLE)
            .put("status", statusName(status))
            .put("stage", selection.stage.wire)
            .put("preference", selection.preference.wire);
        if (error != null) json.put("error", error);
        JSONObject effective = new JSONObject()
            .put("stage", selection.stage.wire)
            .put("preference", selection.preference.wire);
        if (status != STATUS_UNAVAILABLE) {
            try {
                effective.put("base_model_name", port.baseModelName(selection));
            } catch (Exception e) {
                effective.put("base_model_name_error", describe(e));
            }
            try {
                GenAiPort.Capabilities c = port.capabilities(selection);
                json.put("capabilities", new JSONObject()
                    .put("token_limit", nullable(c.tokenLimit))
                    .put("system_prompt", nullable(c.systemPrompt))
                    .put("structured_output", nullable(c.structuredOutput))
                    .put("thinking", nullable(c.thinking))
                    .put("caching", nullable(c.caching)));
            } catch (Exception e) {
                json.put("capabilities_error", describe(e));
            }
        }
        return json.put("effective", effective);
    }

    /** AICore cannot list models: each combination is probed. */
    public JSONObject models() throws Exception {
        JSONArray models = new JSONArray();
        boolean sdkOk = isSdkSupported();
        for (ModelSelection selection : ModelSelection.all()) {
            JSONObject entry = new JSONObject()
                .put("stage", selection.stage.wire)
                .put("preference", selection.preference.wire);
            if (!sdkOk) {
                models.put(entry.put("status", "UNAVAILABLE").put("available", false)
                    .put("error", "Android < 12 (API " + sdkInt.getAsInt() + ")"));
                continue;
            }
            int status;
            try {
                status = port.checkStatus(selection);
            } catch (Exception e) {
                models.put(entry.put("status", "UNAVAILABLE").put("available", false).put("error", describe(e)));
                continue;
            }
            entry.put("status", statusName(status)).put("available", status == STATUS_AVAILABLE);
            if (status != STATUS_UNAVAILABLE) {
                try {
                    entry.put("base_model_name", port.baseModelName(selection));
                } catch (Exception e) {
                    entry.put("base_model_name_error", describe(e));
                }
            }
            models.put(entry);
        }
        return new JSONObject()
            .put("models", models)
            .put("note", "AICore does not list models: each stage/preference pair is probed with checkStatus() and getBaseModelName()");
    }

    public JSONObject download(ModelSelection selection) throws Exception {
        requireSdk();
        port.download(selection, bytes -> { /* progress is not part of the answer */ });
        int status = port.checkStatus(selection);
        return new JSONObject()
            .put("status", statusName(status))
            .put("available", status == STATUS_AVAILABLE)
            .put("stage", selection.stage.wire)
            .put("preference", selection.preference.wire);
    }

    public JSONObject generate(GenParams params, ModelSelection selection) throws Exception {
        requireSdk();
        int status = port.checkStatus(selection);
        if (status != STATUS_AVAILABLE) {
            throw new IllegalStateException("Model not available: " + statusName(status));
        }
        long start = clockMs.getAsLong();
        GenResult result = port.generate(selection, params);
        long latencyMs = clockMs.getAsLong() - start;
        JSONObject json = new JSONObject()
            .put("text", result.text)
            .put("finish_reason", result.finishReason)
            .put("usage", new JSONObject()
                .put("input_tokens_approx", params.prompt.length() / 4)
                .put("output_tokens_approx", result.text.length() / 4))
            .put("latency_ms", latencyMs)
            .put("stage", selection.stage.wire)
            .put("preference", selection.preference.wire)
            .put("max_tokens", params.maxTokens)
            .put("temperature", (double) params.temperature);
        if (params.topK != null) json.put("top_k", params.topK.intValue());
        return json;
    }

    /** Streams in the background; {@link #cancel} interrupts it. */
    public String stream(String requestId, GenParams params, ModelSelection selection, AICoreStreamCallback callback) {
        if (!isSdkSupported()) {
            callback.onError(new IllegalStateException("Android < 12 not supported"));
            return requestId;
        }
        Future<?> future = streamExecutor.submit(() -> {
            try {
                int[] chars = {0};
                GenResult result = port.generateStream(selection, params, chunk -> {
                    if (chunk != null && !chunk.isEmpty()) {
                        callback.onChunk(chunk);
                        chars[0] += chunk.length();
                    }
                });
                callback.onComplete(result.finishReason, chars[0]);
            } catch (Throwable t) {
                callback.onError(t);
            } finally {
                inflight.remove(requestId);
            }
        });
        inflight.put(requestId, future);
        return requestId;
    }

    public boolean cancel(String requestId) {
        Future<?> future = inflight.remove(requestId);
        return future != null && future.cancel(true);
    }

    private void requireSdk() {
        if (!isSdkSupported()) throw new IllegalStateException("AICore requires Android 12+");
    }

    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }

    static String describe(Throwable t) {
        if (t instanceof AiCoreFailure) {
            AiCoreFailure f = (AiCoreFailure) t;
            return f.name + " (" + f.code + "): " + f.getMessage();
        }
        return t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage());
    }

    /** Lists exposed for tests. */
    static List<ModelSelection> combinations() { return ModelSelection.all(); }
}
