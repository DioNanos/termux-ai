package org.json;

// Functional JVM stub of org.json.JSONObject with the FAITHFUL Android
// signatures: every put/get/getBoolean/getString/... declares the
// checked JSONException, put(String,double) rejects non-finite doubles, and
// put(name, null) removes the mapping exactly like the platform
// implementation. Real semantics come from the platform in CI; this stub
// exists so the main classes that must compile against Android do not pass
// the JVM check with a softened contract.
import java.util.LinkedHashMap;
import java.util.Map;

public class JSONObject {
    /** Platform sentinel for an explicit JSON null (equality checks used by callers). */
    public static final Object NULL = new Null();

    private static final class Null {
        @Override public boolean equals(Object other) { return other == this || other == null; }
        @Override public String toString() { return "null"; }
    }

    private final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() {}
    public JSONObject(String json) throws JSONException { this(new JSONTokener(json)); }
    public JSONObject(JSONTokener t) throws JSONException {
        Object v;
        try {
            v = t.nextValue();
        } catch (RuntimeException e) {
            throw new JSONException(e.getMessage());
        }
        if (!(v instanceof JSONObject)) throw new JSONException("not an object");
        map.putAll(((JSONObject) v).map);
    }

    public boolean has(String key) { return map.containsKey(key); }
    public boolean isNull(String key) { return map.get(key) == null; }
    public Object get(String key) throws JSONException {
        Object v = map.get(key);
        if (v == null) throw new JSONException("no value for " + key);
        return v;
    }
    public Object opt(String key) { return map.get(key); }
    public String optString(String key) { return optString(key, ""); }
    public String optString(String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : String.valueOf(v);
    }
    public int optInt(String key) { return optInt(key, 0); }
    public int optInt(String key, int fallback) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }
    public double optDouble(String key) { return optDouble(key, Double.NaN); }
    public double optDouble(String key, double fallback) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }
    public long optLong(String key) { Object v = map.get(key); return v instanceof Number ? ((Number) v).longValue() : 0L; }
    public long optLong(String key, long fallback) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }
    public boolean optBoolean(String key) { return optBoolean(key, false); }
    public boolean optBoolean(String key, boolean fallback) {
        Object v = map.get(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }
    public JSONObject optJSONObject(String key) {
        Object v = map.get(key);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }
    public JSONArray optJSONArray(String key) {
        Object v = map.get(key);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }
    public int length() { return map.size(); }
    public java.util.Iterator<String> keys() { return map.keySet().iterator(); }
    public java.util.Set<String> keySet() { return map.keySet(); }
    public int getInt(String key) throws JSONException {
        Object v = get(key);
        if (!(v instanceof Number)) throw new JSONException("not an int");
        return ((Number) v).intValue();
    }
    public double getDouble(String key) throws JSONException {
        Object v = get(key);
        if (!(v instanceof Number)) throw new JSONException("not a double");
        return ((Number) v).doubleValue();
    }
    public long getLong(String key) throws JSONException {
        Object v = get(key);
        if (!(v instanceof Number)) throw new JSONException("not a long");
        return ((Number) v).longValue();
    }
    public boolean getBoolean(String key) throws JSONException {
        Object v = get(key);
        if (!(v instanceof Boolean)) throw new JSONException("not a boolean");
        return (Boolean) v;
    }
    public String getString(String key) throws JSONException {
        Object v = get(key);
        if (!(v instanceof String)) throw new JSONException("not a string");
        return (String) v;
    }
    public JSONObject getJSONObject(String key) throws JSONException {
        Object v = get(key);
        if (!(v instanceof JSONObject)) throw new JSONException("not an object");
        return (JSONObject) v;
    }
    public JSONArray getJSONArray(String key) throws JSONException {
        Object v = get(key);
        if (!(v instanceof JSONArray)) throw new JSONException("not an array");
        return (JSONArray) v;
    }

    public Object remove(String key) { return map.remove(key); }

    public JSONObject put(String key, Object v) throws JSONException {
        if (v == null) { // platform: put(name, null) removes the mapping
            map.remove(key);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) throw new JSONException("Forbidden numeric value " + d);
            map.put(key, v);
        } else {
            map.put(key, v);
        }
        return this;
    }
    public JSONObject put(String key, int v) throws JSONException { return put(key, (Object) Integer.valueOf(v)); }
    public JSONObject put(String key, long v) throws JSONException { return put(key, (Object) Long.valueOf(v)); }
    public JSONObject put(String key, boolean v) throws JSONException { return put(key, (Object) Boolean.valueOf(v)); }
    public JSONObject put(String key, double v) throws JSONException {
        return put(key, (Object) Double.valueOf(v));
    }

    public String toString() {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(JSONTokener.quote(e.getKey())).append(':').append(JSONTokener.valueToString(e.getValue()));
        }
        return b.append('}').toString();
    }
}
