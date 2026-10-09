package com.termux.app.terminal.ai.litert;

import org.json.JSONException;
import org.json.JSONObject;

/** The {@code litert.*} commands of the socket: argument validation in front of a {@link LitertEngine}. */
public final class LitertCommands {
    private final LitertEngine engine;
    private final LitertConfig config;

    public LitertCommands(LitertEngine engine) { this(engine, null); }

    public LitertCommands(LitertEngine engine, LitertConfig config) {
        this.engine = engine;
        this.config = config;
    }

    /** {@code request_id} (one request) or {@code all: true} (whatever runs); exactly one, nothing else. */
    private JSONObject cancel(JSONObject args) throws LitertFailure, JSONException {
        String requestId = null;
        boolean all = false;
        java.util.Iterator<String> keys = args == null ? java.util.Collections.<String>emptyIterator() : args.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = args.get(key);
            if ("request_id".equals(key) && value instanceof String) {
                requestId = (String) value;
            } else if ("all".equals(key) && Boolean.TRUE.equals(value)) {
                all = true;
            } else {
                throw LitertFailure.invalid("litert.cancel takes request_id (a string) or all (true), not '" + key + "'");
            }
        }
        return engine.cancel(requestId, all);
    }

    /** Show the settings, or with {@code set} change them. Never touches the worker. */
    private JSONObject configure(JSONObject args) throws LitertFailure, JSONException {
        if (config == null) throw LitertFailure.invalid("litert.config is not available here");
        java.util.Iterator<String> keys = args == null ? java.util.Collections.<String>emptyIterator() : args.keys();
        JSONObject set = null;
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"set".equals(key)) throw LitertFailure.invalid("litert.config takes only 'set', not '" + key + "'");
            set = args.optJSONObject("set");
            if (set == null) throw LitertFailure.invalid("'set' must be an object such as {\"idle_unload_ms\": 60000}");
        }
        return set == null ? config.describe() : config.set(set);
    }

    private static void requireNoArguments(String cmd, JSONObject args) throws LitertFailure {
        if (args != null && args.length() > 0) throw LitertFailure.invalid(cmd + " takes no arguments");
    }

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
            case "litert.unload":
                requireNoArguments(cmd, args);
                return engine.unload();
            case "litert.restart":
                requireNoArguments(cmd, args);
                return engine.restart();
            case "litert.cancel":
                return cancel(args);
            case "litert.config":
                return configure(args);
            case "litert.generate":
                // Every argument is checked before the worker is touched.
                return engine.generate(LitertParams.fromArgs(args));
            default:
                throw LitertFailure.invalid("Unknown command: " + cmd);
        }
    }
}
