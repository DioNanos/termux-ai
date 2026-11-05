package com.termux.app.terminal.ai.compat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser of the am-args surface the pinned wrappers actually emit
 * (UPSTREAM_PIN.md): {@code --es k v} (short {@code -e k v}, used by
 * termux-clipboard-set.in), {@code --ei k v}, {@code --ez k v},
 * {@code --ef k v}, {@code --ela k v[,v…]}, {@code --eln k v[,v…]} and the
 * Volume action {@code -a set-volume}.
 *
 * Strictness rules: anything outside this surface is
 * rejected before dispatch ({@link CompatError#E_UNSUPPORTED_OPTION}), a
 * typed extra with a malformed value is rejected ({@code E_BAD_TYPE}), and
 * nothing is ever evaluated — values are data, not shell. Duplicated keys
 * are rejected: the pinned wrappers never emit duplicates, and a silent
 * last-wins would hide caller bugs.
 */
public final class CompatAmArgs {

    public final String action;                 // null or "set-volume"
    public final Map<String, Object> extras;    // ordered: key -> String/Integer/Boolean/Double/long[]/double[]

    private CompatAmArgs(String action, Map<String, Object> extras) {
        this.action = action;
        this.extras = extras;
    }

    public static final class ParseException extends Exception {
        public final CompatError code;
        public ParseException(CompatError code, String detail) {
            super(detail);
            this.code = code;
        }
    }

    private static String flagKind(String flag) {
        switch (flag) {
            case "-e":
            case "--es": return "es";
            case "--ei": return "ei";
            case "--ez": return "ez";
            case "--ef": return "ef";
            case "--ela": return "ela";
            case "--eln": return "eln";
            default: return null;
        }
    }

    public static CompatAmArgs parse(String[] argv, int from) throws ParseException {
        String action = null;
        Map<String, Object> extras = new LinkedHashMap<>();
        int i = from;
        while (i < argv.length) {
            String a = argv[i];
            if ("-a".equals(a)) {
                if (i + 1 >= argv.length) throw new ParseException(CompatError.E_BAD_USAGE, "-a needs a value");
                if (action != null) throw new ParseException(CompatError.E_BAD_USAGE, "duplicate -a");
                action = argv[i + 1];
                i += 2;
                continue;
            }
            String kind = flagKind(a);
            if (kind == null) throw new ParseException(CompatError.E_UNSUPPORTED_OPTION, a);
            if (i + 2 >= argv.length) throw new ParseException(CompatError.E_BAD_USAGE, a + " needs key and value");
            String key = argv[i + 1];
            String value = argv[i + 2];
            if (extras.containsKey(key)) throw new ParseException(CompatError.E_BAD_USAGE, "duplicate extra " + key);
            extras.put(key, decode(kind, key, value));
            i += 3;
        }
        return new CompatAmArgs(action, extras);
    }

    private static Object decode(String kind, String key, String value) throws ParseException {
        try {
            switch (kind) {
                case "es": return value;
                case "ei": return Integer.valueOf(value.trim());
                case "ez": {
                    String v = value.trim();
                    if (!v.equals("true") && !v.equals("false")) throw new NumberFormatException(value);
                    return Boolean.valueOf(v);
                }
                case "ef":
                    // org.json rejects non-finite doubles inside put(): a
                    // NaN/Infinity argv value is refused here, at the parse
                    // boundary, instead of crashing the request assembly.
                    return requireFinite(Double.valueOf(value.trim()), key, value);
                case "ela": {
                    List<Long> out = new ArrayList<>();
                    for (String part : value.split(",")) out.add(Long.valueOf(part.trim()));
                    long[] arr = new long[out.size()];
                    for (int j = 0; j < arr.length; j++) arr[j] = out.get(j);
                    return arr;
                }
                default: { // eln
                    List<Double> out = new ArrayList<>();
                    for (String part : value.split(",")) out.add(requireFinite(Double.valueOf(part.trim()), key, value));
                    double[] arr = new double[out.size()];
                    for (int j = 0; j < arr.length; j++) arr[j] = out.get(j);
                    return arr;
                }
            }
        } catch (NumberFormatException e) {
            throw new ParseException(CompatError.E_BAD_TYPE, "--" + kind + " " + key + ": " + value);
        }
    }

    private static double requireFinite(double d, String key, String value) throws ParseException {
        if (!Double.isFinite(d)) {
            throw new ParseException(CompatError.E_BAD_VALUE, "--ef/--eln " + key + ": " + value);
        }
        return d;
    }

    /**
     * Serializes the typed extras into the native request args object.
     * All doubles were already validated finite at parse time, so the
     * platform JSONException is a defensive frontier, not a live path.
     */
    public JSONObject toJsonArgs() throws org.json.JSONException {
        JSONObject args = new JSONObject();
        for (Map.Entry<String, Object> e : extras.entrySet()) {
            Object v = e.getValue();
            if (v instanceof long[]) {
                JSONArray a = new JSONArray();
                for (long x : (long[]) v) a.put(x);
                args.put(e.getKey(), a);
            } else if (v instanceof double[]) {
                JSONArray a = new JSONArray();
                for (double x : (double[]) v) a.put(x);
                args.put(e.getKey(), a);
            } else {
                args.put(e.getKey(), v);
            }
        }
        return args;
    }
}
