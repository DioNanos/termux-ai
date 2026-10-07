package com.termux.app.terminal.ai.litert;

import org.json.JSONException;
import org.json.JSONObject;

/** The {@code litert.*} commands of the socket: argument validation in front of a {@link LitertEngine}. */
public final class LitertCommands {
    private final LitertEngine engine;

    public LitertCommands(LitertEngine engine) { this.engine = engine; }

    public static boolean handles(String cmd) { return cmd != null && cmd.startsWith("litert."); }

    /** @return the data of the answer. */
    public JSONObject handle(String cmd, JSONObject args) throws LitertFailure, JSONException {
        switch (cmd) {
            case "litert.info": {
                JSONObject info = engine.info();
                try {
                    info.put("worker", engine.workerStatus());
                } catch (LitertFailure f) {
                    // info must work when the worker cannot start: say so instead of failing.
                    info.put("worker", new JSONObject().put("state", "unreachable").put("error_name", f.code.name()).put("error", f.getMessage()));
                }
                return info;
            }
            case "litert.models":
                return engine.models();
            case "litert.generate":
                // Every argument is checked before the worker is touched.
                return engine.generate(LitertParams.fromArgs(args));
            default:
                throw LitertFailure.invalid("Unknown command: " + cmd);
        }
    }
}
