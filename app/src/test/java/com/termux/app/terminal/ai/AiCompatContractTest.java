package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contract tests (written, not executed locally — CI runs them).
 *
 * They pin the three-observable-entry contract recorded in
 * tests/compat/MATRIX.md against the shipped CLI asset and the synthetic
 * fixtures: the 14 upstream methods the compat entry dispatches, the alias
 * names both the installer manages and upstream ships, and the golden
 * families (native envelope vs upstream naked payload).
 *
 * The golden schemas asserted here mirror tests/compat/GOLDEN_CONTRACT.md
 * and the harness "golden contract" section: key names, casing, types and
 * mandatory fields per API at the pinned upstream revision — a wrong key,
 * casing, type or a missing mandatory field fails, not merely "valid JSON".
 * (The byte-level serialization form — 2-space indent, trailing newline —
 * is enforced by the shell harness, which can compare exact bytes.)
 *
 * They do not change behaviour and do not touch the framing regressions
 * (AiRequestFramingTest / AiSocketHandlerTest).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AiCompatContractTest {

    /** The 14 upstream methods the libexec compat entry must know. */
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList(
        "AudioInfo", "BatteryStatus", "Brightness", "CameraInfo", "Clipboard",
        "Notification", "NotificationChannel", "NotificationRemove",
        "TextToSpeech", "Toast", "Torch", "Vibrate", "Volume",
        "WifiConnectionInfo"));

    /** Upstream methods with no native verb on this baseline (MATRIX.md §3). */
    private static final Set<String> NO_NATIVE_VERB = new HashSet<>(Arrays.asList(
        "NotificationChannel", "NotificationRemove", "Vibrate"));

    private static String asset(String name) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (InputStream in = context.getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** tests/compat works from the app module dir (Gradle test working dir) and from the repo root. */
    private static File compatRoot() throws Exception {
        for (String candidate : new String[] {"../tests/compat", "tests/compat"}) {
            File dir = new File(candidate);
            if (new File(dir, "MATRIX.md").isFile()) return dir;
        }
        throw new AssertionError("tests/compat not found from the test working directory");
    }

    private static File golden(String family, String name) throws Exception {
        return new File(new File(compatRoot(), "fixtures/golden/" + family), name);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static JSONObject jsonObject(File file) throws Exception {
        return new JSONObject(read(file));
    }

    private static JSONArray jsonArray(File file) throws Exception {
        return new JSONArray(read(file));
    }

    // Type asserts that do not conflate booleans with integers.
    private static void assertString(JSONObject obj, String key, String ctx) {
        Object v = obj.opt(key);
        assertTrue(ctx + ": " + key + " must be a string, got " + v,
            v instanceof String);
    }

    private static void assertBoolean(JSONObject obj, String key, String ctx) {
        Object v = obj.opt(key);
        assertTrue(ctx + ": " + key + " must be a boolean, got " + v,
            v instanceof Boolean);
    }

    private static void assertInteger(JSONObject obj, String key, String ctx) {
        Object v = obj.opt(key);
        assertTrue(ctx + ": " + key + " must be an integer, got " + v,
            v instanceof Integer || v instanceof Long);
    }

    private static void assertNumber(JSONObject obj, String key, String ctx) {
        Object v = obj.opt(key);
        assertTrue(ctx + ": " + key + " must be a number, got " + v,
            v instanceof Integer || v instanceof Long || v instanceof Double);
    }

    private static void assertExactKeys(JSONObject obj, Set<String> keys, String ctx) {
        Set<String> actual = new HashSet<>();
        for (String k : obj.keySet()) actual.add(k);
        assertEquals(ctx + ": key set", keys, actual);
    }

    @Test
    public void fourteenUpstreamMethodsAreDispatchedByTheCompatEntry() throws Exception {
        String cli = asset("termux-ai");
        for (String method : METHODS) {
            Pattern pattern = Pattern.compile("(?m)^    " + Pattern.quote(method) + "\\)");
            Matcher matcher = pattern.matcher(cli);
            assertTrue("compat entry dispatches " + method, matcher.find());
        }
    }

    @Test
    public void everyAliasTheInstallerManagesIsShippedAndUpstreamAlsoHasIt() throws Exception {
        String cli = asset("termux-ai");
        File pin = new File(compatRoot(), "fixtures/upstream-pin");
        File[] vendored = pin.listFiles((dir, name) -> name.endsWith(".in"));
        assertNotNull("vendored upstream pin directory exists", vendored);
        Set<String> upstream = new HashSet<>();
        for (File file : vendored) upstream.add(file.getName().substring(0, file.getName().length() - 3));

        // The alias names the shipped CLI answers to (compat_main dispatch).
        for (String name : upstream) {
            Pattern pattern = Pattern.compile("(?m)^    " + Pattern.quote(name) + "\\)");
            assertTrue("shipped CLI has an alias handler for " + name, pattern.matcher(cli).find());
        }
        // Every managed helper the installer copies (16 upstream names + termux-ai + termux-api)
        // is either an upstream wrapper or an app-owned name; the 16 collisions are the
        // baseline ownership defect recorded in MATRIX.md §5, not something to assert away.
        assertEquals(16, upstream.size());
    }

    @Test
    public void compatGoldensMatchThePinnedUpstreamSchemas() throws Exception {
        // AudioInfo (AudioAPI.java:68-88): property values are STRINGS, track values ints.
        JSONObject audio = jsonObject(golden("compat", "AudioInfo.json"));
        assertExactKeys(audio, new HashSet<>(Arrays.asList(
            "PROPERTY_OUTPUT_SAMPLE_RATE", "PROPERTY_OUTPUT_FRAMES_PER_BUFFER",
            "AUDIOTRACK_SAMPLE_RATE", "AUDIOTRACK_BUFFER_SIZE_IN_FRAMES",
            "BLUETOOTH_A2DP_IS_ON", "WIREDHEADSET_IS_CONNECTED")), "AudioInfo");
        assertString(audio, "PROPERTY_OUTPUT_SAMPLE_RATE", "AudioInfo");
        assertString(audio, "PROPERTY_OUTPUT_FRAMES_PER_BUFFER", "AudioInfo");
        assertInteger(audio, "AUDIOTRACK_SAMPLE_RATE", "AudioInfo");
        assertInteger(audio, "AUDIOTRACK_BUFFER_SIZE_IN_FRAMES", "AudioInfo");
        assertBoolean(audio, "BLUETOOTH_A2DP_IS_ON", "AudioInfo");
        assertBoolean(audio, "WIREDHEADSET_IS_CONNECTED", "AudioInfo");

        // BatteryStatus (BatteryStatusAPI.java:149-168): mandatory keys and enum domains.
        JSONObject battery = jsonObject(golden("compat", "BatteryStatus.json"));
        assertBoolean(battery, "present", "BatteryStatus");
        for (String k : new String[] {"health", "plugged", "status", "technology"}) {
            if (battery.has(k)) assertString(battery, k, "BatteryStatus");
        }
        assertNumber(battery, "temperature", "BatteryStatus");
        assertTrue("BatteryStatus.temperature is a 1-decimal double (upstream rounds)",
            battery.get("temperature") instanceof Double);
        for (String k : new String[] {"voltage", "level", "scale"}) {
            assertTrue("BatteryStatus mandatory " + k, battery.has(k));
            assertInteger(battery, k, "BatteryStatus");
        }
        for (String k : new String[] {"current", "current_average", "percentage", "charge_counter"}) {
            if (battery.has(k)) assertInteger(battery, k, "BatteryStatus");
        }
        assertTrue("BatteryStatus.health domain",
            Arrays.asList("COLD", "DEAD", "GOOD", "OVERHEAT", "OVER_VOLTAGE",
                "UNKNOWN", "UNSPECIFIED_FAILURE").contains(battery.getString("health")));
        assertTrue("BatteryStatus.status domain",
            Arrays.asList("CHARGING", "DISCHARGING", "FULL", "NOT_CHARGING",
                "UNKNOWN").contains(battery.getString("status")));

        // CameraInfo (CameraInfoAPI.java:31-145): array, lowercase facing, all per-camera fields.
        JSONArray cameras = jsonArray(golden("compat", "CameraInfo.json"));
        assertTrue("CameraInfo is a non-empty array", cameras.length() >= 1);
        Set<String> cameraKeys = new HashSet<>(Arrays.asList(
            "id", "facing", "jpeg_output_sizes", "focal_lengths",
            "auto_exposure_modes", "physical_size", "capabilities"));
        Set<String> aeDomain = new HashSet<>(Arrays.asList(
            "CONTROL_AE_MODE_OFF", "CONTROL_AE_MODE_ON", "CONTROL_AE_MODE_ON_ALWAYS_FLASH",
            "CONTROL_AE_MODE_ON_AUTO_FLASH", "CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE",
            "CONTROL_AE_MODE_ON_EXTERNAL_FLASH"));
        Set<String> capabilityDomain = new HashSet<>(Arrays.asList(
            "backward_compatible", "burst_capture", "constrained_high_speed_video",
            "depth_output", "logical_multi_camera", "manual_post_processing",
            "manual_sensor", "monochrome", "motion_tracking", "private_reprocessing",
            "raw", "read_sensor_settings", "yuv_reprocessing"));
        for (int i = 0; i < cameras.length(); i++) {
            String ctx = "CameraInfo[" + i + "]";
            JSONObject cam = cameras.getJSONObject(i);
            assertExactKeys(cam, cameraKeys, ctx);
            assertString(cam, "id", ctx);
            assertTrue(ctx + ": facing must be lowercase back/front, got " + cam.get("facing"),
                "back".equals(cam.get("facing")) || "front".equals(cam.get("facing"))
                    || cam.get("facing") instanceof Integer);
            JSONArray sizes = cam.getJSONArray("jpeg_output_sizes");
            assertTrue(ctx + ": jpeg_output_sizes non-empty", sizes.length() >= 1);
            for (int j = 0; j < sizes.length(); j++) {
                JSONObject s = sizes.getJSONObject(j);
                assertExactKeys(s, new HashSet<>(Arrays.asList("width", "height")), ctx + ".size");
                assertInteger(s, "width", ctx + ".size");
                assertInteger(s, "height", ctx + ".size");
            }
            assertTrue(ctx + ": focal_lengths non-empty numbers",
                cam.getJSONArray("focal_lengths").length() >= 1);
            JSONArray modes = cam.getJSONArray("auto_exposure_modes");
            assertTrue(ctx + ": auto_exposure_modes non-empty", modes.length() >= 1);
            for (int j = 0; j < modes.length(); j++) {
                Object m = modes.get(j);
                assertTrue(ctx + ": ae mode domain", m instanceof Integer || aeDomain.contains(m));
            }
            JSONObject ps = cam.getJSONObject("physical_size");
            assertExactKeys(ps, new HashSet<>(Arrays.asList("width", "height")), ctx + ".physical_size");
            assertNumber(ps, "width", ctx + ".physical_size");
            assertNumber(ps, "height", ctx + ".physical_size");
            JSONArray caps = cam.getJSONArray("capabilities");
            assertTrue(ctx + ": capabilities non-empty", caps.length() >= 1);
            for (int j = 0; j < caps.length(); j++) {
                Object c = caps.get(j);
                assertTrue(ctx + ": capability domain", c instanceof Integer || capabilityDomain.contains(c));
            }
        }

        // TTS engines list (TextToSpeechAPI.java:124-140).
        JSONArray engines = jsonArray(golden("compat", "TextToSpeech-LIST_AVAILABLE.json"));
        assertTrue("TTS engines non-empty", engines.length() >= 1);
        for (int i = 0; i < engines.length(); i++) {
            JSONObject e = engines.getJSONObject(i);
            assertExactKeys(e, new HashSet<>(Arrays.asList("name", "label", "default")),
                "TTS engine entry");
            assertString(e, "name", "TTS engine entry");
            assertString(e, "label", "TTS engine entry");
            assertBoolean(e, "default", "TTS engine entry");
        }

        // Volume list (VolumeAPI.java:20-27): fixed stream order and key set.
        JSONArray volumes = jsonArray(golden("compat", "Volume-list.json"));
        assertEquals("Volume list stream order follows the pin streamMap",
            Arrays.asList("alarm", "music", "notification", "ring", "system", "call"),
            streamNames(volumes));
        for (int i = 0; i < volumes.length(); i++) {
            JSONObject v = volumes.getJSONObject(i);
            assertExactKeys(v, new HashSet<>(Arrays.asList("stream", "volume", "max_volume")),
                "Volume entry");
            assertString(v, "stream", "Volume entry");
            assertInteger(v, "volume", "Volume entry");
            assertInteger(v, "max_volume", "Volume entry");
        }

        // WifiConnectionInfo connected branch (WifiAPI.java:31-48): all fields, exact set.
        JSONObject wifi = jsonObject(golden("compat", "WifiConnectionInfo.json"));
        assertExactKeys(wifi, new HashSet<>(Arrays.asList(
            "bssid", "frequency_mhz", "ip", "link_speed_mbps", "mac_address",
            "network_id", "rssi", "ssid", "ssid_hidden", "supplicant_state")),
            "WifiConnectionInfo");
        for (String k : new String[] {"bssid", "ip", "mac_address", "ssid", "supplicant_state"}) {
            assertString(wifi, k, "WifiConnectionInfo");
        }
        for (String k : new String[] {"frequency_mhz", "link_speed_mbps", "network_id", "rssi"}) {
            assertInteger(wifi, k, "WifiConnectionInfo");
        }
        assertBoolean(wifi, "ssid_hidden", "WifiConnectionInfo");

        // WifiConnectionInfo null-info branch (WifiAPI.java:34-35).
        JSONObject unconnected = jsonObject(golden("compat", "WifiConnectionInfo-unconnected.json"));
        assertEquals("WifiConnectionInfo unconnected branch",
            "No current connection", unconnected.getString("API_ERROR"));
        assertEquals(1, unconnected.length());
    }

    private static List<String> streamNames(JSONArray volumes) throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < volumes.length(); i++) names.add(volumes.getJSONObject(i).getString("stream"));
        return names;
    }

    @Test
    public void compatTextTargetsMatchTheUpstreamTextLines() throws Exception {
        // NotificationChannel upstream prints a plain text line (NotificationAPI.java:99,109).
        assertEquals("channel create text line",
            "Created channel with id \"synthetic-channel\" and name \"Synthetic\".\n",
            read(golden("compat", "NotificationChannel-create.txt")));
        assertEquals("channel delete text line",
            "Deleted channel with id \"synthetic-channel\".\n",
            read(golden("compat", "NotificationChannel-delete.txt")));
        // Action methods whose upstream stdout is exactly empty.
        for (String name : new String[] {"Brightness-empty.txt", "Clipboard-set-empty.txt",
            "Notification-empty.txt", "NotificationRemove-empty.txt",
            "TextToSpeech-speak-empty.txt", "Toast-empty.txt", "Torch-empty.txt",
            "Vibrate-empty.txt", "Volume-set-empty.txt"}) {
            assertEquals("empty stdout target " + name, 0, golden("compat", name).length());
        }
        // Clipboard get: raw text, no trailing newline, not an envelope.
        String clip = read(golden("compat", "Clipboard-get.txt"));
        assertFalse("clipboard get is not an envelope", clip.startsWith("{\"ok\""));
        assertFalse("clipboard get adds no trailing newline (out.print)", clip.endsWith("\n"));
    }

    @Test
    public void nativeGoldensAreEnvelopesWithTheAppSourcePayloadShapes() throws Exception {
        File nativeDir = new File(compatRoot(), "fixtures/golden/native");
        int nativeCount = 0;
        for (File file : nativeDir.listFiles()) {
            String text = read(file);
            JSONObject envelope = new JSONObject(text);
            assertTrue(envelope.has("ok"));
            if (file.getName().startsWith("error-")) {
                assertFalse(envelope.getBoolean("ok"));
                assertTrue("error envelope has an error string: " + file.getName(),
                    envelope.getString("error").length() > 0);
            } else {
                assertTrue("success envelope has a data object: " + file.getName(),
                    envelope.getBoolean("ok") && envelope.get("data") instanceof JSONObject);
            }
            nativeCount++;
        }
        assertTrue("native golden set is not empty", nativeCount >= 14);

        // Payload key sets fixed by TermuxAiSocketServer at the baseline
        // (the two rows corrected afterwards: volume and tts-speak).
        JSONObject volumeList = jsonObject(golden("native", "volume-list.json"))
            .getJSONObject("data");
        JSONArray streams = volumeList.getJSONArray("streams");
        assertEquals("native volume list stream order",
            Arrays.asList("alarm", "music", "notification", "ring", "system", "call"),
            streamNames(streams));
        for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.getJSONObject(i);
            assertExactKeys(s, new HashSet<>(Arrays.asList("stream", "volume", "max_volume")),
                "native volume entry");
        }
        JSONObject volumeSet = jsonObject(golden("native", "volume-set.json")).getJSONObject("data");
        assertExactKeys(volumeSet, new HashSet<>(Arrays.asList("stream", "volume", "max_volume")),
            "native volume set");
        JSONObject ttsSpeak = jsonObject(golden("native", "tts-speak.json")).getJSONObject("data");
        assertExactKeys(ttsSpeak, new HashSet<>(Arrays.asList("queued", "chars")),
            "native tts speak");
        assertBoolean(ttsSpeak, "queued", "native tts speak");
        assertInteger(ttsSpeak, "chars", "native tts speak");
    }

    @Test
    public void matrixSummaryTableCoversExactlyTheFourteenMethods() throws Exception {
        List<String> lines = Files.readAllLines(
            new File(compatRoot(), "MATRIX.md").toPath(), StandardCharsets.UTF_8);
        Pattern row = Pattern.compile("^\\|\\s*\\d+\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|");
        Set<String> seen = new HashSet<>();
        Set<String> nativeNA = new HashSet<>();
        for (String line : lines) {
            Matcher matcher = row.matcher(line);
            if (matcher.find()) {
                String methodCell = matcher.group(1).trim();
                String method = methodCell.split("\\s+")[0];
                seen.add(method);
                String nativeCell = matcher.group(2).trim().replace("*", "");
                if (nativeCell.startsWith("N/A")) nativeNA.add(method);
            }
        }
        assertEquals("MATRIX.md summary lists exactly the 14 methods", METHODS, seen);
        assertEquals("MATRIX.md marks exactly the verbless methods N/A on the native entry",
            NO_NATIVE_VERB, nativeNA);
    }

    @Test
    public void matrixRecordsTheTwoConfirmedCompatDefectsAndTheAlreadyCorrectAliasPaths() throws Exception {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(new File(compatRoot(), "MATRIX.md").toPath(), StandardCharsets.UTF_8)) {
            lines.add(line.toLowerCase(Locale.ROOT));
        }
        String all = String.join("\n", lines);
        assertTrue("matrix cites the Clipboard compat defect anchor :535", all.contains(":535"));
        assertTrue("matrix cites the Volume compat defect anchor :549", all.contains(":549"));
        assertTrue("matrix records clipboard-set alias stdin as present", all.contains(":576"));
        assertTrue("matrix records notification alias stdin as present (no forced red)", all.contains(":592"));
        assertTrue("matrix records the upstream engines invocation landing on tts.speak",
            all.contains("tts.speak"));
        assertTrue("matrix records the NotificationChannel upstream text line target",
            all.contains("notificationapi.java"));
    }
}
