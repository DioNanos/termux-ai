package com.termux.app.terminal.ai;

import org.json.JSONObject;

/**
 * The {@code aicore.*} commands of the socket: argument validation in front of
 * an {@link AiCoreEngine}. Validation errors are {@link IllegalArgumentException}s
 * and nothing reaches the model when one is thrown.
 */
public final class AiCoreCommands {

    private final AiCoreEngine engine;

    public AiCoreCommands(AiCoreEngine engine) { this.engine = engine; }

    public static boolean handles(String cmd) { return cmd != null && cmd.startsWith("aicore."); }

    /** @return the data of the answer. */
    public JSONObject handle(String cmd, JSONObject args) throws Exception {
        switch (cmd) {
            case "aicore.info":
                return engine.info(selection(args));
            case "aicore.models":
                return engine.models();
            case "aicore.download":
                return engine.download(selection(args));
            case "aicore.generate": {
                // Every argument is checked before the model is touched.
                GenParams params = GenParams.fromArgs(args);
                ModelSelection selection = selection(args);
                return engine.generate(params, selection);
            }
            default:
                throw new IllegalArgumentException("Unknown command: " + cmd);
        }
    }

    /** The socket answer for an AICore failure: name, code and the SDK's retry delay. */
    public static String errorJson(AiCoreFailure failure) {
        try {
            JSONObject json = new JSONObject()
                .put("ok", false)
                .put("error", failure.getMessage())
                .put("error_name", failure.name)
                .put("error_code", failure.code);
            if (failure.retryDelayMs >= 0) json.put("retry_delay_ms", failure.retryDelayMs);
            return json.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"internal error\"}";
        }
    }

    static ModelSelection selection(JSONObject args) {
        return ModelSelection.parse(optionalString(args, "stage"), optionalString(args, "preference"));
    }

    private static String optionalString(JSONObject args, String name) {
        if (!args.has(name) || args.isNull(name)) return null;
        Object raw = args.opt(name);
        if (!(raw instanceof String)) throw new IllegalArgumentException(name + " must be a string");
        return (String) raw;
    }
}
