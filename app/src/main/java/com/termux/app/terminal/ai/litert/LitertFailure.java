package com.termux.app.terminal.ai.litert;

import org.json.JSONException;
import org.json.JSONObject;

/** A {@code litert.*} failure: stable code, the backend that was asked for, the phase and the SDK's own message. */
public class LitertFailure extends Exception {
    private static final long serialVersionUID = 1L;

    public final LitertErrorCode code;
    /** The backend of the request, or null when the request never got that far. */
    public final String backendRequested;
    /** One of validate, guard, catalog, init, generate, worker. */
    public final String phase;
    public final String model;

    public LitertFailure(LitertErrorCode code, String backendRequested, String phase, String model,
                         String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.backendRequested = backendRequested;
        this.phase = phase;
        this.model = model;
    }

    public static LitertFailure invalid(String message) {
        return new LitertFailure(LitertErrorCode.INVALID_ARGUMENT, null, "validate", null, message, null);
    }

    /** The socket answer for this failure. */
    public String toJson() {
        try {
            JSONObject json = new JSONObject()
                .put("ok", false)
                .put("error", getMessage())
                .put("error_name", code.name())
                .put("error_code", code.number)
                .put("phase", phase);
            if (backendRequested != null) json.put("backend_requested", backendRequested);
            if (model != null) json.put("model", model);
            return json.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":\"internal error\"}";
        }
    }
}
