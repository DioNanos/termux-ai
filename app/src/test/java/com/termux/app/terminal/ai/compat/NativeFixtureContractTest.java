package com.termux.app.terminal.ai.compat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Producer→fixture constraints: every paired native input
 * under fixtures/golden/s1b1-native must be an object the backend can
 * actually EMIT — fields, types and the pure RELATIONS the producer derives
 * from one measurement (facing/facing_id from the same LENS_FACING, torch
 * camera id from the same CameraManager walk, notification id/channel
 * always posted, volume list in the producer's stream order, unconnected
 * wifi exactly the null-info branch). GOLDEN_CONTRACT.md §3 documents the
 * source of every family and marks each input a COMPLETE object of its
 * represented branch (no undeclared projections). What this does not
 * certify: that the values were measured on a device — they are synthetic
 * by design, and real-device qualification stays CI material.
 */
public class NativeFixtureContractTest {

    @Test
    public void cameraFacingAndFacingIdDeriveFromTheSameLensFacing() throws Exception {
        for (JSONObject camera : cameras()) {
            String facing = camera.getString("facing");
            // the producer writes facing_id only when LENS_FACING was read
            if (facing.equals("unknown")) {
                assertFalse("an unreadable LENS_FACING cannot carry a facing_id",
                    camera.has("facing_id"));
                return;
            }
            assertTrue("facing must be a lens constant name: " + facing,
                facing.equals("front") || facing.equals("back") || facing.equals("external"));
            int facingId = camera.getInt("facing_id");
            // cameraFacingName(facingId) must return the fixture's facing:
            // the pure relation of CameraCharacteristics.LENS_FACING_*
            int expected = facing.equals("front") ? 0 : facing.equals("back") ? 1 : 2;
            assertEquals("facing/facing_id must derive from one LENS_FACING value", expected, facingId);
        }
    }

    @Test
    public void cameraSchemaMatchesTheBackendEmission() throws Exception {
        for (JSONObject camera : cameras()) {
            assertEquals("camera id is the CameraManager string id", String.class, camera.get("id").getClass());
            assertEquals("flash_available is always emitted", Boolean.class, camera.get("flash_available").getClass());
            JSONArray sizes = camera.getJSONArray("jpeg_output_sizes");
            for (int i = 0; i < sizes.length(); i++) {
                JSONObject size = sizes.optJSONObject(i);
                assertNotNull(size);
                assertEquals(Integer.class, size.get("width").getClass());
                assertEquals(Integer.class, size.get("height").getClass());
            }
            JSONObject physical = camera.optJSONObject("physical_size");
            if (physical != null) {
                assertTrue(physical.get("width") instanceof Number);
                assertTrue(physical.get("height") instanceof Number);
            }
            for (String key : new String[]{"auto_exposure_modes", "capabilities"}) {
                JSONArray mixed = camera.optJSONArray(key);
                if (mixed == null) continue;
                for (int i = 0; i < mixed.length(); i++) {
                    Object element = mixed.opt(i);
                    // known values are pin names, unknown stay numbers
                    assertTrue(element instanceof String || element instanceof Number);
                }
            }
        }
    }

    @Test
    public void torchReportsACameraThatActuallyHasAFlash() throws Exception {
        JSONObject torch = nativeInput("Torch-empty");
        assertEquals("on", torch.getString("status"));
        // the producer emits the camera id on EVERY ok outcome (
        // this used to be an undeclared projection with no camera)
        String cameraId = torch.getString("camera");
        boolean foundWithFlash = false;
        for (JSONObject camera : cameras()) {
            if (camera.getString("id").equals(cameraId)) {
                assertTrue("torch camera must have FLASH_INFO_AVAILABLE",
                    camera.getBoolean("flash_available"));
                foundWithFlash = true;
            }
        }
        assertTrue("torch camera must be one of the CameraInfo cameras", foundWithFlash);
    }

    @Test
    public void notificationAlwaysCarriesItsPostedIdAndChannel() throws Exception {
        JSONObject notification = nativeInput("Notification-empty");
        assertEquals("posted", notification.getString("status"));
        // notify() returns status+id+channel unconditionally
        assertTrue("posted notifications carry the id", notification.getString("id").length() > 0);
        assertTrue("posted notifications carry the channel", notification.getString("channel").length() > 0);
    }

    @Test
    public void batteryCarriesTheAlwaysMeasuredKeysWithNativeTypes() throws Exception {
        JSONObject battery = nativeInput("BatteryStatus");
        // battery() measures these four unconditionally
        for (String key : new String[]{"level", "charging", "current_ua", "charge_uah"}) {
            assertTrue("battery must carry the always-measured " + key, battery.has(key));
        }
        assertEquals(Boolean.class, battery.get("charging").getClass());
        assertEquals(Integer.class, battery.get("level").getClass());
        assertEquals(Integer.class, battery.get("current_ua").getClass());
        // charge_uah comes from getLongProperty: a long range value is legal
        assertTrue(battery.get("charge_uah") instanceof Number);
        if (battery.has("temperature")) {
            assertEquals("temperature is a sticky double (0.1 °C units)",
                Double.class, battery.get("temperature").getClass());
        }
    }

    @Test
    public void volumeListKeepsTheProducerStreamOrderAndClampedValues() throws Exception {
        JSONArray streams = nativeInput("Volume-list").getJSONArray("streams");
        String[] producerOrder = {"alarm", "music", "notification", "ring", "system", "call"};
        assertEquals(producerOrder.length, streams.length());
        for (int i = 0; i < streams.length(); i++) {
            JSONObject stream = streams.optJSONObject(i);
            assertNotNull(stream);
            assertEquals("audioVolumeList() emits the fixed stream order",
                producerOrder[i], stream.getString("stream"));
            int volume = stream.getInt("volume");
            int max = stream.getInt("max_volume");
            assertTrue("volume is clamped to [0, max] by the producer",
                volume >= 0 && volume <= max);
        }
    }

    @Test
    public void unconnectedWifiIsExactlyTheNullInfoBranch() throws Exception {
        JSONObject wifi = nativeInput("WifiConnectionInfo-unconnected");
        assertEquals(false, wifi.getBoolean("wifi_info_present"));
        // the null-info branch emits exactly these keys: ssid/bssid empty,
        // rssi/link_speed 0, network_id -1, supplicant_state empty
        Set<String> nullBranch = new HashSet<>(Arrays.asList(
            "wifi_info_present", "ssid", "bssid", "rssi", "link_speed_mbps", "network_id", "supplicant_state"));
        assertEquals("no additive keys outside the measured-info branch",
            nullBranch, wifi.keySet());
        assertEquals("", wifi.getString("ssid"));
        assertEquals(-1, wifi.getInt("network_id"));
        assertEquals(0, wifi.getInt("rssi"));
    }

    @Test
    public void connectedWifiCarriesTheAlwaysMeasuredFields() throws Exception {
        JSONObject wifi = nativeInput("WifiConnectionInfo");
        assertEquals(true, wifi.getBoolean("wifi_info_present"));
        // info != null: these are always real measurements
        for (String key : new String[]{"ssid", "rssi", "link_speed_mbps", "network_id",
            "supplicant_state", "frequency_mhz", "ip", "ssid_hidden"}) {
            assertTrue("measured WifiInfo must carry " + key, wifi.has(key));
        }
    }

    @Test
    public void everyActionFixtureMatchesItsBranchEmission() throws Exception {
        assertEquals("ok", nativeInput("Clipboard-set-empty").getString("status"));
        assertEquals("shown", nativeInput("Toast-empty").getString("status"));
        JSONObject removal = nativeInput("NotificationRemove-empty");
        assertEquals("removed", removal.getString("status"));
        assertEquals("7", removal.getString("id"));
        JSONObject channel = nativeInput("NotificationChannel-create");
        assertEquals("created", channel.getString("status"));
        assertEquals("synthetic-channel", channel.getString("id"));
        assertEquals("Synthetic", channel.getString("name"));
        assertEquals("deleted", nativeInput("NotificationChannel-delete").getString("status"));

        JSONObject brightness = nativeInput("Brightness-empty");
        assertEquals("manual", brightness.getString("mode"));
        assertTrue("manual mode carries the brightness", brightness.has("brightness"));

        JSONObject vibrate = nativeInput("Vibrate-empty");
        int duration = vibrate.getInt("duration_ms");
        assertTrue("vibrate clamps duration to [1, 5000]", duration >= 1 && duration <= 5000);

        JSONObject speak = nativeInput("TextToSpeech-speak-empty");
        assertEquals(Boolean.class, speak.get("queued").getClass());
        assertTrue("chars is text.length()", speak.getInt("chars") >= 0);

        JSONArray engines = nativeInput("TextToSpeech-LIST_AVAILABLE").getJSONArray("engines");
        int defaults = 0;
        for (int i = 0; i < engines.length(); i++) {
            JSONObject engine = engines.optJSONObject(i);
            assertNotNull(engine);
            assertTrue(engine.getString("name").length() > 0);
            assertTrue(engine.getString("label").length() > 0);
            if (engine.getBoolean("default")) defaults++;
        }
        assertTrue("at most one engine matches getDefaultEngine()", defaults <= 1);

        JSONObject audio = nativeInput("AudioInfo");
        // AudioManager.getProperty returns strings, always emitted
        assertEquals(String.class, audio.get("sample_rate").getClass());
        assertEquals(String.class, audio.get("frames_per_buffer").getClass());
        for (String key : new String[]{"bluetooth_a2dp_on", "wired_headset_on",
            "speakerphone_on", "music_active"}) {
            assertEquals(Boolean.class, audio.get(key).getClass());
        }
    }

    // --- helpers ---------------------------------------------------------------

    private static Iterable<JSONObject> cameras() throws Exception {
        JSONArray array = nativeInput("CameraInfo").getJSONArray("cameras");
        assertTrue("the fixture represents a device with cameras", array.length() > 0);
        return new Iterable<JSONObject>() {
            @Override public java.util.Iterator<JSONObject> iterator() {
                return new java.util.Iterator<JSONObject>() {
                    int i = 0;
                    @Override public boolean hasNext() { return i < array.length(); }
                    @Override public JSONObject next() { return array.optJSONObject(i++); }
                };
            }
        };
    }

    private static JSONObject nativeInput(String base) throws Exception {
        File file = new File(nativeDir(), base + ".json");
        assertTrue("paired native input missing: " + file, file.isFile());
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private static File nativeDir() throws Exception {
        String viaProperty = System.getProperty("termuxai.compat.root");
        Map<String, File> roots = new HashMap<>();
        if (viaProperty != null) {
            roots.put("property", new File(viaProperty));
        }
        roots.put("walk-up-a", new File("../tests/compat"));
        roots.put("walk-up-b", new File("tests/compat"));
        for (File dir : roots.values()) {
            if (new File(dir, "MATRIX.md").isFile()) {
                return new File(dir, "fixtures/golden/s1b1-native");
            }
        }
        throw new AssertionError("tests/compat not found (pass -Dtermuxai.compat.root=…)");
    }
}
