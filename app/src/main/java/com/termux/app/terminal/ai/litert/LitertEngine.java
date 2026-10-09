package com.termux.app.terminal.ai.litert;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@code litert.*} logic of the main process. It validates, checks the device, finds the model and hands the
 * work to the {@link LitertWorker} in the {@code :litert} process; no SDK class is loaded here. The main process
 * cannot tell a death around the spawn from one mid-generation, so a {@code MODEL_WORKER_DIED} in generate is
 * retried exactly once and the retry is declared as {@code spawn_retried}; a death that repeats, and every other
 * failure, is the typed error with nothing replayed or moved to another backend.
 */
public final class LitertEngine {
    public static final String SDK_VERSION = "litertlm-android 0.18.0";
    /** The wall-clock limit of one generation; the worker is asked to cancel when it passes. */
    public static final long GENERATE_DEADLINE_MS = 300_000L;
    private static final long STATUS_TIMEOUT_MS = 5_000L;
    /** How long unload and restart wait for the ending worker to be reported gone before they answer anyway. */
    public static final long TEARDOWN_TIMEOUT_MS = 3_000L;
    static final long TEARDOWN_POLL_MS = 100L;
    /** How long info keeps calling a dying worker "stopping" instead of "unreachable" after a stop was asked. */
    public static final long STOP_WINDOW_MS = 5_000L;
    /** The value of {@code lastStopRequestedAt} before any stop; not Long.MIN_VALUE so a difference cannot overflow. */
    static final long NEVER_STOP_ASKED = Long.MIN_VALUE / 2;

    private final LitertWorker worker;
    private final LitertModelCatalog catalog;
    private final LitertGuards guards;
    private final int sdkInt;
    private final String nativeLibraryDir;
    private final String cacheDir;
    private final long deadlineMs;
    private final java.util.function.LongSupplier clock;
    private final AtomicReference<String> active = new AtomicReference<>(null);
    /** When the last unload or restart was asked for, or {@link #NEVER_STOP_ASKED} if never. */
    private volatile long lastStopRequestedAt = NEVER_STOP_ASKED;

    public LitertEngine(LitertWorker worker, LitertModelCatalog catalog, LitertGuards guards, int sdkInt,
                        String nativeLibraryDir, String cacheDir, long deadlineMs) {
        this(worker, catalog, guards, sdkInt, nativeLibraryDir, cacheDir, deadlineMs, System::currentTimeMillis);
    }

    public LitertEngine(LitertWorker worker, LitertModelCatalog catalog, LitertGuards guards, int sdkInt,
                        String nativeLibraryDir, String cacheDir, long deadlineMs, java.util.function.LongSupplier clock) {
        this.worker = worker;
        this.catalog = catalog;
        this.guards = guards;
        this.sdkInt = sdkInt;
        this.nativeLibraryDir = nativeLibraryDir;
        this.cacheDir = cacheDir;
        this.deadlineMs = deadlineMs;
        this.clock = clock;
    }

    /** The runtime, the device guards for each backend and the catalog, without starting the worker. */
    public JSONObject info() throws JSONException {
        JSONObject backends = new JSONObject();
        for (LitertBackend backend : LitertBackend.values()) backends.put(backend.wire, guards.availability(backend));
        return new JSONObject()
            .put("backend", "litertlm")
            .put("sdk_version", SDK_VERSION)
            .put("sdk_int", sdkInt)
            .put("min_sdk", LitertGuards.MIN_SDK)
            .put("abi", LitertGuards.ABI)
            .put("process", ":litert")
            .put("models_directory", catalog.root().getPath())
            .put("backends", backends)
            .put("busy", active.get() != null)
            .put("supports_streaming", false)
            .put("backend_reporting", new JSONObject()
                .put("cpu", "backend_effective is cpu and backend_verified is true: a CPU engine has no delegate that could run the model elsewhere")
                .put("gpu", "backend_effective is null and backend_verified is false: the SDK does not say which executor ran; backend_evidence lists what was observed")
                .put("npu", "backend_effective is null and backend_verified is false: the SDK does not say which executor ran; backend_evidence lists what was observed"))
            .put("finish_reason", "other: litertlm-android 0.18.0 does not report why a generation stopped; tool_calls when the model asked for tools");
    }

    /**
     * Unloads the model in the worker. A worker that is not running is not started for this. On GPU the worker ends
     * its process after replying, so the reply can be lost to that very death: the model is gone either way. When
     * the stop ends the process, the answer leaves only once the process is reported gone (or the bounded timer
     * runs out, and then {@code teardown_pending} says so); a CPU unload keeps the process alive and never waits.
     */
    public JSONObject unload() throws LitertFailure, JSONException {
        if (!worker.isConnected()) return new JSONObject().put("unloaded", false).put("state", "not_started");
        lastStopRequestedAt = clock.getAsLong();
        JSONObject out;
        try {
            out = new JSONObject(workerData(new JSONObject().put("op", "unload"))).put("reply_lost", false);
        } catch (LitertFailure f) {
            if (f.code != LitertErrorCode.MODEL_WORKER_DIED) throw f;
            out = new JSONObject().put("unloaded", true).put("via", "process_recycle").put("reply_lost", true);
        }
        boolean endsTheProcess = out.optBoolean("reply_lost", false) || "process_recycle".equals(out.optString("via", ""));
        out.put("teardown_pending", endsTheProcess && !awaitTeardown());
        return out;
    }

    /**
     * Ends the {@code :litert} process so the next request starts a clean one. It works on a worker that is busy.
     * A request in flight is lost (MODEL_WORKER_DIED for it); the reply to this call may be lost the same way, and
     * that is still a successful restart. The answer leaves once the process is reported gone, or with
     * {@code teardown_pending} when the bounded timer runs out before that.
     */
    public JSONObject restart() throws LitertFailure, JSONException {
        if (!worker.isConnected()) return new JSONObject().put("restarted", false).put("state", "not_started");
        lastStopRequestedAt = clock.getAsLong();
        JSONObject out;
        try {
            workerData(new JSONObject().put("op", "restart"));
            out = new JSONObject().put("restarted", true).put("reply_lost", false);
        } catch (LitertFailure f) {
            if (f.code != LitertErrorCode.MODEL_WORKER_DIED) throw f;
            out = new JSONObject().put("restarted", true).put("reply_lost", true);
        }
        // A restart always ends the process, so the wait applies every time.
        out.put("teardown_pending", !awaitTeardown());
        return out;
    }

    /** One short op to the worker; its {@code data} as text, or the worker's own typed failure. */
    private String workerData(JSONObject request) throws LitertFailure, JSONException {
        String op = request.getString("op");
        String reply = worker.call(request.toString(), STATUS_TIMEOUT_MS);
        JSONObject json;
        try {
            json = new JSONObject(reply);
        } catch (JSONException e) {
            throw new LitertFailure(LitertErrorCode.GENERATION_FAILED, null, "worker", null, "malformed reply from the :litert process", e);
        }
        if (!json.optBoolean("ok", false)) {
            throw new LitertFailure(LitertErrorCode.fromName(json.optString("error_name", "")), null,
                json.optString("phase", op), null, json.optString("error", "the :litert process reported an error"), null);
        }
        JSONObject data = json.optJSONObject("data");
        return (data == null ? new JSONObject() : data).toString();
    }

    /**
     * Cancels one request ({@code requestId}) or, with {@code all}, whatever the worker is running, including a
     * generation nobody is waiting for any more. A worker that is not running is not started for this.
     */
    public JSONObject cancel(String requestId, boolean all) throws LitertFailure, JSONException {
        if (all == (requestId != null)) throw LitertFailure.invalid("cancel needs exactly one of a request id and all");
        if (!all && !LitertParams.validRequestId(requestId)) {
            throw LitertFailure.invalid("request_id must be 1 to 64 characters of A-Z a-z 0-9 . _ -");
        }
        if (!worker.isConnected()) return new JSONObject().put("cancelled", false).put("state", "not_started");
        JSONObject request = new JSONObject().put("op", "cancel");
        if (all) request.put("all", true); else request.put("request_id", requestId);
        return new JSONObject(workerData(request));
    }

    public JSONObject models() throws JSONException {
        return catalog.list();
    }

    public JSONObject generate(LitertParams params) throws LitertFailure, JSONException {
        String backend = params.backend.wire;
        guards.require(params.backend);
        File model = catalog.resolve(params.model);
        if (!active.compareAndSet(null, params.requestId)) {
            throw new LitertFailure(LitertErrorCode.BUSY, backend, "generate", params.model,
                "request " + active.get() + " is still running", null);
        }
        try {
            JSONObject request = new JSONObject()
                .put("op", "generate")
                .put("request_id", params.requestId)
                .put("model", params.model)
                .put("model_path", model.getPath())
                .put("backend", backend)
                .put("context_tokens", params.contextTokens)
                .put("max_tokens", params.maxTokens)
                .put("temperature", params.temperature)
                .put("top_k", params.topK)
                .put("top_p", LitertParams.TOP_P)
                .put("seed", LitertParams.SEED)
                .put("native_library_dir", nativeLibraryDir)
                .put("cache_dir", cacheDir);
            // A chat travels with its roles; a plain prompt travels as a prompt. Never both.
            if (params.messages != null) request.put("messages", params.messages); else request.put("prompt", params.prompt);
            if (params.tools != null) request.put("tools", params.tools);
            // The precision the caller asked for. On GPU an unasked request is fp32: the GPU fp16 path corrupts
            // answers on some devices, so fp16 now travels only when it is asked for explicitly.
            LitertActivation activation = params.activation;
            if (activation == LitertActivation.DEFAULT && params.backend == LitertBackend.GPU) activation = LitertActivation.FP32;
            if (activation != LitertActivation.DEFAULT) request.put("activation", activation.wire);
            String reply;
            boolean retried = false;
            try {
                reply = callWorker(request, backend, params.model);
            } catch (LitertFailure f) {
                if (f.code != LitertErrorCode.MODEL_WORKER_DIED) throw f;
                // The one declared retry: a fresh worker answers the same request under the full deadline.
                retried = true;
                reply = callWorker(request, backend, params.model);
            }
            return unwrap(reply, params, retried);
        } finally {
            active.set(null);
        }
    }

    /** One call with the deadline: a DEADLINE_EXCEEDED asks the worker to cancel and says the worker may still run. */
    private String callWorker(JSONObject request, String backend, String model) throws LitertFailure {
        try {
            return worker.call(request.toString(), deadlineMs);
        } catch (LitertFailure f) {
            if (f.code == LitertErrorCode.DEADLINE_EXCEEDED) {
                worker.cancel(request.optString("request_id"));
                // The deadline frees this side, not the worker: its inference may still be running.
                throw new LitertFailure(f.code, backend, f.phase, model,
                    f.getMessage() + "; the :litert process may still be finishing this request, and a new one is answered BUSY until it has", f);
            }
            throw withContext(f, backend, model);
        }
    }

    /** What the worker says about itself, or the failure to reach it. */
    public JSONObject workerStatus() throws LitertFailure, JSONException {
        // Asking about the worker must not be the thing that starts it.
        if (!worker.isConnected()) return new JSONObject().put("state", "not_started");
        String reply;
        try {
            reply = worker.call(new JSONObject().put("op", "status").toString(), STATUS_TIMEOUT_MS);
        } catch (LitertFailure f) {
            // Just after an unload or a restart the process is on its way out: that is stopping, not unreachable.
            if (f.code == LitertErrorCode.MODEL_WORKER_DIED && clock.getAsLong() - lastStopRequestedAt <= STOP_WINDOW_MS) {
                return new JSONObject().put("state", "stopping");
            }
            throw f;
        }
        JSONObject json = new JSONObject(reply);
        return json.optJSONObject("data") == null ? new JSONObject() : json.getJSONObject("data");
    }

    /**
     * Waits, within a bounded timer, for an ending worker to be reported gone; false when the timer ran out first.
     * A CPU unload keeps the process alive on purpose, so callers wait only when the stop ends the process.
     */
    private boolean awaitTeardown() {
        long giveUpAt = clock.getAsLong() + TEARDOWN_TIMEOUT_MS;
        while (worker.isConnected()) {
            if (clock.getAsLong() >= giveUpAt) return false;
            try {
                Thread.sleep(TEARDOWN_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private JSONObject unwrap(String reply, LitertParams params, boolean retried) throws LitertFailure, JSONException {
        JSONObject json;
        try {
            json = new JSONObject(reply);
        } catch (JSONException e) {
            throw new LitertFailure(LitertErrorCode.GENERATION_FAILED, params.backend.wire, "worker", params.model,
                "malformed reply from the :litert process", e);
        }
        if (!json.optBoolean("ok", false)) {
            throw new LitertFailure(LitertErrorCode.fromName(json.optString("error_name", "")), params.backend.wire,
                json.optString("phase", "generate"), params.model, json.optString("error", "the :litert process reported an error"), null);
        }
        JSONObject data = json.getJSONObject("data");
        // The backend reported back is the one asked for: a reply cannot rename it.
        boolean verified = params.backend == LitertBackend.CPU && data.optBoolean("backend_verified", false);
        return new JSONObject()
            .put("request_id", params.requestId)
            .put("model", params.model)
            .put("text", data.optString("text", ""))
            .put("finish_reason", data.optString("finish_reason", "other"))
            .put("thinking", data.optString("thinking", ""))
            .put("tool_calls_count", data.optInt("tool_calls_count", 0))
            .put("tool_calls", data.optJSONArray("tool_calls") == null ? new org.json.JSONArray() : data.getJSONArray("tool_calls"))
            .put("backend_requested", params.backend.wire)
            .put("backend_effective", verified ? "cpu" : JSONObject.NULL)
            .put("backend_verified", verified)
            .put("backend_evidence", data.optString("backend_evidence", LitertRunner.EVIDENCE_NONE))
            .put("activation_requested", data.optString("activation_requested", LitertActivation.DEFAULT.wire))
            .put("engine_reused", data.optBoolean("engine_reused", false))
            .put("spawn_retried", retried)
            .put("params", new JSONObject()
                .put("max_tokens", params.maxTokens)
                .put("temperature", params.temperature)
                .put("top_k", params.topK)
                .put("top_p", LitertParams.TOP_P)
                .put("context_tokens", params.contextTokens))
            .put("timing", new JSONObject()
                .put("load_ms", data.optLong("load_ms", -1))
                .put("generate_ms", data.optLong("generate_ms", -1)));
    }

    private static LitertFailure withContext(LitertFailure f, String backend, String model) {
        return new LitertFailure(f.code, backend, f.phase, model, f.getMessage(), f);
    }
}
