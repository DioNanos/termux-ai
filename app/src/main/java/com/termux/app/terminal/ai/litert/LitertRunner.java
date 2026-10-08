package com.termux.app.terminal.ai.litert;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The logic of the {@code :litert} process: one engine, one request at a time. It reads the JSON of a request and
 * answers with the JSON of a reply; it knows nothing about Android or the broker.
 *
 * <p>Lifecycle rules: the engine is reused only for the same model, backend and context; a different key closes
 * the old engine <em>before</em> the new one is opened, so two engines never coexist; a failed open is reported as
 * is and the backend is never changed.
 */
public final class LitertRunner {
    public static final String EVIDENCE_NONE = "the SDK does not report which executor ran the model";

    private final LitertRuntime runtime;
    private final LongSupplier clockMs;
    private final Object lock = new Object();

    private LitertRuntime.Loaded loaded;
    private String loadedModelPath;
    private LitertBackend loadedBackend;
    private int loadedContext;

    private String activeRequestId;
    /**
     * The cancel of the active request, created with it and used by nothing else: a cancel is bound to the request,
     * never to the engine, so it holds while the model loads and cannot reach a later request.
     */
    private LitertCancelToken activeToken;
    /** The generation of the active request has returned: from then on a cancel has nothing left to stop. */
    private boolean generationOver;

    public LitertRunner(LitertRuntime runtime, LongSupplier clockMs) {
        this.runtime = runtime;
        this.clockMs = clockMs;
    }

    /** One request in, one reply out. Never throws. */
    public String handle(String requestJson) {
        try {
            JSONObject request = new JSONObject(requestJson);
            String op = request.optString("op", "");
            switch (op) {
                case "generate": return generate(request);
                case "cancel": return cancel(request);
                case "status": return status();
                default: return error(LitertErrorCode.INVALID_ARGUMENT, "validate", null, null, "unknown op: " + op);
            }
        } catch (JSONException e) {
            return error(LitertErrorCode.INVALID_ARGUMENT, "validate", null, null, "malformed worker request: " + e.getMessage());
        }
    }

    private String generate(JSONObject request) throws JSONException {
        String requestId = request.getString("request_id");
        String modelId = request.optString("model", null);
        LitertBackend backend;
        try {
            backend = LitertBackend.parse(request.optString("backend", null));
        } catch (LitertFailure f) {
            return error(f.code, "validate", null, modelId, f.getMessage());
        }
        LitertCancelToken token;
        synchronized (lock) {
            if (activeRequestId != null) {
                return error(LitertErrorCode.BUSY, "generate", backend.wire, modelId,
                    "request " + activeRequestId + " is still running");
            }
            activeRequestId = requestId;
            activeToken = new LitertCancelToken();
            generationOver = false;
            token = activeToken;
        }
        try {
            return run(request, requestId, modelId, backend, token);
        } finally {
            synchronized (lock) {
                activeRequestId = null;
                activeToken = null;
                generationOver = false;
            }
        }
    }

    private String run(JSONObject request, String requestId, String modelId, LitertBackend backend,
                       LitertCancelToken token) throws JSONException {
        String modelPath = request.getString("model_path");
        int context = request.getInt("context_tokens");
        long start = clockMs.getAsLong();
        if (token.isCancelled()) return cancelledReply(backend, modelId, "before the model was loaded");
        boolean reused = loaded != null && loadedBackend == backend && loadedContext == context && loadedModelPath.equals(modelPath);
        if (!reused) {
            closeLoaded();
            try {
                loaded = runtime.load(modelPath, backend, context);
            } catch (LitertFailure f) {
                loaded = null;
                return error(f.code, f.phase == null ? "init" : f.phase, backend.wire, modelId, f.getMessage());
            } catch (RuntimeException e) {
                loaded = null;
                return error(LitertErrorCode.BACKEND_INIT_FAILED, "init", backend.wire, modelId, describe(e));
            }
            loadedModelPath = modelPath;
            loadedBackend = backend;
            loadedContext = context;
        }
        long loadedAt = clockMs.getAsLong();
        // The generation itself checks the token as it starts and attaches the native stop to it atomically, so a
        // cancel that arrived during the load is honoured there and one that arrives later reaches the inference.
        if (token.isCancelled()) return cancelledReply(backend, modelId, "while the model was loading");
        LitertRuntime.Output output;
        try {
            output = loaded.generate(request.getString("prompt"), request.getInt("max_tokens"),
                request.getDouble("temperature"), request.getInt("top_k"), request.getDouble("top_p"), request.getInt("seed"),
                token);
        } catch (LitertFailure f) {
            // A native cancel that never returned leaves the conversation open: the engine is not trusted any more.
            // It is dropped without being closed (closing could touch what the stuck call is using); the next
            // request opens a new one.
            if (f.code == LitertErrorCode.CANCEL_TIMEOUT) abandonLoaded();
            return error(f.code, f.phase == null ? "generate" : f.phase, backend.wire, modelId, f.getMessage());
        } catch (RuntimeException e) {
            return error(LitertErrorCode.GENERATION_FAILED, "generate", backend.wire, modelId, describe(e));
        } finally {
            // The runtime ended the generation (and its cancel) before returning: from here nothing is left to stop.
            synchronized (lock) { generationOver = true; }
        }
        long done = clockMs.getAsLong();
        boolean cpu = backend == LitertBackend.CPU;
        String evidence = loaded.evidence();
        JSONObject data = new JSONObject()
            .put("request_id", requestId)
            .put("text", output.text)
            .put("finish_reason", output.finishReason)
            .put("backend_requested", backend.wire)
            // A CPU engine can only run on the CPU. For gpu and npu the SDK reports no executor, so nothing is claimed.
            .put("backend_effective", cpu ? "cpu" : JSONObject.NULL)
            .put("backend_verified", cpu)
            .put("backend_evidence", evidence == null || evidence.isEmpty() ? EVIDENCE_NONE : evidence)
            .put("engine_reused", reused)
            .put("load_ms", loadedAt - start)
            .put("generate_ms", done - loadedAt);
        return new JSONObject().put("ok", true).put("data", data).toString();
    }

    private String cancel(JSONObject request) throws JSONException {
        String requestId = request.getString("request_id");
        LitertCancelToken token = null;
        synchronized (lock) {
            // Only the request that is active, and whose generation has not returned, can still be cancelled; a
            // cancel for a request that already finished is a no-op.
            if (requestId.equals(activeRequestId) && !generationOver) token = activeToken;
        }
        if (token != null) token.cancel();
        return new JSONObject().put("ok", true).put("data", new JSONObject().put("cancelled", token != null)).toString();
    }

    private static String cancelledReply(LitertBackend backend, String modelId, String when) {
        return error(LitertErrorCode.CANCELLED, "init", backend.wire, modelId, "the request was cancelled " + when);
    }

    private String status() throws JSONException {
        synchronized (lock) {
            JSONObject data = new JSONObject()
                .put("state", activeRequestId != null ? "busy" : (loaded != null ? "loaded" : "idle"))
                .put("loaded_model_path", loaded != null ? loadedModelPath : JSONObject.NULL)
                .put("loaded_backend", loaded != null ? loadedBackend.wire : JSONObject.NULL);
            return new JSONObject().put("ok", true).put("data", data).toString();
        }
    }

    /** Closes the engine, if one is open. Called when the process is going away. */
    public void shutdown() {
        synchronized (lock) { closeLoaded(); }
    }

    /** Forgets the engine without closing it. Only for an engine whose native state cannot be trusted. */
    private void abandonLoaded() {
        loaded = null;
        loadedModelPath = null;
        loadedBackend = null;
    }

    private void closeLoaded() {
        if (loaded == null) return;
        try {
            loaded.close();
        } catch (RuntimeException ignored) {
            // The next load starts from nothing either way.
        }
        loaded = null;
        loadedModelPath = null;
        loadedBackend = null;
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static String error(LitertErrorCode code, String phase, String backend, String model, String message) {
        try {
            JSONObject json = new JSONObject().put("ok", false).put("error", Objects.toString(message, ""))
                .put("error_name", code.name()).put("phase", phase);
            if (backend != null) json.put("backend_requested", backend);
            if (model != null) json.put("model", model);
            return json.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error_name\":\"GENERATION_FAILED\",\"error\":\"internal error\"}";
        }
    }
}
