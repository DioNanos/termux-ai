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
    /** The one thread that runs native stops; at most one stop is ever in flight for this worker. */
    private final LitertStopper stopper = new LitertStopper();

    private LitertRuntime.Loaded loaded;
    private String loadedModelPath;
    private LitertBackend loadedBackend;
    private int loadedContext;
    private LitertActivation loadedActivation = LitertActivation.DEFAULT;

    private String activeRequestId;
    /**
     * The cancel of the active request, created with it and used by nothing else: a cancel is bound to the request,
     * never to the engine, so it holds while the model loads and cannot reach a later request.
     */
    private LitertCancelToken activeToken;
    /** The generation of the active request has returned: from then on a cancel has nothing left to stop. */
    private boolean generationOver;

    private final LitertProcessControl control;
    private final long idleUnloadMs;
    /** Reports process memory around an unload and in the status; without one those fields are absent. */
    public LitertRunner withMemoryProbe(java.util.function.Supplier<JSONObject> probe) {
        this.memoryProbe = probe;
        return this;
    }

    private JSONObject memory() {
        java.util.function.Supplier<JSONObject> probe = memoryProbe;
        if (probe == null) return null;
        try {
            return probe.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** When the last generation ended: the idle clock starts here. Only a generation moves it. */
    private long lastActivityMs;
    /** The process is going to be ended (unload on GPU, restart, idle unload on GPU): no new generation is accepted. */
    private boolean recyclePending;
    private boolean recycleStarted;
    private volatile java.util.function.Supplier<JSONObject> memoryProbe;
    /** Why the last engine close did not complete; null after a close that did. A close is never silent. */
    private String lastCloseError;

    public LitertRunner(LitertRuntime runtime, LongSupplier clockMs) {
        this(runtime, clockMs, LitertProcessControl.NONE, 0);
    }

    /** @param idleUnloadMs how long an engine may sit unused before it is unloaded; 0 or less turns the timer off */
    public LitertRunner(LitertRuntime runtime, LongSupplier clockMs, LitertProcessControl control, long idleUnloadMs) {
        this.runtime = runtime;
        this.clockMs = clockMs;
        this.control = control;
        this.idleUnloadMs = idleUnloadMs;
    }

    /**
     * The idle unload, driven by a timer outside this class. The decision and the state change are one step under
     * the same lock that accepts a generation: an engine in use is never unloaded, and a generation cannot be
     * accepted between the check and the unload. A CPU engine is closed in place; any other backend ends the
     * process (a GPU or NPU engine is never closed and reloaded in the same process, see {@link #unload}).
     */
    public void idleTick() {
        synchronized (lock) {
            if (idleUnloadMs <= 0 || loaded == null || recyclePending) return;
            if (activeRequestId != null || stopper.isBusy()) return;
            if (clockMs.getAsLong() - lastActivityMs < idleUnloadMs) return;
            if (loadedBackend == LitertBackend.CPU) {
                closeLoaded();
                return;
            }
            recyclePending = true;
            abandonLoaded();
        }
        runPendingRecycle();
    }

    /**
     * Ends the process if a recycle was asked for. Called after the reply of {@code unload} or {@code restart} has
     * been sent, so the answer leaves before the process goes. From the moment a recycle is pending every new
     * generation is answered BUSY: nothing is accepted into a process that is about to end.
     */
    public void runPendingRecycle() {
        synchronized (lock) {
            if (!recyclePending || recycleStarted) return;
            recycleStarted = true;
        }
        control.recycle();
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
                case "unload": return unload();
                case "restart": return restart();
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
        LitertActivation activation;
        try {
            backend = LitertBackend.parse(request.optString("backend", null));
            activation = LitertActivation.parse(request.has("activation") ? request.optString("activation", null) : null);
        } catch (LitertFailure f) {
            return error(f.code, "validate", null, modelId, f.getMessage());
        }
        LitertCancelToken token;
        synchronized (lock) {
            if (activeRequestId != null) {
                return error(LitertErrorCode.BUSY, "generate", backend.wire, modelId,
                    "request " + activeRequestId + " is still running");
            }
            // A native stop that has not returned (after CANCEL_TIMEOUT it can stay stuck) keeps the worker busy:
            // the answer is immediate and typed, never a wait.
            if (stopper.isBusy()) {
                return error(LitertErrorCode.BUSY, "generate", backend.wire, modelId,
                    "a native cancel is still running; a new request is refused until it returns");
            }
            if (recyclePending) {
                return error(LitertErrorCode.BUSY, "generate", backend.wire, modelId,
                    "the worker is being recycled; send the request again in a moment");
            }
            activeRequestId = requestId;
            activeToken = new LitertCancelToken(stopper);
            generationOver = false;
            token = activeToken;
        }
        try {
            return run(request, requestId, modelId, backend, activation, token);
        } finally {
            synchronized (lock) {
                activeRequestId = null;
                activeToken = null;
                generationOver = false;
                lastActivityMs = clockMs.getAsLong();
            }
        }
    }

    private String run(JSONObject request, String requestId, String modelId, LitertBackend backend,
                       LitertActivation activation, LitertCancelToken token) throws JSONException {
        String modelPath = request.getString("model_path");
        int context = request.getInt("context_tokens");
        long start = clockMs.getAsLong();
        if (token.isCancelled()) return cancelledReply(backend, modelId, "before the model was loaded");
        boolean reused = loaded != null && loadedBackend == backend && loadedContext == context && loadedActivation == activation
            && loadedModelPath.equals(modelPath);
        if (!reused) {
            closeLoaded();
            try {
                loaded = runtime.load(modelPath, backend, context, activation);
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
            loadedActivation = activation;
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
        // An empty string is not an answer: it is reported as an error, never as ok:true. The engine stays loaded.
        if (output.text.trim().isEmpty()) {
            return error(LitertErrorCode.EMPTY_OUTPUT, "generate", backend.wire, modelId,
                "the model produced no answer text (finish_reason " + output.finishReason + "; thinking "
                    + output.thinking.length() + " chars; tool calls " + output.toolCallsCount + ")");
        }
        long done = clockMs.getAsLong();
        boolean cpu = backend == LitertBackend.CPU;
        String evidence = loaded.evidence();
        JSONObject data = new JSONObject()
            .put("request_id", requestId)
            .put("text", output.text)
            .put("finish_reason", output.finishReason)
            .put("thinking", output.thinking)
            .put("tool_calls_count", output.toolCallsCount)
            .put("backend_requested", backend.wire)
            .put("activation_requested", activation.wire)
            // A CPU engine can only run on the CPU. For gpu and npu the SDK reports no executor, so nothing is claimed.
            .put("backend_effective", cpu ? "cpu" : JSONObject.NULL)
            .put("backend_verified", cpu)
            .put("backend_evidence", evidence == null || evidence.isEmpty() ? EVIDENCE_NONE : evidence)
            .put("engine_reused", reused)
            .put("load_ms", loadedAt - start)
            .put("generate_ms", done - loadedAt);
        return new JSONObject().put("ok", true).put("data", data).toString();
    }

    /** Cancels one request by id, or with {@code all} whatever request is running; exactly one of the two. */
    private String cancel(JSONObject request) throws JSONException {
        boolean hasId = request.has("request_id");
        boolean all = request.optBoolean("all", false);
        if (hasId == all) {
            return error(LitertErrorCode.INVALID_ARGUMENT, "validate", null, null, "cancel needs exactly one of request_id and all");
        }
        String requestId = hasId ? request.getString("request_id") : null;
        LitertCancelToken token = null;
        String cancelled = null;
        synchronized (lock) {
            // Only the request that is active, and whose generation has not returned, can still be cancelled; a
            // cancel for a request that already finished is a no-op.
            if (activeRequestId != null && !generationOver && (all || activeRequestId.equals(requestId))) {
                token = activeToken;
                cancelled = activeRequestId;
            }
        }
        if (token != null) token.cancel();
        JSONObject data = new JSONObject().put("cancelled", token != null);
        if (all && token != null) data.put("request_id", cancelled);
        return new JSONObject().put("ok", true).put("data", data).toString();
    }

    private static String cancelledReply(LitertBackend backend, String modelId, String when) {
        return error(LitertErrorCode.CANCELLED, "init", backend.wire, modelId, "the request was cancelled " + when);
    }

    /**
     * Unloads the engine. Refused (BUSY) while a generation runs or a native stop has not returned: it never closes
     * under either. A CPU engine is closed in place; a GPU or NPU engine is not (closing and reloading in one process
     * is what degraded the GPU allocator on some devices): the process is ended once the reply is out, and the system
     * starts a clean one on the next request.
     */
    private String unload() throws JSONException {
        synchronized (lock) {
            if (activeRequestId != null) {
                return error(LitertErrorCode.BUSY, "unload", null, null, "request " + activeRequestId + " is still running");
            }
            if (stopper.isBusy()) {
                return error(LitertErrorCode.BUSY, "unload", null, null, "a native cancel is still running; nothing is unloaded under it");
            }
            JSONObject data = new JSONObject().put("unloaded", loaded != null);
            if (loaded == null) return new JSONObject().put("ok", true).put("data", data.put("via", "none")).toString();
            JSONObject before = memory();
            if (before != null) data.put("memory_before", before);
            if (loadedBackend == LitertBackend.CPU) {
                String closeError = closeLoaded();
                data.put("via", "close").put("closed_cleanly", closeError == null);
                if (closeError != null) data.put("close_error", closeError);
                JSONObject after = memory();
                if (after != null) data.put("memory_after", after);
                return new JSONObject().put("ok", true).put("data", data).toString();
            }
            // The process ends after the reply: what it frees is what the next process starts with, not a figure here.
            if (before != null) data.put("memory_after", JSONObject.NULL);
            recyclePending = true;
            abandonLoaded();
            return new JSONObject().put("ok", true).put("data", data.put("via", "process_recycle")).toString();
        }
    }

    /**
     * Ends the process whatever it is doing: the way out of a worker that is busy for good. A request in flight is
     * lost and the client sees MODEL_WORKER_DIED; the reply to this call may be lost the same way.
     */
    private String restart() throws JSONException {
        synchronized (lock) {
            recyclePending = true;
            abandonLoaded();
        }
        return new JSONObject().put("ok", true).put("data", new JSONObject().put("restarting", true)).toString();
    }

    private String status() throws JSONException {
        synchronized (lock) {
            boolean timed = idleUnloadMs > 0 && loaded != null && activeRequestId == null;
            JSONObject data = new JSONObject()
                .put("last_close_error", lastCloseError == null ? JSONObject.NULL : lastCloseError)
                .put("idle_unload_ms", idleUnloadMs)
                .put("idle_unload_in_ms", timed ? Math.max(0, idleUnloadMs - (clockMs.getAsLong() - lastActivityMs)) : JSONObject.NULL)
                .put("active_request_id", activeRequestId == null ? JSONObject.NULL : activeRequestId)
                .put("recycle_pending", recyclePending)
                .put("state", activeRequestId != null ? "busy" : (stopper.isBusy() ? "stopping" : (loaded != null ? "loaded" : "idle")))
                .put("native_stop", stopper.isBusy() ? "running" : "none")
                .put("loaded_model_path", loaded != null ? loadedModelPath : JSONObject.NULL)
                .put("loaded_backend", loaded != null ? loadedBackend.wire : JSONObject.NULL)
                .put("loaded_activation", loaded != null ? loadedActivation.wire : JSONObject.NULL);
            JSONObject current = memory();
            if (current != null) data.put("memory", current);
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

    /**
     * Closes the engine, if one is open. The engine is forgotten either way (the next load starts from nothing),
     * but a close that fails is not silent: the reason is kept for the status and returned to the caller.
     *
     * @return why the close did not complete, or null
     */
    private String closeLoaded() {
        if (loaded == null) return null;
        String error = null;
        try {
            loaded.close();
        } catch (Exception | LinkageError e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null || e.getMessage().isEmpty() ? "" : ": " + e.getMessage());
        }
        lastCloseError = error;
        loaded = null;
        loadedModelPath = null;
        loadedBackend = null;
        return error;
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
