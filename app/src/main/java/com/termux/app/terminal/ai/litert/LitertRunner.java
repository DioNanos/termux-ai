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
 *
 * <p>Every accepted request owns a {@link Cycle}. A restart (and the end of the process) invalidates the cycle of the
 * request in flight under the same lock that guards the engine state, without waiting for the native call: whatever
 * that request produces afterwards is answered MODEL_WORKER_DIED and never published, and it works on the engine it
 * took when it started, never on what the runner holds by then.
 *
 * <p>Locking: a request opens, closes and generates outside the lock and decides under it. The calls that act on an
 * idle engine (idle unload, unload, and the shutdown of an idle runner) close it while holding the lock, which is
 * safe because they only run with no request active. The shutdown of a runner with a request in flight never closes
 * the engine that request may be using: it abandons it, and the process that is ending takes it with it.
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

    /**
     * The life of one request, created when it is accepted and used by nothing else. {@code invalidated} (read and
     * written under {@link #lock}) means a restart or the end of the process took the request away from the engine:
     * from then on it publishes nothing and its outcome is MODEL_WORKER_DIED.
     */
    private static final class Cycle {
        boolean invalidated;
    }

    private String activeRequestId;
    private Cycle activeCycle;
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
    /** The runner has been shut down (the service is going away): nothing is accepted any more. Never reset. */
    private boolean ended;
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
            if (idleUnloadMs <= 0 || loaded == null || recyclePending || ended) return;
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
        LitertChat chat;
        try {
            backend = LitertBackend.parse(request.optString("backend", null));
            activation = LitertActivation.parse(request.has("activation") ? request.optString("activation", null) : null);
            chat = request.has("messages")
                ? LitertChat.parse(request.getJSONArray("messages"), request.optJSONArray("tools"))
                : LitertChat.ofPrompt(request.getString("prompt"));
        } catch (LitertFailure f) {
            return error(f.code, "validate", null, modelId, f.getMessage());
        }
        LitertCancelToken token;
        Cycle cycle;
        synchronized (lock) {
            // The end of the runner comes before every busy state: a runner that has ended is not busy for a while,
            // it is gone, even while the request that was running when it ended has not returned.
            if (ended) {
                return error(LitertErrorCode.MODEL_WORKER_DIED, "worker", backend.wire, modelId,
                    "the worker has been shut down and accepts no request");
            }
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
            activeCycle = new Cycle();
            activeToken = new LitertCancelToken(stopper);
            generationOver = false;
            token = activeToken;
            cycle = activeCycle;
        }
        try {
            String reply = run(request, requestId, modelId, backend, activation, chat, token, cycle);
            // The point of order between the end of the request and a restart: this step, under the lock. A restart
            // that took the lock first has invalidated the request, whatever it produced is not delivered as such.
            synchronized (lock) {
                if (cycle.invalidated) return workerDied(backend, modelId);
            }
            return reply;
        } finally {
            synchronized (lock) {
                // The epilogue of an old request never touches the state of another one.
                if (activeCycle == cycle) {
                    activeRequestId = null;
                    activeCycle = null;
                    activeToken = null;
                    generationOver = false;
                    lastActivityMs = clockMs.getAsLong();
                }
            }
        }
    }

    private boolean invalidated(Cycle cycle) {
        synchronized (lock) { return cycle.invalidated; }
    }

    private static String workerDied(LitertBackend backend, String modelId) {
        return error(LitertErrorCode.MODEL_WORKER_DIED, "worker", backend.wire, modelId,
            "the worker was restarted while this request was running; the request was dropped");
    }

    private String run(JSONObject request, String requestId, String modelId, LitertBackend backend,
                       LitertActivation activation, LitertChat chat, LitertCancelToken token, Cycle cycle) throws JSONException {
        String modelPath = request.getString("model_path");
        int context = request.getInt("context_tokens");
        long start = clockMs.getAsLong();
        if (token.isCancelled()) return cancelledReply(backend, modelId, "before the model was loaded");
        // What this request works on is decided once, under the lock, and kept in locals from here on.
        LitertRuntime.Loaded engine;
        LitertRuntime.Loaded stale = null;
        boolean reused;
        synchronized (lock) {
            if (cycle.invalidated) return workerDied(backend, modelId);
            reused = loaded != null && loadedBackend == backend && loadedContext == context && loadedActivation == activation
                && loadedModelPath.equals(modelPath);
            engine = reused ? loaded : null;
            if (!reused) {
                stale = loaded;
                abandonLoaded();
            }
        }
        if (!reused) {
            // The old engine is closed before the new one is opened: two engines never coexist.
            if (stale != null) {
                String closeError = closeEngine(stale);
                synchronized (lock) {
                    lastCloseError = closeError;
                    // The close may have blocked while the worker was restarted or shut down: the order is checked
                    // again before any new engine is opened, and a restarted worker opens nothing.
                    if (cycle.invalidated) return workerDied(backend, modelId);
                }
            }
            LitertRuntime.Loaded fresh;
            try {
                fresh = runtime.load(modelPath, backend, context, activation);
            } catch (LitertFailure f) {
                return error(f.code, f.phase == null ? "init" : f.phase, backend.wire, modelId, f.getMessage());
            } catch (RuntimeException e) {
                return error(LitertErrorCode.BACKEND_INIT_FAILED, "init", backend.wire, modelId, describe(e));
            }
            synchronized (lock) {
                // A restart that came while the engine was opening: it is not published, and not closed either (the
                // process is going away; a close here would be a native call in a worker that is being recycled).
                if (cycle.invalidated) return workerDied(backend, modelId);
                loaded = fresh;
                loadedModelPath = modelPath;
                loadedBackend = backend;
                loadedContext = context;
                loadedActivation = activation;
            }
            engine = fresh;
        }
        long loadedAt = clockMs.getAsLong();
        // The generation itself checks the token as it starts and attaches the native stop to it atomically, so a
        // cancel that arrived during the load is honoured there and one that arrives later reaches the inference.
        if (token.isCancelled()) return cancelledReply(backend, modelId, "while the model was loading");
        // A restart is the one thing that wins over a request that has not started its inference yet.
        if (invalidated(cycle)) return workerDied(backend, modelId);
        LitertRuntime.Output output;
        try {
            output = engine.generate(chat, request.getInt("max_tokens"),
                request.getDouble("temperature"), request.getInt("top_k"), request.getDouble("top_p"), request.getInt("seed"),
                token);
        } catch (LitertFailure f) {
            // A native cancel that never returned leaves the conversation open: the engine is not trusted any more.
            // It is dropped without being closed (closing could touch what the stuck call is using); the next
            // request opens a new one. Only this request's engine is dropped, and only if the runner still holds it.
            if (f.code == LitertErrorCode.CANCEL_TIMEOUT) abandonIfCurrent(engine);
            return error(f.code, f.phase == null ? "generate" : f.phase, backend.wire, modelId, f.getMessage());
        } catch (RuntimeException e) {
            return error(LitertErrorCode.GENERATION_FAILED, "generate", backend.wire, modelId, describe(e));
        } finally {
            // The runtime ended the generation (and its cancel) before returning: from here nothing is left to stop.
            synchronized (lock) { if (activeCycle == cycle) generationOver = true; }
        }
        // A restart that came while the engine was generating: nothing more is read from the engine.
        if (invalidated(cycle)) return workerDied(backend, modelId);
        // An empty string is not an answer: it is reported as an error, never as ok:true. The engine stays loaded.
        if (output.text.trim().isEmpty() && output.toolCalls.isEmpty()) {
            return error(LitertErrorCode.EMPTY_OUTPUT, "generate", backend.wire, modelId,
                "the model produced no answer text (finish_reason " + output.finishReason + "; thinking "
                    + output.thinking.length() + " chars; tool calls " + output.toolCallsCount + ")");
        }
        long done = clockMs.getAsLong();
        boolean cpu = backend == LitertBackend.CPU;
        String evidence = engine.evidence();
        JSONObject data = new JSONObject()
            .put("request_id", requestId)
            .put("text", output.text)
            .put("finish_reason", output.finishReason)
            .put("thinking", output.thinking)
            .put("tool_calls_count", output.toolCallsCount)
            .put("tool_calls", toolCallsJson(output.toolCalls))
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

    private static org.json.JSONArray toolCallsJson(java.util.List<LitertChat.Call> calls) throws JSONException {
        org.json.JSONArray out = new org.json.JSONArray();
        for (LitertChat.Call call : calls) out.put(new JSONObject().put("name", call.name).put("arguments", call.arguments));
        return out;
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
     * lost and the client sees MODEL_WORKER_DIED (from the process dying, or from the reply this runner gives the
     * request when its native call returns first); the reply to this call may be lost the same way. It marks the
     * recycle and invalidates the request at once and waits for nothing: no load, generate or stop is waited on.
     */
    private String restart() throws JSONException {
        synchronized (lock) {
            recyclePending = true;
            invalidateActive();
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

    /**
     * The end of the runner: the process is going away. Idempotent. It invalidates the request in flight (its late
     * outcome is MODEL_WORKER_DIED) and refuses every request from now on. An idle engine is closed cleanly; an
     * engine that a request, a load or a native stop may still be using is not closed under it: it is abandoned, and
     * nothing here waits for any native call.
     */
    public void shutdown() {
        synchronized (lock) {
            ended = true;
            boolean inUse = activeRequestId != null || stopper.isBusy();
            invalidateActive();
            if (inUse) {
                abandonLoaded();
            } else {
                closeLoaded();
            }
        }
    }

    /** Takes the active request away from the engine, under the lock. Its late outcome becomes MODEL_WORKER_DIED. */
    private void invalidateActive() {
        if (activeCycle != null) activeCycle.invalidated = true;
    }

    /** Forgets the engine without closing it. Only for an engine whose native state cannot be trusted. */
    private void abandonLoaded() {
        loaded = null;
        loadedModelPath = null;
        loadedBackend = null;
    }

    /** As {@link #abandonLoaded}, but only if the runner still holds this very engine. */
    private void abandonIfCurrent(LitertRuntime.Loaded engine) {
        synchronized (lock) {
            if (loaded == engine) abandonLoaded();
        }
    }

    /**
     * Closes the engine, if one is open. The engine is forgotten either way (the next load starts from nothing),
     * but a close that fails is not silent: the reason is kept for the status and returned to the caller.
     *
     * @return why the close did not complete, or null
     */
    private String closeLoaded() {
        if (loaded == null) return null;
        LitertRuntime.Loaded engine = loaded;
        abandonLoaded();
        String error = closeEngine(engine);
        lastCloseError = error;
        return error;
    }

    /** The native close of one engine, never throwing; the reason it did not complete, or null. */
    private static String closeEngine(LitertRuntime.Loaded engine) {
        try {
            engine.close();
            return null;
        } catch (Exception | LinkageError e) {
            return e.getClass().getSimpleName() + (e.getMessage() == null || e.getMessage().isEmpty() ? "" : ": " + e.getMessage());
        }
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
