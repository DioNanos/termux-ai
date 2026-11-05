package org.json;

/**
 * Signature stub of the platform org.json.JSONException
 * (https://developer.android.com/reference/org/json/JSONException).
 *
 * On Android this is a CHECKED exception declared by JSONObject/JSONArray
 * mutation and getters; the previous harness stub did not declare it, which
 * hid catch/throws mistakes in the compat main classes. This stub
 * restores the checked contract as DOCUMENTED, so javac on the JVM enforces
 * it against the stub tree — still a stub, not the platform org.json; the
 * real exception surface stays CI material.
 */
public class JSONException extends Exception {

    public JSONException(String s) {
        super(s);
    }
}
