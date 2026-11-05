package com.termux.app.terminal.ai.litert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool arguments between JSON and the plain values the SDK takes. Faithful in both directions: a JSON null stays a
 * null (in an object and in an array, so no index shifts and no key disappears), order is kept, nothing is dropped.
 */
public final class LitertJson {
    /** Deeper than any real argument; the chats that reach this are already limited to a lower depth. */
    private static final int MAX_DEPTH = 128;

    private LitertJson() {}

    public static Map<String, Object> toMap(JSONObject json) {
        return toMap(json, 0);
    }

    public static JSONObject fromMap(Map<String, ?> map) {
        return fromMap(map, 0);
    }

    private static Map<String, Object> toMap(JSONObject json, int depth) {
        check(depth);
        Map<String, Object> out = new LinkedHashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            out.put(key, plain(json.opt(key), depth + 1));
        }
        return out;
    }

    private static Object plain(Object value, int depth) {
        if (value == null || JSONObject.NULL.equals(value)) return null;
        if (value instanceof JSONObject) return toMap((JSONObject) value, depth);
        if (value instanceof JSONArray) {
            check(depth);
            JSONArray array = (JSONArray) value;
            List<Object> out = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) out.add(plain(array.opt(i), depth + 1));
            return out;
        }
        return value;
    }

    private static JSONObject fromMap(Map<String, ?> map, int depth) {
        check(depth);
        JSONObject out = new JSONObject();
        try {
            for (Map.Entry<String, ?> entry : map.entrySet()) out.put(entry.getKey(), json(entry.getValue(), depth + 1));
        } catch (JSONException e) {
            throw new IllegalStateException("tool arguments cannot be written as JSON: " + e.getMessage(), e);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object json(Object value, int depth) throws JSONException {
        if (value == null) return JSONObject.NULL;
        if (value instanceof Map) return fromMap((Map<String, ?>) value, depth);
        if (value instanceof Iterable) {
            check(depth);
            JSONArray out = new JSONArray();
            for (Object item : (Iterable<?>) value) out.put(json(item, depth + 1));
            return out;
        }
        return value;
    }

    private static void check(int depth) {
        if (depth > MAX_DEPTH) throw new IllegalStateException("tool arguments are nested more than " + MAX_DEPTH + " levels deep");
    }
}
