package com.termux.app.terminal.ai.litert;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@code litert.*} logic of the main process. It validates, checks the device, finds the model and hands the
 * work to the {@link LitertWorker} in the {@code :litert} process; no SDK class is loaded here. A request is sent
 * once: a worker that dies is reported as MODEL_WORKER_DIED and nothing is replayed or moved to another backend.
 */
public final class LitertEngine {
    public static final String SDK_VERSION = "litertlm-android 0.18.0";
    /** The wall-clock limit of one generation; the worker is asked to cancel when it passes. */
    public static final long GENERATE_DEADLINE_MS = 300_000L;
    private static final long STATUS_TIMEOUT_MS = 5_000L;

    private final LitertWorker worker;
    private final LitertModelCatalog catalog;
    private final LitertGuards guards;
    private final int sdkInt;
    private final String nativeLibraryDir;
    private final String cacheDir;
    private final long deadlineMs;
    private final AtomicReference<String> active = new AtomicReference<>(null);

    public LitertEngine(LitertWorker worker, LitertModelCatalog catalog, LitertGuards guards, int sdkInt,
                        String nativeLibraryDir, String cacheDir, long deadlineMs) {
        this.worker = worker;
        this.catalog = catalog;
        this.guards = guards;
        this.sdkInt = sdkInt;
        this.nativeLibraryDir = nativeLibraryDir;
        this.cacheDir = cacheDir;
        this.deadlineMs = deadlineMs;
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
            .put("finish_reason", "always other: litertlm-android 0.18.0 does not report why a generation stopped");
    }

    /**
     * Unloads the model in the worker. A worker that is not running is not started for this. On GPU the worker ends
     * its process after replying, so the reply can be lost to that very death: the model is gone either way.
     */
    public JSONObject unload() throws LitertFailure, JSONException {
        if (!worker.isConnected()) return new JSONObject().put("unloaded", false).put("state", "not_started");
        try {
            return new JSONObject(workerData(new JSONObject().put("op", "unload"))).put("reply_lost", false);
        } catch (LitertFailure f) {
            if (f.code != LitertErrorCode.MODEL_WORKER_DIED) throw f;
            return new JSONObject().put("unloaded", true).put("via", "process_recycle").put("reply_lost", true);
        }
    }

    /**
     * Ends the {@code :litert} process so the next request starts a clean one. It works on a worker that is busy.
     * A request in flight is lost (MODEL_WORKER_DIED for it); the reply to this call may be lost the same way, and
     * that is still a successful restart.
     */
    public JSONObject restart() throws LitertFailure, JSONException {
        if (!worker.isConnected()) return new JSONObject().put("restarted", false).put("state", "not_started");
        try {
            workerData(new JSONObject().put("op", "restart"));
            return new JSONObject().put("restarted", true).put("reply_lost", false);
        } catch (LitertFailure f) {
            if (f.code != LitertErrorCode.MODEL_WORKER_DIED) throw f;
            return new JSONObject().put("restarted", true).put("reply_lost", true);
        }
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
                .put("context_tokens", LitertParams.CONTEXT_TOKENS)
                .put("prompt", params.prompt)
                .put("max_tokens", params.maxTokens)
                .put("temperature", params.temperature)
                .put("top_k", params.topK)
                .put("top_p", LitertParams.TOP_P)
                .put("seed", LitertParams.SEED)
                .put("native_library_dir", nativeLibraryDir)
                .put("cache_dir", cacheDir);
            // Only a precision that was asked for travels: an unasked request is exactly what it was before.
            if (params.activation != LitertActivation.DEFAULT) request.put("activation", params.activation.wire);
            String reply;
            try {
                reply = worker.call(request.toString(), deadlineMs);
            } catch (LitertFailure f) {
                if (f.code == LitertErrorCode.DEADLINE_EXCEEDED) {
                    worker.cancel(params.requestId);
                    // The deadline frees this side, not the worker: its inference may still be running.
                    throw new LitertFailure(f.code, backend, f.phase, params.model,
                        f.getMessage() + "; the :litert process may still be finishing this request, and a new one is answered BUSY until it has", f);
                }
                throw withContext(f, backend, params.model);
            }
            return unwrap(reply, params);
        } finally {
            active.set(null);
        }
    }

    /** What the worker says about itself, or the failure to reach it. */
    public JSONObject workerStatus() throws LitertFailure, JSONException {
        // Asking about the worker must not be the thing that starts it.
        if (!worker.isConnected()) return new JSONObject().put("state", "not_started");
        String reply = worker.call(new JSONObject().put("op", "status").toString(), STATUS_TIMEOUT_MS);
        JSONObject json = new JSONObject(reply);
        return json.optJSONObject("data") == null ? new JSONObject() : json.getJSONObject("data");
    }

    private JSONObject unwrap(String reply, LitertParams params) throws LitertFailure, JSONException {
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
            .put("backend_requested", params.backend.wire)
            .put("backend_effective", verified ? "cpu" : JSONObject.NULL)
            .put("backend_verified", verified)
            .put("backend_evidence", data.optString("backend_evidence", LitertRunner.EVIDENCE_NONE))
            .put("activation_requested", data.optString("activation_requested", LitertActivation.DEFAULT.wire))
            .put("engine_reused", data.optBoolean("engine_reused", false))
            .put("params", new JSONObject()
                .put("max_tokens", params.maxTokens)
                .put("temperature", params.temperature)
                .put("top_k", params.topK)
                .put("top_p", LitertParams.TOP_P)
                .put("context_tokens", LitertParams.CONTEXT_TOKENS))
            .put("timing", new JSONObject()
                .put("load_ms", data.optLong("load_ms", -1))
                .put("generate_ms", data.optLong("generate_ms", -1)));
    }

    private static LitertFailure withContext(LitertFailure f, String backend, String model) {
        return new LitertFailure(f.code, backend, f.phase, model, f.getMessage(), f);
    }
}
