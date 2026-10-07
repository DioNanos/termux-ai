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
            .put("backend_requested", params.backend.wire)
            .put("backend_effective", verified ? "cpu" : JSONObject.NULL)
            .put("backend_verified", verified)
            .put("backend_evidence", data.optString("backend_evidence", LitertRunner.EVIDENCE_NONE))
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
