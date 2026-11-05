package com.termux.app.terminal.ai.compat;

import com.termux.app.terminal.ai.compat.CompatMethod.Branch;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Per-method result adapter: native envelope → upstream compat stdout.
 *
 * The adapter receives the VALIDATED REQUEST BRANCH — never a
 * guess from the payload shape — and enforces that branch's schema on the
 * native data: required fields must be present with the right types, and
 * nothing is defaulted, renamed-around or invented. A field the backend did
 * not measure is an explicit error, not an empty array or an opt* fallback.
 *
 * Byte-exactness rules come from GOLDEN_CONTRACT.md §1–2 (serialization read
 * at the upstream pin): 2-space indentation via an android.util.JsonWriter
 * equivalent, the per-payload trailing-newline table, raw clipboard text with
 * no added newline, plain-text lines for NotificationChannel, empty stdout
 * for the action methods. Battery emits in the upstream writer's order
 * (BatteryStatusAPI.java:149–168); camera arrays keep the mixed
 * string-or-number element types of the pin writer (CameraInfoAPI.java).
 *
 * Nested bridge errors (data.error under ok:true — Volume/Brightness/Torch)
 * are explicit compat errors: empty stdout, enumerated stderr, nonzero exit.
 * The legacy envelope is not touched. The extraction of data.cameras /
 * data.streams / data.engines into naked arrays happens here, never on the
 * native CLI.
 */
public final class CompatResultAdapter {

    private CompatResultAdapter() {}

    /** Adapted outcome. stdout is what the method contract requires (may be empty). */
    public static final class Outcome {
        public final byte[] stdout;
        public Outcome(byte[] stdout) {
            this.stdout = stdout;
        }
    }

    public static final class AdapterException extends Exception {
        public final CompatError code;
        public AdapterException(CompatError code, String detail) {
            super(detail);
            this.code = code;
        }
    }

    /**
     * @param plan          the validated request plan (branch context).
     * @param envelopeData  the data object of a validated ok:true envelope.
     */
    public static Outcome adapt(CompatMethod.Plan plan, JSONObject envelopeData) throws AdapterException {
        // Nested errors are errors, never silent successes.
        String nested = envelopeData.optString("error", null);
        if (nested != null && !nested.isEmpty()) {
            throw new AdapterException(CompatError.E_BRIDGE_ERROR, "nested data.error");
        }
        switch (plan.branch) {
            case BATTERY_STATUS: return battery(envelopeData);
            case AUDIO_INFO: return audioInfo(envelopeData);
            case CAMERA_INFO: return cameraInfo(envelopeData);
            case WIFI_CONNECTION_INFO: return wifi(envelopeData);
            case CLIPBOARD_GET: return clipboardGet(envelopeData);
            case TTS_ENGINES: return ttsEngines(envelopeData);
            case VOLUME_LIST: return volumeList(envelopeData);
            case CHANNEL_CREATE: return notificationChannel(envelopeData, true);
            case CHANNEL_DELETE: return notificationChannel(envelopeData, false);
            default:
                // Action branches (clipboard set, brightness, notification
                // show/remove, tts speak, toast, torch, vibrate, volume set):
                // the upstream contract is empty stdout — but only after the
                // branch's own schema holds.
                return action(plan.branch, envelopeData);
        }
    }

    // --- payload adapters -------------------------------------------------

    /**
     * Upstream writer order (BatteryStatusAPI.java:149–168): present,
     * technology, health, plugged, status, temperature, voltage, current,
     * current_average, percentage, level, scale, charge_counter; every key
     * only when it was actually measured (put*IfSet at the pin).
     */
    private static Outcome battery(JSONObject d) throws AdapterException {
        // the always-present native keys of the backend contract
        int capacity = requireInt(d, "level");
        requireBool(d, "charging");
        requireInt(d, "current_ua");
        requireLong(d, "charge_uah"); // getLongProperty: full long range

        IndentWriter w = new IndentWriter();
        w.beginObject();
        optBool(w, "present", d);
        optString(w, "technology", d);
        optString(w, "health", d);
        optString(w, "plugged", d);
        optString(w, "status", d);
        // upstream: double, 1 decimal (temperature/10 at the source)
        if (d.has("temperature")) w.doubleKey("temperature", requireFiniteDouble(d, "temperature"));
        optInt(w, "voltage", d);
        w.intKeyRaw("current", requireInt(d, "current_ua"));
        optInt(w, "current_average", d);
        // upstream percentage IS the capacity property == native "level";
        // unknown (MIN_VALUE) stays absent exactly like the pin's null
        if (capacity != Integer.MIN_VALUE) w.intKeyRaw("percentage", capacity);
        optInt(w, "level", "raw_level", d);
        optInt(w, "scale", d);
        w.longKeyRaw("charge_counter", requireLong(d, "charge_uah"));
        w.endObject();
        return new Outcome(w.bytesWithNewline());
    }

    /** Upstream writer order (AudioAPI.java:68–88). */
    private static Outcome audioInfo(JSONObject d) throws AdapterException {
        IndentWriter w = new IndentWriter();
        w.beginObject();
        w.stringKeyRaw("PROPERTY_OUTPUT_SAMPLE_RATE", requireString(d, "sample_rate"));
        w.stringKeyRaw("PROPERTY_OUTPUT_FRAMES_PER_BUFFER", requireString(d, "frames_per_buffer"));
        optInt(w, "AUDIOTRACK_SAMPLE_RATE", "audiotrack_sample_rate", d);
        optInt(w, "AUDIOTRACK_BUFFER_SIZE_IN_FRAMES", "audiotrack_buffer_size_in_frames", d);
        w.booleanKeyRaw("BLUETOOTH_A2DP_IS_ON", requireBool(d, "bluetooth_a2dp_on"));
        w.booleanKeyRaw("WIREDHEADSET_IS_CONNECTED", requireBool(d, "wired_headset_on"));
        w.endObject();
        return new Outcome(w.bytesWithNewline());
    }

    /**
     * Naked array (CameraInfoAPI.java:31–145). Per camera: facing is the
     * lowercase string for back/front and the int for any other lens; AE
     * modes and capabilities keep the pin's mixed string-or-number element
     * types (unknown values are NUMBERS at the pin, not invented tokens).
     */
    private static Outcome cameraInfo(JSONObject d) throws AdapterException {
        JSONArray cameras = requireArray(d, "cameras");
        IndentWriter w = new IndentWriter();
        w.beginArray();
        for (int i = 0; i < cameras.length(); i++) {
            JSONObject c = optObject(cameras, i, "cameras[" + i + "]");
            w.beginObjectInArray();
            w.stringKeyRaw("id", requireString(c, "id"));
            String facing = c.optString("facing", "");
            Integer facingId = c.has("facing_id") ? Integer.valueOf(requireInt(c, "facing_id")) : null;
            if (facing.equals("back") || facing.equals("front")) {
                w.stringKeyRaw("facing", facing);
            } else if (facingId != null) {
                // upstream emits the int for a non-front/back lens
                w.intKeyRaw("facing", facingId);
            } else {
                throw new AdapterException(CompatError.E_INVALID_RESPONSE, "facing not measurable");
            }
            writeSizeArray(w, "jpeg_output_sizes", c);
            writeNumberArray(w, "focal_lengths", c);
            writeMixedArray(w, "auto_exposure_modes", c);
            JSONObject ps = requireObject(c, "physical_size");
            w.name("physical_size");
            w.beginObjectValue();
            w.doubleKeyRaw("width", requireFiniteDouble(ps, "width"));
            w.doubleKeyRaw("height", requireFiniteDouble(ps, "height"));
            w.endObject();
            writeMixedArray(w, "capabilities", c);
            w.endObject();
        }
        w.endArray();
        return new Outcome(w.bytesWithNewline());
    }

    private static Outcome wifi(JSONObject d) throws AdapterException {
        // The backend's own metadata tells a genuinely absent WifiInfo
        // (unconnected branch of the pin) apart from a missing or redacted
        // FIELD, which is a contract violation, not a connection state
        // No inference from the ssid text anymore.
        if (!d.has("wifi_info_present")) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, "wifi metadata missing");
        }
        boolean infoPresent = requireBool(d, "wifi_info_present");
        if (!infoPresent) {
            // info == null branch of the pin (WifiAPI.java:34–35)
            IndentWriter w = new IndentWriter();
            w.beginObject();
            w.stringKeyRaw("API_ERROR", "No current connection");
            w.endObject();
            return new Outcome(w.bytesWithNewline());
        }
        // measured WifiInfo: these fields are always real when info != null;
        // bssid/mac_address may legitimately be absent (null from the API)
        IndentWriter w = new IndentWriter();
        w.beginObject();
        optString(w, "bssid", d);
        w.intKeyRaw("frequency_mhz", requireInt(d, "frequency_mhz"));
        w.stringKeyRaw("ip", requireString(d, "ip"));
        w.intKeyRaw("link_speed_mbps", requireInt(d, "link_speed_mbps"));
        optString(w, "mac_address", d);
        w.intKeyRaw("network_id", requireInt(d, "network_id"));
        w.intKeyRaw("rssi", requireInt(d, "rssi"));
        // measured as-is, quotes stripped: a redacted "<unknown ssid>" is
        // delivered as the measured value, not reinterpreted
        w.stringKeyRaw("ssid", stripQuotes(requireString(d, "ssid")));
        w.booleanKeyRaw("ssid_hidden", requireBool(d, "ssid_hidden"));
        w.stringKeyRaw("supplicant_state", requireString(d, "supplicant_state"));
        w.endObject();
        return new Outcome(w.bytesWithNewline());
    }

    private static Outcome clipboardGet(JSONObject d) throws AdapterException {
        // raw clip text, out.print semantics: no trailing newline added
        return new Outcome(requireString(d, "text").getBytes(StandardCharsets.UTF_8));
    }

    private static Outcome ttsEngines(JSONObject d) throws AdapterException {
        // engines invocation: naked array, no trailing newline (GOLDEN §1)
        JSONArray engines = requireArray(d, "engines");
        IndentWriter w = new IndentWriter();
        w.beginArray();
        for (int i = 0; i < engines.length(); i++) {
            JSONObject e = optObject(engines, i, "engines[" + i + "]");
            w.beginObjectInArray();
            w.stringKeyRaw("name", requireString(e, "name"));
            w.stringKeyRaw("label", requireString(e, "label"));
            w.booleanKeyRaw("default", requireBool(e, "default"));
            w.endObject();
        }
        w.endArray();
        return new Outcome(w.bytes()); // no trailing newline
    }

    private static Outcome volumeList(JSONObject d) throws AdapterException {
        // list action: array without trailing newline (VolumeAPI.java:87)
        JSONArray streams = requireArray(d, "streams");
        IndentWriter w = new IndentWriter();
        w.beginArray();
        for (int i = 0; i < streams.length(); i++) {
            JSONObject s = optObject(streams, i, "streams[" + i + "]");
            w.beginObjectInArray();
            w.stringKeyRaw("stream", requireString(s, "stream"));
            w.intKeyRaw("volume", requireInt(s, "volume"));
            w.intKeyRaw("max_volume", requireInt(s, "max_volume"));
            w.endObject();
        }
        w.endArray();
        return new Outcome(w.bytes()); // no trailing newline
    }

    private static Outcome notificationChannel(JSONObject d, boolean create) throws AdapterException {
        String status = requireString(d, "status");
        if (create) {
            if (!status.equals("created")) {
                // "ignored" (Android < 8) and anything else: explicit error,
                // never a guessed text line — the exact upstream wording of
                // that branch is not pinned. The native ignored branch
                // carries status/reason only, so id is demanded per branch.
                throw new AdapterException(CompatError.E_BRIDGE_ERROR, "channel status " + status);
            }
            return textLine("Created channel with id \"" + requireString(d, "id") + "\" and name \""
                + requireString(d, "name") + "\".");
        }
        if (!status.equals("deleted")) {
            throw new AdapterException(CompatError.E_BRIDGE_ERROR, "channel status " + status);
        }
        return textLine("Deleted channel with id \"" + requireString(d, "id") + "\".");
    }

    /** Action branches: empty stdout once the branch's own schema holds. */
    private static Outcome action(Branch branch, JSONObject d) throws AdapterException {
        switch (branch) {
            case CLIPBOARD_SET: requireString(d, "status"); break;
            case BRIGHTNESS_SET: requireString(d, "mode"); break;
            case NOTIFICATION_SHOW: requireString(d, "status"); break;
            case NOTIFICATION_REMOVE: requireString(d, "status"); break;
            case TTS_SPEAK:
                requireBool(d, "queued");
                requireInt(d, "chars");
                break;
            case TOAST_SHOW: requireString(d, "status"); break;
            case TORCH_SET: requireString(d, "status"); break;
            case VIBRATE: requireInt(d, "duration_ms"); break;
            case VOLUME_SET:
                requireString(d, "stream");
                requireInt(d, "volume");
                requireInt(d, "max_volume");
                break;
            default:
                throw new AdapterException(CompatError.E_INVALID_RESPONSE, "branch not an action");
        }
        return new Outcome(new byte[0]);
    }

    private static Outcome textLine(String line) {
        return new Outcome((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // --- per-branch array emitters (present-or-error, never invented) -------

    private static void writeSizeArray(IndentWriter w, String key, JSONObject c) throws AdapterException {
        JSONArray a = requireArray(c, key);
        w.name(key);
        w.beginArrayValue();
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = optObject(a, i, key + "[" + i + "]");
            w.beginObjectInArray();
            w.intKeyRaw("width", requireInt(s, "width"));
            w.intKeyRaw("height", requireInt(s, "height"));
            w.endObject();
        }
        w.endArray();
    }

    private static void writeNumberArray(IndentWriter w, String key, JSONObject c) throws AdapterException {
        JSONArray a = requireArray(c, key);
        w.name(key);
        w.beginArrayValue();
        for (int i = 0; i < a.length(); i++) {
            Object v = a.opt(i);
            if (!(v instanceof Number)) {
                throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " element not a number");
            }
            double dv = ((Number) v).doubleValue();
            if (!Double.isFinite(dv)) {
                throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " element not finite");
            }
            w.arrayNumberElement((Number) v);
        }
        w.endArray();
    }

    /** String-or-number elements, exactly as the pin writer emits them. */
    private static void writeMixedArray(IndentWriter w, String key, JSONObject c) throws AdapterException {
        JSONArray a = requireArray(c, key);
        w.name(key);
        w.beginArrayValue();
        for (int i = 0; i < a.length(); i++) {
            Object v = a.opt(i);
            if (v instanceof String) {
                w.arrayStringElement((String) v);
            } else if (v instanceof Number) {
                double dv = ((Number) v).doubleValue();
                if (!Double.isFinite(dv)) {
                    throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " element not finite");
                }
                w.arrayNumberElement((Number) v);
            } else {
                throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " element not string or number");
            }
        }
        w.endArray();
    }

    // --- schema helpers: required means present AND of the right type -------

    /**
     * Numeric contract per field: every int field of the native
     * contract is emitted from an int Android API (see TermuxAiSocketServer:
     * stream volumes, battery int properties, camera sizes, WifiInfo getters,
     * AudioTrack getters, text lengths), so a JSON number is a valid int
     * measure only when it is FINITE, EXACTLY INTEGRAL and inside the signed
     * int range of that source. A fraction (1.5) or an out-of-range value
     * (4294967296) is a contract violation — E_INVALID_RESPONSE — never a
     * silently truncated or wrapped different measure.
     */
    private static int requireInt(JSONObject d, String key) throws AdapterException {
        return (int) requireIntegerInRange(d, key, "int", Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * The legitimately-long fields: charge_uah is emitted from
     * BatteryManager.getLongProperty(CHARGE_COUNTER), so its native range is
     * the full signed long range and must NOT be narrowed to int — a
     * charge_counter above Integer.MAX_VALUE is a real possible measurement.
     */
    private static long requireLong(JSONObject d, String key) throws AdapterException {
        return requireIntegerInRange(d, key, "long", Long.MIN_VALUE, Long.MAX_VALUE);
    }

    private static long requireIntegerInRange(JSONObject d, String key, String domain, long min, long max)
            throws AdapterException {
        Object v = d.opt(key);
        if (!(v instanceof Number)) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " missing or not a number");
        }
        Number n = (Number) v;
        double dv = n.doubleValue();
        if (!Double.isFinite(dv)) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " not a finite number");
        }
        if (dv != Math.rint(dv)) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " not an exact integer");
        }
        long lv;
        if (n instanceof Long || n instanceof Integer) {
            // integral by construction, no precision loss
            lv = n.longValue();
        } else {
            // Double/Float literal: integral, but 2^63 is not representable
            double longMaxPlusOne = 9.223372036854775808E18;
            if (dv < -longMaxPlusOne || dv >= longMaxPlusOne) {
                throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " outside the long range");
            }
            lv = (long) dv;
        }
        if (lv < min || lv > max) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " outside the " + domain + " range");
        }
        return lv;
    }

    private static boolean requireBool(JSONObject d, String key) throws AdapterException {
        Object v = d.opt(key);
        if (!(v instanceof Boolean)) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " missing or not a boolean");
        }
        return (Boolean) v;
    }

    private static String requireString(JSONObject d, String key) throws AdapterException {
        Object v = d.opt(key);
        if (!(v instanceof String)) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " missing or not a string");
        }
        return (String) v;
    }

    private static double requireFiniteDouble(JSONObject d, String key) throws AdapterException {
        Object v = d.opt(key);
        if (!(v instanceof Number)) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " missing or not a number");
        }
        double dv = ((Number) v).doubleValue();
        if (!Double.isFinite(dv)) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " not finite");
        }
        return dv;
    }

    private static JSONArray requireArray(JSONObject d, String key) throws AdapterException {
        Object v = d.opt(key);
        if (!(v instanceof JSONArray)) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " missing or not an array");
        }
        return (JSONArray) v;
    }

    private static JSONObject requireObject(JSONObject d, String key) throws AdapterException {
        Object v = d.opt(key);
        if (!(v instanceof JSONObject)) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, key + " missing or not an object");
        }
        return (JSONObject) v;
    }

    private static JSONObject optObject(JSONArray a, int i, String what) throws AdapterException {
        Object v = a.opt(i);
        if (!(v instanceof JSONObject)) {
            throw new AdapterException(CompatError.E_INVALID_RESPONSE, what + " not an object");
        }
        return (JSONObject) v;
    }

    // --- optional-key emitters (emit only when present, type-validated) -----

    private static void optBool(IndentWriter w, String key, JSONObject d) throws AdapterException {
        if (d.has(key)) w.booleanKeyRaw(key, requireBool(d, key));
    }

    private static void optString(IndentWriter w, String key, JSONObject d) throws AdapterException {
        if (d.has(key)) w.stringKeyRaw(key, requireString(d, key));
    }

    private static void optInt(IndentWriter w, String key, JSONObject d) throws AdapterException {
        optInt(w, key, key, d);
    }

    private static void optInt(IndentWriter w, String key, String nativeKey, JSONObject d) throws AdapterException {
        if (d.has(nativeKey)) w.intKeyRaw(key, requireInt(d, nativeKey));
    }

    // --- android.util.JsonWriter-equivalent indentation ----------------------

    /**
     * Minimal writer producing byte-identical output to
     * {@code android.util.JsonWriter} with {@code setIndent("  ")} for the
     * value domains of this adapter (objects, arrays, strings, ints, doubles,
     * booleans). Doubles serialize like {@link Double#toString}, exactly as
     * JsonWriter does for a value written as Number. {@link #raw} splices a
     * finished sub-writer's product in at the current position so nested
     * containers keep the exact indentation of the pin writer.
     */
    static final class IndentWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final StringBuilder sb = new StringBuilder();
        private int depth = 0;
        private boolean hasValue = false; // something already written at this container position

        void beginObject() {
            sb.append('{');
            depth++;
            hasValue = false;
        }

        void beginObjectInArray() {
            beforeValue();
            sb.append('{');
            depth++;
            hasValue = false;
        }

        void beginObjectValue() {
            sb.append('{');
            depth++;
            hasValue = false;
        }

        void endObject() {
            depth--;
            if (hasValue) newline();
            sb.append('}');
            hasValue = true;
        }

        void beginArray() {
            sb.append('[');
            depth++;
            hasValue = false;
        }

        void beginArrayValue() {
            // array opened after a "name: " prefix
            sb.append('[');
            depth++;
            hasValue = false;
        }

        void endArray() {
            depth--;
            if (hasValue) newline();
            sb.append(']');
            hasValue = true;
        }

        void name(String name) {
            if (hasValue) sb.append(',');
            newline();
            sb.append(quote(name)).append(": ");
            hasValue = false;
        }

        void stringKeyRaw(String key, String value) {
            name(key);
            stringValue(value);
        }

        void intKeyRaw(String key, int value) {
            name(key);
            sb.append(value);
            hasValue = true;
        }

        /** Same decimal text as intKeyRaw for in-range values; long sources
         *  (charge_counter) keep their full range without narrowing. */
        void longKeyRaw(String key, long value) {
            name(key);
            sb.append(value);
            hasValue = true;
        }

        void booleanKeyRaw(String key, boolean value) {
            name(key);
            sb.append(value);
            hasValue = true;
        }

        void doubleKey(String key, double value) {
            name(key);
            sb.append(Double.toString(value));
            hasValue = true;
        }

        void doubleKeyRaw(String key, double value) {
            doubleKey(key, value);
        }

        void stringValue(String value) {
            sb.append(quote(value));
            hasValue = true;
        }

        /** Array element emitters: comma + newline separated like JsonWriter. */
        void arrayNumberElement(Number value) {
            beforeValue();
            numberValue(value);
        }

        void arrayStringElement(String value) {
            beforeValue();
            stringValue(value);
        }

        void numberValue(Number value) {
            if (value instanceof Double || value instanceof Float) {
                sb.append(Double.toString(value.doubleValue()));
            } else {
                sb.append(value.longValue());
            }
            hasValue = true;
        }

        private void beforeValue() {
            if (hasValue) sb.append(',');
            newline();
        }

        private void newline() {
            sb.append('\n');
            for (int i = 0; i < depth; i++) sb.append("  ");
        }

        byte[] bytes() {
            // flush the accumulated string once; the writer is single-shot
            byte[] s = sb.toString().getBytes(StandardCharsets.UTF_8);
            out.write(s, 0, s.length);
            return out.toByteArray();
        }

        byte[] bytesWithNewline() {
            sb.append('\n');
            return bytes();
        }

        static String quote(String s) {
            StringBuilder b = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    case '\b': b.append("\\b"); break;
                    case '\f': b.append("\\f"); break;
                    default:
                        if (c < 0x20) {
                            b.append(String.format("\\u%04x", (int) c));
                        } else {
                            b.append(c);
                        }
                }
            }
            return b.append('"').toString();
        }
    }
}
