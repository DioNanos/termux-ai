package org.json;

// Functional JVM stub of org.json.JSONArray with the FAITHFUL Android
// signatures: only put(double) declares the checked
// JSONException (and rejects non-finite doubles); put(int/long/boolean/
// Object) do not declare it, exactly like the platform class.
// https://developer.android.com/reference/org/json/JSONArray
import java.util.ArrayList;
import java.util.List;

public class JSONArray {
    private final List<Object> list = new ArrayList<>();

    public JSONArray() {}
    public JSONArray(Object array) throws JSONException {
        if (!(array instanceof Object[])) throw new JSONException("not an array");
        for (Object v : (Object[]) array) list.add(v);
    }
    public JSONArray(JSONTokener t) throws JSONException {
        Object v;
        try {
            v = t.nextValue();
        } catch (RuntimeException e) {
            throw new JSONException(e.getMessage());
        }
        if (!(v instanceof JSONArray)) throw new JSONException("not an array");
        list.addAll(((JSONArray) v).list);
    }

    public int length() { return list.size(); }
    public Object get(int i) { return list.get(i); }
    public Object opt(int i) { return i < 0 || i >= list.size() ? null : list.get(i); }
    public String optString(int i, String fallback) { Object v = opt(i); return v == null ? fallback : String.valueOf(v); }
    public int optInt(int i) { Object v = opt(i); return v instanceof Number ? ((Number) v).intValue() : 0; }
    public boolean optBoolean(int i) { Object v = opt(i); return v instanceof Boolean && (Boolean) v; }
    public JSONObject optJSONObject(int i) { Object v = opt(i); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(int i) { Object v = opt(i); return v instanceof JSONArray ? (JSONArray) v : null; }

    public JSONArray put(Object v) { list.add(v); return this; }
    public JSONArray put(int v) { return put((Object) Integer.valueOf(v)); }
    public JSONArray put(long v) { return put((Object) Long.valueOf(v)); }
    public JSONArray put(boolean v) { return put((Object) Boolean.valueOf(v)); }
    public JSONArray put(double v) throws JSONException {
        if (!Double.isFinite(v)) throw new JSONException("Forbidden numeric value " + v);
        return put((Object) Double.valueOf(v));
    }

    public String toString() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) b.append(',');
            b.append(JSONTokener.valueToString(list.get(i)));
        }
        return b.append(']').toString();
    }
}
