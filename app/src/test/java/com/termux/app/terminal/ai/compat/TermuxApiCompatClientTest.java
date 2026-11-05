package com.termux.app.terminal.ai.compat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * JVM tests of the compat client, driven through the controlled
 * transport seam with the real parser and adapter . Written to also run locally with functional org.json/JUnit
 * stubs; the platform org.json + a real socket boundary remain CI material.
 */
public class TermuxApiCompatClientTest {

    /** A stdin that must never be read for its branch: every read attempt
     *  is counted and fails, standing in for a pipe that would never produce
     *  input or close. */
    private static final class UnreadableStdin extends java.io.InputStream {
        int reads;
        @Override public int read() throws java.io.IOException {
            reads++;
            throw new java.io.IOException("stdin must not be read for this branch");
        }
        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            reads++;
            throw new java.io.IOException("stdin must not be read for this branch");
        }
    }

    private static final class Run {
        final String stdout;
        final String stderr;
        final int exit;
        final FakeCompatTransport transport;
        Run(String[] argv, java.io.InputStream stdin, FakeCompatTransport t) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            this.exit = TermuxApiCompatClient.run(argv, stdin == null ? FakeCompatTransport.noStdin() : stdin,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                t, CompatClock.SYSTEM);
            this.stdout = new String(out.toByteArray(), StandardCharsets.UTF_8);
            this.stderr = new String(err.toByteArray(), StandardCharsets.UTF_8);
            this.transport = t;
        }
    }

    private static Run ok(String[] argv, String dataJson) {
        return new Run(argv, null, new FakeCompatTransport().answering("{\"ok\":true,\"data\":" + dataJson + "}"));
    }

    private static Run okStdin(String[] argv, String stdin, String dataJson) {
        return new Run(argv, FakeCompatTransport.stdin(stdin),
            new FakeCompatTransport().answering("{\"ok\":true,\"data\":" + dataJson + "}"));
    }

    // --- per-method success: byte-exact stdout ------------------------------

    @Test
    public void batteryStatusMapsEveryMeasuredKeyWithNewline() {
        Run r = ok(new String[]{"BatteryStatus"},
            "{\"level\":88,\"charging\":true,\"current_ua\":-450000,\"charge_uah\":1234567,"
            + "\"present\":true,\"health\":\"GOOD\",\"plugged\":\"PLUGGED_USB\",\"status\":\"CHARGING\","
            + "\"temperature\":29.0,\"voltage\":4351,\"raw_level\":88,\"scale\":100,\"technology\":\"Li-ion\","
            + "\"current_average\":-400000,\"percentage\":88}");
        // upstream writer order (BatteryStatusAPI.java:149-168)
        assertEquals("{\n"
            + "  \"present\": true,\n"
            + "  \"technology\": \"Li-ion\",\n"
            + "  \"health\": \"GOOD\",\n"
            + "  \"plugged\": \"PLUGGED_USB\",\n"
            + "  \"status\": \"CHARGING\",\n"
            + "  \"temperature\": 29.0,\n"
            + "  \"voltage\": 4351,\n"
            + "  \"current\": -450000,\n"
            + "  \"current_average\": -400000,\n"
            + "  \"percentage\": 88,\n"
            + "  \"level\": 88,\n"
            + "  \"scale\": 100,\n"
            + "  \"charge_counter\": 1234567\n"
            + "}\n", r.stdout);
        assertEquals("", r.stderr);
        assertEquals(0, r.exit);
        assertEquals("{\"cmd\":\"sys.battery\",\"args\":{}}", r.transport.lastRequest);
    }

    @Test
    public void audioInfoMapsUpstreamKeyNames() {
        Run r = ok(new String[]{"AudioInfo"},
            "{\"sample_rate\":\"48000\",\"frames_per_buffer\":\"1024\","
            + "\"bluetooth_a2dp_on\":false,\"wired_headset_on\":true,"
            + "\"audiotrack_sample_rate\":48000,\"audiotrack_buffer_size_in_frames\":4096}");
        assertEquals("{\n"
            + "  \"PROPERTY_OUTPUT_SAMPLE_RATE\": \"48000\",\n"
            + "  \"PROPERTY_OUTPUT_FRAMES_PER_BUFFER\": \"1024\",\n"
            + "  \"AUDIOTRACK_SAMPLE_RATE\": 48000,\n"
            + "  \"AUDIOTRACK_BUFFER_SIZE_IN_FRAMES\": 4096,\n"
            + "  \"BLUETOOTH_A2DP_IS_ON\": false,\n"
            + "  \"WIREDHEADSET_IS_CONNECTED\": true\n"
            + "}\n", r.stdout);
        assertEquals(0, r.exit);
    }

    @Test
    public void cameraInfoEmitsNakedArrayWithUpstreamSchema() {
        String camera0 = "{\"id\":\"0\",\"facing\":\"back\",\"flash_available\":true,\"facing_id\":0,"
            + "\"jpeg_output_sizes\":[{\"width\":640,\"height\":480},{\"width\":1920,\"height\":1080}],"
            + "\"focal_lengths\":[4.25],\"auto_exposure_modes\":[\"CONTROL_AE_MODE_ON\"],"
            + "\"physical_size\":{\"width\":5.5,\"height\":4.25},"
            + "\"capabilities\":[\"backward_compatible\",\"manual_sensor\"]}";
        Run r = ok(new String[]{"CameraInfo"}, "{\"cameras\":[" + camera0 + "]}");
        assertEquals("[\n"
            + "  {\n"
            + "    \"id\": \"0\",\n"
            + "    \"facing\": \"back\",\n"
            + "    \"jpeg_output_sizes\": [\n"
            + "      {\n"
            + "        \"width\": 640,\n"
            + "        \"height\": 480\n"
            + "      },\n"
            + "      {\n"
            + "        \"width\": 1920,\n"
            + "        \"height\": 1080\n"
            + "      }\n"
            + "    ],\n"
            + "    \"focal_lengths\": [\n"
            + "      4.25\n"
            + "    ],\n"
            + "    \"auto_exposure_modes\": [\n"
            + "      \"CONTROL_AE_MODE_ON\"\n"
            + "    ],\n"
            + "    \"physical_size\": {\n"
            + "      \"width\": 5.5,\n"
            + "      \"height\": 4.25\n"
            + "    },\n"
            + "    \"capabilities\": [\n"
            + "      \"backward_compatible\",\n"
            + "      \"manual_sensor\"\n"
            + "    ]\n"
            + "  }\n"
            + "]\n", r.stdout);
        assertEquals(0, r.exit);
    }

    @Test
    public void cameraInfoEmitsIntFacingForExternalLens() {
        Run r = ok(new String[]{"CameraInfo"},
            "{\"cameras\":[{\"id\":\"2\",\"facing\":\"external\",\"facing_id\":2,"
            + "\"jpeg_output_sizes\":[{\"width\":320,\"height\":240}],\"focal_lengths\":[2.0],"
            + "\"auto_exposure_modes\":[\"CONTROL_AE_MODE_ON\"],"
            + "\"physical_size\":{\"width\":2.0,\"height\":1.5},"
            + "\"capabilities\":[\"backward_compatible\"]}]}");
        assertTrue(r.stdout.startsWith("[\n  {\n    \"id\": \"2\",\n    \"facing\": 2,\n"));
        assertEquals(0, r.exit);
    }

    @Test
    public void cameraInfoWithoutMandatoryMetadataIsAnErrorNotAnEmptyArray() {
        // no invented empty arrays: every field the pin writer always emits
        // must be measured, otherwise the branch fails explicitly
        Run r = ok(new String[]{"CameraInfo"},
            "{\"cameras\":[{\"id\":\"2\",\"facing\":\"external\",\"facing_id\":2}]}");
        assertEquals("", r.stdout);
        assertEquals("E_INVALID_RESPONSE\n", r.stderr);
        assertEquals(4, r.exit);
    }

    @Test
    public void ttsEnginesListHasNoTrailingNewline() {
        Run r = ok(new String[]{"TextToSpeech", "--es", "engine", "LIST_AVAILABLE"},
            "{\"engines\":[{\"name\":\"com.google.android.tts\",\"label\":\"Google TTS\",\"default\":true}]}");
        assertEquals("[\n"
            + "  {\n"
            + "    \"name\": \"com.google.android.tts\",\n"
            + "    \"label\": \"Google TTS\",\n"
            + "    \"default\": true\n"
            + "  }\n"
            + "]", r.stdout);
        assertEquals(0, r.exit);
    }

    @Test
    public void volumeListEmitsArrayWithoutTrailingNewline() {
        Run r = ok(new String[]{"Volume"},
            "{\"streams\":[{\"stream\":\"alarm\",\"volume\":3,\"max_volume\":7},"
            + "{\"stream\":\"music\",\"volume\":11,\"max_volume\":15}]}");
        assertEquals("[\n"
            + "  {\n"
            + "    \"stream\": \"alarm\",\n"
            + "    \"volume\": 3,\n"
            + "    \"max_volume\": 7\n"
            + "  },\n"
            + "  {\n"
            + "    \"stream\": \"music\",\n"
            + "    \"volume\": 11,\n"
            + "    \"max_volume\": 15\n"
            + "  }\n"
            + "]", r.stdout);
        assertEquals(0, r.exit);
    }

    @Test
    public void wifiConnectionMapsAllUpstreamKeysAndStripsSsidQuotes() {
        Run r = ok(new String[]{"WifiConnectionInfo"},
            "{\"wifi_info_present\":true,\"ssid\":\"\\\"MyWifi\\\"\",\"bssid\":\"02:00:00:00:00:00\",\"rssi\":-55,"
            + "\"link_speed_mbps\":433,\"network_id\":1,\"supplicant_state\":\"COMPLETED\","
            + "\"frequency_mhz\":5180,\"ip\":\"192.168.1.42\",\"mac_address\":\"02:00:00:00:00:00\","
            + "\"ssid_hidden\":false}");
        assertEquals("{\n"
            + "  \"bssid\": \"02:00:00:00:00:00\",\n"
            + "  \"frequency_mhz\": 5180,\n"
            + "  \"ip\": \"192.168.1.42\",\n"
            + "  \"link_speed_mbps\": 433,\n"
            + "  \"mac_address\": \"02:00:00:00:00:00\",\n"
            + "  \"network_id\": 1,\n"
            + "  \"rssi\": -55,\n"
            + "  \"ssid\": \"MyWifi\",\n"
            + "  \"ssid_hidden\": false,\n"
            + "  \"supplicant_state\": \"COMPLETED\"\n"
            + "}\n", r.stdout);
        assertEquals(0, r.exit);
    }

    @Test
    public void wifiUnconnectedBranchIsTheBackendMetadataNotTheSsidText() {
        // the pin's info == null branch, signalled by the backend metadata
        Run r = ok(new String[]{"WifiConnectionInfo"},
            "{\"wifi_info_present\":false,\"ssid\":\"\",\"bssid\":\"\",\"rssi\":-1,"
            + "\"link_speed_mbps\":-1,\"network_id\":-1,\"supplicant_state\":\"\"}");
        assertEquals("{\n  \"API_ERROR\": \"No current connection\"\n}\n", r.stdout);
        assertEquals(0, r.exit);
    }

    @Test
    public void wifiMissingBackendMetadataIsAnError() {
        // no metadata key: the adapter refuses instead of guessing
        Run r = ok(new String[]{"WifiConnectionInfo"},
            "{\"ssid\":\"net\",\"rssi\":-50}");
        assertEquals("", r.stdout);
        assertEquals("E_INVALID_RESPONSE\n", r.stderr);
        assertEquals(4, r.exit);
    }

    @Test
    public void wifiRedactedSsidIsDeliveredMeasuredNotReinterpreted() {
        // info exists but the OS redacted the ssid: the measured value is
        // delivered as-is, never turned into "no current connection"
        Run r = ok(new String[]{"WifiConnectionInfo"},
            "{\"wifi_info_present\":true,\"ssid\":\"<unknown ssid>\",\"rssi\":-50,"
            + "\"link_speed_mbps\":100,\"network_id\":1,\"supplicant_state\":\"COMPLETED\","
            + "\"frequency_mhz\":2437,\"ip\":\"192.168.1.2\",\"ssid_hidden\":false}");
        assertTrue(r.stdout.contains("\"ssid\": \"<unknown ssid>\""));
        assertFalse(r.stdout.contains("API_ERROR"));
        assertEquals(0, r.exit);
    }

    @Test
    public void wifiMissingMeasuredFieldWithInfoPresentIsAnError() {
        Run r = ok(new String[]{"WifiConnectionInfo"},
            "{\"wifi_info_present\":true,\"ssid\":\"net\",\"rssi\":-50}");
        assertEquals("", r.stdout);
        assertEquals("E_INVALID_RESPONSE\n", r.stderr);
        assertEquals(4, r.exit);
    }

    @Test
    public void clipboardGetEmitsRawTextWithoutNewline() {
        Run r = ok(new String[]{"Clipboard"}, "{\"text\":\"line1\\nline2\"}");
        assertEquals("line1\nline2", r.stdout);
        assertEquals(0, r.exit);
    }

    @Test
    public void notificationChannelCreatedEmitsTextLine() {
        Run r = ok(new String[]{"NotificationChannel", "--es", "id", "mail", "--es", "name", "Mail"},
            "{\"status\":\"created\",\"id\":\"mail\",\"name\":\"Mail\"}");
        assertEquals("Created channel with id \"mail\" and name \"Mail\".\n", r.stdout);
        assertEquals(0, r.exit);
    }

    @Test
    public void notificationChannelDeletedEmitsTextLine() {
        Run r = ok(new String[]{"NotificationChannel", "--ez", "delete", "true", "--es", "id", "mail"},
            "{\"status\":\"deleted\",\"id\":\"mail\"}");
        assertEquals("Deleted channel with id \"mail\".\n", r.stdout);
        assertEquals(0, r.exit);
    }

    @Test
    public void actionMethodsPrintNothingOnSuccess() {
        String[][][] cases = {
            {{"Brightness", "--ei", "brightness", "128"}, {"{\"mode\":\"manual\",\"brightness\":128}"}},
            {{"Clipboard", "--ez", "set", "true"}, {"{\"status\":\"ok\"}"}},
            {{"Notification", "--es", "title", "t"}, {"{\"status\":\"posted\"}"}},
            {{"NotificationRemove", "--es", "id", "7"}, {"{\"status\":\"removed\"}"}},
            {{"TextToSpeech", "--es", "text", "hello"}, {"{\"queued\":true,\"chars\":5}"}},
            {{"Toast", "--es", "text", "hi"}, {"{\"status\":\"shown\"}"}},
            {{"Torch", "--ez", "enabled", "true"}, {"{\"status\":\"on\"}"}},
            {{"Vibrate", "--ei", "duration_ms", "900"}, {"{\"duration_ms\":900}"}},
            {{"Volume", "-a", "set-volume", "--es", "stream", "music", "--ei", "volume", "5"},
                {"{\"stream\":\"music\",\"volume\":5,\"max_volume\":15}"}},
        };
        for (String[][] c : cases) {
            Run r = ok(c[0], c[1][0]);
            assertEquals("stdout for " + c[0][0], "", r.stdout);
            assertEquals("stderr for " + c[0][0], "", r.stderr);
            assertEquals("exit for " + c[0][0], 0, r.exit);
        }
    }

    // --- per-branch schema negatives for ALL 14 methods -----------------

    /**
     * For every branch's required keys: absent, null and wrong type must all
     * be explicit failures (empty stdout, exit 4), never a defaulted or
     * partially published success.
     */
    @Test
    public void schemaNegativesForEveryMethodAndBranch() throws Exception {
        String camera = "{\"id\":\"0\",\"facing\":\"back\",\"facing_id\":0,"
            + "\"jpeg_output_sizes\":[{\"width\":640,\"height\":480}],\"focal_lengths\":[4.25],"
            + "\"auto_exposure_modes\":[\"CONTROL_AE_MODE_ON\"],"
            + "\"physical_size\":{\"width\":5.5,\"height\":4.25},"
            + "\"capabilities\":[\"backward_compatible\"]}";
        Object[][] table = {
            {new String[]{"AudioInfo"},
                "{\"sample_rate\":\"48000\",\"frames_per_buffer\":\"256\",\"bluetooth_a2dp_on\":false,"
                    + "\"wired_headset_on\":true}",
                new String[]{"sample_rate", "frames_per_buffer", "bluetooth_a2dp_on", "wired_headset_on"}},
            {new String[]{"BatteryStatus"},
                "{\"level\":88,\"charging\":true,\"current_ua\":-1,\"charge_uah\":1}",
                new String[]{"level", "charging", "current_ua", "charge_uah"}},
            {new String[]{"Brightness", "--ei", "brightness", "128"}, "{\"mode\":\"manual\"}",
                new String[]{"mode"}},
            {new String[]{"CameraInfo"}, "{\"cameras\":[" + camera + "]}", new String[]{"cameras"}},
            {new String[]{"Clipboard"}, "{\"text\":\"x\"}", new String[]{"text"}},
            {new String[]{"Clipboard", "--ez", "set", "true"}, "{\"status\":\"ok\"}",
                new String[]{"status"}},
            {new String[]{"Notification", "--es", "title", "t"}, "{\"status\":\"posted\"}",
                new String[]{"status"}},
            {new String[]{"NotificationChannel", "--es", "id", "mail", "--es", "name", "Mail"},
                "{\"status\":\"created\",\"id\":\"mail\",\"name\":\"Mail\"}",
                new String[]{"status", "id", "name"}},
            {new String[]{"NotificationChannel", "--ez", "delete", "true", "--es", "id", "mail"},
                "{\"status\":\"deleted\",\"id\":\"mail\"}", new String[]{"status", "id"}},
            {new String[]{"NotificationRemove", "--es", "id", "7"}, "{\"status\":\"removed\"}",
                new String[]{"status"}},
            {new String[]{"TextToSpeech", "--es", "engine", "LIST_AVAILABLE"},
                "{\"engines\":[{\"name\":\"e\",\"label\":\"E\",\"default\":true}]}",
                new String[]{"engines"}},
            {new String[]{"TextToSpeech", "--es", "text", "hello"}, "{\"queued\":true,\"chars\":5}",
                new String[]{"queued", "chars"}},
            {new String[]{"Toast", "--es", "text", "hi"}, "{\"status\":\"shown\"}",
                new String[]{"status"}},
            {new String[]{"Torch", "--ez", "enabled", "true"}, "{\"status\":\"on\"}",
                new String[]{"status"}},
            {new String[]{"Vibrate", "--ei", "duration_ms", "900"}, "{\"duration_ms\":900}",
                new String[]{"duration_ms"}},
            {new String[]{"Volume"}, "{\"streams\":[{\"stream\":\"music\",\"volume\":5,\"max_volume\":15}]}",
                new String[]{"streams"}},
            {new String[]{"Volume", "-a", "set-volume", "--es", "stream", "music", "--ei", "volume", "5"},
                "{\"stream\":\"music\",\"volume\":5,\"max_volume\":15}",
                new String[]{"stream", "volume", "max_volume"}},
            {new String[]{"WifiConnectionInfo"},
                "{\"wifi_info_present\":true,\"ssid\":\"n\",\"rssi\":-50,\"link_speed_mbps\":100,"
                    + "\"network_id\":1,\"supplicant_state\":\"COMPLETED\",\"frequency_mhz\":2437,"
                    + "\"ip\":\"192.168.1.2\",\"ssid_hidden\":false}",
                new String[]{"wifi_info_present", "ssid", "rssi", "link_speed_mbps", "network_id",
                    "supplicant_state", "frequency_mhz", "ip", "ssid_hidden"}},
        };
        for (Object[] row : table) {
            String[] argv = (String[]) row[0];
            String validData = (String) row[1];
            String[] required = (String[]) row[2];
            for (String key : required) {
                assertRefused(argv, mutateData(validData, key, "absent"), argv[0] + " " + key + " absent");
                assertRefused(argv, mutateData(validData, key, "null"), argv[0] + " " + key + " null");
                assertRefused(argv, mutateData(validData, key, "wrongtype"), argv[0] + " " + key + " wrong type");
            }
        }
    }

    @Test
    public void cameraInnerFieldsAreValidatedToo() {
        // missing physical_size on a measured camera → explicit error
        String cam = "{\"id\":\"0\",\"facing\":\"back\",\"facing_id\":0,"
            + "\"jpeg_output_sizes\":[{\"width\":640,\"height\":480}],\"focal_lengths\":[4.25],"
            + "\"auto_exposure_modes\":[\"CONTROL_AE_MODE_ON\"],"
            + "\"capabilities\":[\"backward_compatible\"]}";
        Run r = ok(new String[]{"CameraInfo"}, "{\"cameras\":[" + cam + "]}");
        assertEquals("E_INVALID_RESPONSE\n", r.stderr);
        assertEquals(4, r.exit);
        assertEquals("", r.stdout);
    }

    @Test
    public void intFieldsRejectFractionAndOverflowWithoutWrapping() {
        // numeric domains: a valid int measure must be FINITE, EXACTLY
        // INTEGRAL and inside the signed int range of its native source.
        // Fractions (1.5) and int-overflow values (4294967296, 2147483648)
        // used to be truncated (1) or wrapped (0, -2147483648) into a
        // successful different measure; they are contract violations.
        String wifi = "{\"wifi_info_present\":true,\"ssid\":\"n\",\"rssi\":RSSI,\"link_speed_mbps\":100,"
            + "\"network_id\":1,\"supplicant_state\":\"COMPLETED\",\"frequency_mhz\":2437,"
            + "\"ip\":\"192.168.1.2\",\"ssid_hidden\":false}";
        String camSizes = "\"jpeg_output_sizes\":[{\"width\":WIDTH,\"height\":480}]";
        Object[][] table = {
            {new String[]{"Volume"},
                "{\"streams\":[{\"stream\":\"music\",\"volume\":1.5,\"max_volume\":15}]}"},
            {new String[]{"Volume"},
                "{\"streams\":[{\"stream\":\"music\",\"volume\":4294967296,\"max_volume\":15}]}"},
            {new String[]{"Volume"},
                "{\"streams\":[{\"stream\":\"music\",\"volume\":2147483648,\"max_volume\":15}]}"},
            {new String[]{"Volume"},
                "{\"streams\":[{\"stream\":\"music\",\"volume\":-2147483649,\"max_volume\":15}]}"},
            {new String[]{"Volume"},
                "{\"streams\":[{\"stream\":\"music\",\"volume\":5,\"max_volume\":4294967296}]}"},
            {new String[]{"BatteryStatus"},
                "{\"level\":88.5,\"charging\":true,\"current_ua\":-1,\"charge_uah\":1}"},
            {new String[]{"BatteryStatus"},
                "{\"level\":4294967296,\"charging\":true,\"current_ua\":-1,\"charge_uah\":1}"},
            {new String[]{"BatteryStatus"},
                "{\"level\":88,\"charging\":true,\"current_ua\":4294967296,\"charge_uah\":1}"},
            // charge_uah is a LONG field (getLongProperty): int overflow is a
            // real measurement there, but a fraction is still a violation
            {new String[]{"BatteryStatus"},
                "{\"level\":88,\"charging\":true,\"current_ua\":-1,\"charge_uah\":1.5}"},
            {new String[]{"WifiConnectionInfo"}, wifi.replace("RSSI", "-50.5")},
            {new String[]{"WifiConnectionInfo"}, wifi.replace("RSSI", "4294967296")},
            {new String[]{"Vibrate", "--ei", "duration_ms", "900"}, "{\"duration_ms\":1.5}"},
            {new String[]{"Vibrate", "--ei", "duration_ms", "900"}, "{\"duration_ms\":4294967296}"},
            {new String[]{"TextToSpeech", "--es", "text", "hello"}, "{\"queued\":true,\"chars\":4294967296}"},
            {new String[]{"CameraInfo"},
                "{\"cameras\":[{\"id\":\"0\",\"facing\":\"back\",\"facing_id\":1.5,"
                    + camSizes.replace("WIDTH", "640")
                    + ",\"focal_lengths\":[4.25],\"auto_exposure_modes\":[\"CONTROL_AE_MODE_ON\"],"
                    + "\"physical_size\":{\"width\":5.5,\"height\":4.25},"
                    + "\"capabilities\":[\"backward_compatible\"]}]}"},
            {new String[]{"CameraInfo"},
                "{\"cameras\":[{\"id\":\"0\",\"facing\":\"back\",\"facing_id\":0,"
                    + camSizes.replace("WIDTH", "640.5")
                    + ",\"focal_lengths\":[4.25],\"auto_exposure_modes\":[\"CONTROL_AE_MODE_ON\"],"
                    + "\"physical_size\":{\"width\":5.5,\"height\":4.25},"
                    + "\"capabilities\":[\"backward_compatible\"]}]}"},
        };
        for (Object[] row : table) {
            assertRefused((String[]) row[0], (String) row[1], "numeric domain violation");
        }
    }

    @Test
    public void intBoundsAndTheLongChargeFieldStayExact() {
        // the valid boundaries of each native numeric domain survive intact:
        // int fields keep the full signed int range, and the one long source
        // (charge_uah, BatteryManager.getLongProperty) keeps values beyond
        // Integer.MAX_VALUE without narrowing to int
        Run maxVol = ok(new String[]{"Volume"},
            "{\"streams\":[{\"stream\":\"music\",\"volume\":2147483647,\"max_volume\":15}]}");
        assertEquals(0, maxVol.exit);
        assertTrue(maxVol.stdout.contains("\"volume\": 2147483647"));
        Run minVol = ok(new String[]{"Volume"},
            "{\"streams\":[{\"stream\":\"music\",\"volume\":-2147483648,\"max_volume\":15}]}");
        assertEquals(0, minVol.exit);
        assertTrue(minVol.stdout.contains("\"volume\": -2147483648"));
        // an integral JSON decimal is the same integer the source emits
        Run decimal = ok(new String[]{"Volume"},
            "{\"streams\":[{\"stream\":\"music\",\"volume\":5.0,\"max_volume\":15}]}");
        assertEquals(0, decimal.exit);
        assertTrue(decimal.stdout.contains("\"volume\": 5,"));
        Run charge = ok(new String[]{"BatteryStatus"},
            "{\"level\":88,\"charging\":true,\"current_ua\":-450000,\"charge_uah\":4294967296}");
        assertEquals(0, charge.exit);
        assertTrue(charge.stdout.contains("\"charge_counter\": 4294967296"));
        Run chargeMax = ok(new String[]{"BatteryStatus"},
            "{\"level\":88,\"charging\":true,\"current_ua\":-450000,\"charge_uah\":9223372036854775807}");
        assertEquals(0, chargeMax.exit);
        assertTrue(chargeMax.stdout.contains("\"charge_counter\": 9223372036854775807"));
    }

    private static String mutateData(String validData, String key, String mutation) throws Exception {
        org.json.JSONObject d = new org.json.JSONObject(validData);
        if (mutation.equals("absent")) {
            d.remove(key);
        } else if (mutation.equals("null")) {
            d.put(key, org.json.JSONObject.NULL);
        } else {
            d.put(key, new org.json.JSONObject());
        }
        return d.toString();
    }

    private static void assertRefused(String[] argv, String dataJson, String what) {
        Run r = ok(argv, dataJson);
        assertEquals(what + ": stdout must stay empty", "", r.stdout);
        assertEquals(what + ": enumerated refusal expected", "E_INVALID_RESPONSE\n", r.stderr);
        assertEquals(what + ": exit must be 4", 4, r.exit);
    }

    // --- per-method error: empty stdout, enumerated stderr, nonzero exit ----

    @Test
    public void bridgeErrorIsEmptyStdoutAndEnumeratedStderr() {
        Run r = new Run(new String[]{"Torch", "--ez", "enabled", "true"}, null,
            new FakeCompatTransport().answering("{\"ok\":false,\"error\":\"No camera with torch available\"}"));
        assertEquals("", r.stdout);
        assertEquals("E_BRIDGE_ERROR\n", r.stderr);
        assertEquals(1, r.exit);
    }

    @Test
    public void nestedDataErrorIsNotASuccess() {
        // Volume unknown-stream branch (SocketServer audioVolume error)
        Run r = ok(new String[]{"Volume", "-a", "set-volume", "--es", "stream", "music", "--ei", "volume", "3"},
            "{\"error\":\"Unknown audio stream: x\"}");
        assertEquals("", r.stdout);
        assertEquals("E_BRIDGE_ERROR\n", r.stderr);
        assertEquals(1, r.exit);
    }

    @Test
    public void nestedBrightnessPermissionErrorIsExplicit() {
        Run r = ok(new String[]{"Brightness", "--ei", "brightness", "10"},
            "{\"error\":\"WRITE_SETTINGS permission is not granted\"}");
        assertEquals("", r.stdout);
        assertEquals("E_BRIDGE_ERROR\n", r.stderr);
        assertEquals(1, r.exit);
    }

    @Test
    public void ttsMissingTextIsAnErrorNotSpoken() {
        Run r = ok(new String[]{"TextToSpeech", "--es", "text", ""}, "{\"error\":\"Missing text\"}");
        assertEquals("", r.stdout);
        assertEquals("E_BRIDGE_ERROR\n", r.stderr);
        assertEquals(1, r.exit);
    }

    @Test
    public void notificationChannelIgnoredBranchIsAnExplicitError() {
        Run r = ok(new String[]{"NotificationChannel", "--es", "id", "x", "--es", "name", "y"},
            "{\"status\":\"ignored\",\"reason\":\"notification channels require Android 8+\"}");
        assertEquals("", r.stdout);
        assertTrue(r.stderr.startsWith("E_BRIDGE_ERROR"));
        assertEquals(1, r.exit);
    }

    // --- input and option validation ----------------------------------------

    @Test
    public void unknownMethodIsRejected() {
        Run r = ok(new String[]{"SensorService"}, "{\"status\":\"ok\"}");
        assertEquals("", r.stdout);
        assertEquals("E_UNKNOWN_METHOD\n", r.stderr);
        assertEquals(2, r.exit);
        assertEquals(0, r.transport.exchanges);
    }

    @Test
    public void unknownOptionIsRejectedBeforeDispatch() {
        Run r = ok(new String[]{"BatteryStatus", "--es", "whatever", "1"}, "{}");
        assertEquals("", r.stdout);
        assertEquals("E_UNSUPPORTED_OPTION\n", r.stderr);
        assertEquals(2, r.exit);
        assertEquals(0, r.transport.exchanges);
    }

    @Test
    public void unsupportedUpstreamOptionIsRejectedNotSilentlyDropped() {
        // Vibrate -f / Notification --action: the bridge ignores them, so the
        // client refuses instead of faking compatibility.
        Run force = ok(new String[]{"Vibrate", "--ez", "force", "true"}, "{\"duration_ms\":1}");
        assertEquals("E_UNSUPPORTED_OPTION\n", force.stderr);
        assertEquals(2, force.exit);
        Run action = ok(new String[]{"Notification", "--es", "action", "ls"}, "{}");
        assertEquals("E_UNSUPPORTED_OPTION\n", action.stderr);
        assertEquals(2, action.exit);
        Run colors = ok(new String[]{"Toast", "--es", "text_color", "#FF0000"}, "{}");
        assertEquals("E_UNSUPPORTED_OPTION\n", colors.stderr);
    }

    @Test
    public void wrongTypeIsRejected() {
        Run r = ok(new String[]{"Brightness", "--ei", "brightness", "notanumber"}, "{}");
        assertEquals("E_BAD_TYPE\n", r.stderr);
        assertEquals(2, r.exit);
    }

    @Test
    public void badVolumeStreamAndNegativeVolumeAreRejected() {
        Run stream = ok(new String[]{"Volume", "-a", "set-volume", "--es", "stream", "nope", "--ei", "volume", "3"}, "{}");
        assertEquals("E_BAD_VALUE\n", stream.stderr);
        assertEquals(2, stream.exit);
        Run vol = ok(new String[]{"Volume", "-a", "set-volume", "--es", "stream", "music", "--ei", "volume", "-1"}, "{}");
        assertEquals("E_BAD_VALUE\n", vol.stderr);
    }

    @Test
    public void missingRequiredArgsAreRejected() {
        assertEquals(2, ok(new String[]{"Torch"}, "{}").exit);
        assertEquals(2, ok(new String[]{"NotificationRemove"}, "{}").exit);
        assertEquals(2, ok(new String[]{"NotificationChannel"}, "{}").exit);
        assertEquals(2, ok(new String[]{"Brightness"}, "{}").exit);
        assertEquals(2, ok(new String[]{"Volume", "-a", "set-volume"}, "{}").exit);
    }

    @Test
    public void volumeListRejectsExtras() {
        Run r = ok(new String[]{"Volume", "--es", "stream", "music"}, "{}");
        assertEquals("E_BAD_USAGE\n", r.stderr);
    }

    // --- Clipboard / Volume / Notification precedence -----------------------

    @Test
    public void clipboardSetUsesApiVersion2EnvelopeOfTheWrapper() {
        Run r = okStdin(new String[]{"Clipboard", "-e", "api_version", "2", "--ez", "set", "true"},
            "from stdin", "{\"status\":\"ok\"}");
        assertEquals("{\"cmd\":\"sys.clipboard.set\",\"args\":{\"text\":\"from stdin\"}}",
            r.transport.lastRequest);
        assertEquals("", r.stdout);
        assertEquals(0, r.exit);
    }

    @Test
    public void clipboardSetExplicitTextBeatsStdin() {
        Run r = okStdin(new String[]{"Clipboard", "-e", "api_version", "2", "--ez", "set", "true",
            "--es", "text", "explicit"}, "from stdin", "{\"status\":\"ok\"}");
        assertTrue(r.transport.lastRequest.contains("\"text\":\"explicit\""));
    }

    @Test
    public void clipboardSetEmptyStdinDeliversEmptyText() {
        Run r = okStdin(new String[]{"Clipboard", "--ez", "set", "true"}, "", "{\"status\":\"ok\"}");
        assertEquals("{\"cmd\":\"sys.clipboard.set\",\"args\":{\"text\":\"\"}}", r.transport.lastRequest);
    }

    @Test
    public void clipboardSetPreservesQuotesBackslashUnicodeNewlines() throws Exception {
        String nasty = "a\"b\\c à😀\nline2\n\n";
        Run r = okStdin(new String[]{"Clipboard", "--ez", "set", "true"}, nasty, "{\"status\":\"ok\"}");
        // delivered to the dispatcher byte-exact: reparse the wire line
        org.json.JSONObject wire = new org.json.JSONObject(r.transport.lastRequest);
        assertEquals(nasty, wire.getJSONObject("args").getString("text"));
    }

    @Test
    public void clipboardSetRejectsApiVersion1() {
        Run r = okStdin(new String[]{"Clipboard", "-e", "api_version", "1", "--ez", "set", "true"},
            "x", "{\"status\":\"ok\"}");
        assertEquals("E_BAD_VALUE\n", r.stderr);
        assertEquals(2, r.exit);
    }

    @Test
    public void volumeSetSendsTypedStreamAndVolume() {
        Run r = ok(new String[]{"Volume", "-a", "set-volume", "--es", "stream", "ring", "--ei", "volume", "4"},
            "{\"stream\":\"ring\",\"volume\":4,\"max_volume\":7}");
        assertEquals("{\"cmd\":\"audio.volume\",\"args\":{\"stream\":\"ring\",\"volume\":4}}",
            r.transport.lastRequest);
    }

    @Test
    public void notificationContentPrecedenceExplicitStdinEmpty() {
        // explicit extra beats stdin
        Run explicit = okStdin(new String[]{"Notification", "--es", "title", "t", "--es", "text", "explicit"},
            "from stdin", "{\"status\":\"posted\"}");
        assertTrue(explicit.transport.lastRequest.contains("\"text\":\"explicit\""));
        // stdin delivered when no explicit content
        Run viaStdin = okStdin(new String[]{"Notification", "--es", "title", "t"},
            "body from stdin", "{\"status\":\"posted\"}");
        assertTrue(viaStdin.transport.lastRequest.contains("\"text\":\"body from stdin\""));
        // empty stdin is still delivered (empty body), not replaced by a default
        Run empty = okStdin(new String[]{"Notification", "--es", "title", "t"}, "", "{\"status\":\"posted\"}");
        assertTrue(empty.transport.lastRequest.contains("\"text\":\"\""));
    }

    // --- stdin is read only when the branch needs it --------------------

    @Test
    public void clipboardGetNeverReadsStdin() {
        UnreadableStdin probe = new UnreadableStdin();
        Run r = new Run(new String[]{"Clipboard"}, probe,
            new FakeCompatTransport().answering("{\"ok\":true,\"data\":{\"text\":\"clip\"}}"));
        assertEquals("clip", r.stdout);
        assertEquals(0, r.exit);
        assertEquals(1, r.transport.exchanges); // the valid answer was dispatched
        assertEquals("stdin was read on a get branch", 0, probe.reads);
    }

    @Test
    public void ttsEnginesListNeverReadsStdin() {
        UnreadableStdin probe = new UnreadableStdin();
        Run r = new Run(new String[]{"TextToSpeech", "--es", "engine", "LIST_AVAILABLE"}, probe,
            new FakeCompatTransport().answering(
                "{\"ok\":true,\"data\":{\"engines\":[{\"name\":\"e\",\"label\":\"E\",\"default\":true}]}}"));
        assertEquals(0, r.exit);
        assertEquals(0, probe.reads);
        assertTrue(r.stdout.startsWith("[\n"));
    }

    @Test
    public void explicitContentExtraNeverReadsStdin() {
        UnreadableStdin probe = new UnreadableStdin();
        Run clip = new Run(new String[]{"Clipboard", "--ez", "set", "true", "--es", "text", "explicit"},
            probe, new FakeCompatTransport().answering("{\"ok\":true,\"data\":{\"status\":\"ok\"}}"));
        assertEquals(0, clip.exit);
        assertTrue(clip.transport.lastRequest.contains("\"text\":\"explicit\""));
        UnreadableStdin probe2 = new UnreadableStdin();
        Run note = new Run(new String[]{"Notification", "--es", "text", "explicit"}, probe2,
            new FakeCompatTransport().answering("{\"ok\":true,\"data\":{\"status\":\"posted\"}}"));
        assertEquals(0, note.exit);
        UnreadableStdin probe3 = new UnreadableStdin();
        Run tts = new Run(new String[]{"TextToSpeech", "--es", "text", "explicit"}, probe3,
            new FakeCompatTransport().answering("{\"ok\":true,\"data\":{\"queued\":true,\"chars\":8}}"));
        assertEquals(0, tts.exit);
        assertEquals(0, probe.reads + probe2.reads + probe3.reads);
    }

    @Test
    public void unsupportedOptionLeavesStdinUntouched() {
        UnreadableStdin probe = new UnreadableStdin();
        Run r = new Run(new String[]{"Clipboard", "--es", "bogus", "1"}, probe,
            new FakeCompatTransport().answering("{\"ok\":true,\"data\":{}}"));
        assertEquals("", r.stdout);
        assertEquals("E_UNSUPPORTED_OPTION\n", r.stderr);
        assertEquals(2, r.exit);
        assertEquals(0, r.transport.exchanges); // refused before the transport
        assertEquals(0, probe.reads);           // and before any stdin access
    }

    // --- robustness: malformed / oversize / disconnect / no-retry ------------

    @Test
    public void malformedResponseIsRefused() {
        Run r = new Run(new String[]{"BatteryStatus"}, null,
            new FakeCompatTransport().answering("not json at all"));
        assertEquals("", r.stdout);
        assertEquals("E_INVALID_RESPONSE\n", r.stderr);
        assertEquals(4, r.exit);
    }

    @Test
    public void responseMissingOkIsRefused() {
        Run r = new Run(new String[]{"BatteryStatus"}, null,
            new FakeCompatTransport().answering("{\"data\":{}}"));
        assertEquals("E_INVALID_RESPONSE\n", r.stderr);
    }

    @Test
    public void truncatedEnvelopeIsRefused() {
        Run r = new Run(new String[]{"BatteryStatus"}, null,
            new FakeCompatTransport().answering("{\"ok\":true"));
        assertEquals("E_INVALID_RESPONSE\n", r.stderr);
        assertEquals(4, r.exit);
    }

    @Test
    public void transportFailureAfterSendIsNeverRetried() {
        FakeCompatTransport t = new FakeCompatTransport().failing(
            new CompatTransport.CompatTransportException(CompatError.E_EOF, true, null));
        Run r = new Run(new String[]{"Torch", "--ez", "enabled", "true"}, null, t);
        assertEquals("", r.stdout);
        assertEquals("E_EOF\n", r.stderr);
        assertEquals(3, r.exit);
        assertEquals(1, t.exchanges); // exactly one exchange, no retry
    }

    @Test
    public void connectFailureReportsConnect() {
        FakeCompatTransport t = new FakeCompatTransport().failing(
            new CompatTransport.CompatTransportException(CompatError.E_CONNECT, false, "no socket"));
        Run r = new Run(new String[]{"BatteryStatus"}, null, t);
        assertEquals("E_CONNECT\n", r.stderr);
        assertEquals(3, r.exit);
    }

    @Test
    public void stdinBeyondTheCapIsRefused() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < CompatLimits.MAX_STDIN_BYTES / 64 + 2; i++) big.append("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        Run r = okStdin(new String[]{"Clipboard", "--ez", "set", "true"}, big.toString(), "{\"status\":\"ok\"}");
        assertEquals("", r.stdout);
        assertEquals("E_STDIN_TOO_LARGE\n", r.stderr);
        assertEquals(2, r.exit);
        assertEquals(0, r.transport.exchanges);
    }

    @Test
    public void requestBeyondTheCapIsRefusedBeforeSending() {
        // explicit text extra past the cap: parse succeeds, wire line too big
        StringBuilder big = new StringBuilder();
        while (big.length() < CompatLimits.MAX_REQUEST_BYTES / 4 + 1024) big.append('"');
        // escaped quotes double: force the encoded line past 256 KiB
        while (big.length() < CompatLimits.MAX_REQUEST_BYTES / 2 + 2048) big.append('"');
        Run r = new Run(new String[]{"Clipboard", "--ez", "set", "true", "--es", "text", big.toString()},
            FakeCompatTransport.noStdin(), new FakeCompatTransport().answering("{\"ok\":true,\"data\":{}}"));
        assertEquals("E_REQUEST_TOO_LARGE\n", r.stderr);
        assertEquals(2, r.exit);
        assertEquals(0, r.transport.exchanges);
    }

    @Test
    public void emptyArgvIsRejected() {
        Run r = ok(new String[]{}, "{}");
        assertEquals("E_UNKNOWN_METHOD\n", r.stderr);
    }

    // --- the stderr diagnostic is a fixed vocabulary, nothing raw -------

    @Test
    public void bridgeErrorTextIsNeverRepeatedOnStderr() {
        // synthetic markers stand in for anything sensitive the bridge could
        // return: none of it may reach stderr, not even sanitized
        String marker = "/srv/private/area PRIVATE_HOST internal.secret";
        Run r = new Run(new String[]{"Torch", "--ez", "enabled", "true"}, null,
            new FakeCompatTransport().answering("{\"ok\":false,\"error\":\"" + marker + " x\ny\"}"));
        assertEquals("E_BRIDGE_ERROR\n", r.stderr);
        assertEquals("", r.stdout);
        assertFalse(r.stderr.contains("private"));
        assertFalse(r.stderr.contains("internal"));
    }

    @Test
    public void nestedDataErrorIsNeverRepeatedOnStderr() {
        Run r = ok(new String[]{"Volume", "-a", "set-volume", "--es", "stream", "music", "--ei", "volume", "3"},
            "{\"error\":\"leak\u0007marker /tmp/x path\n\"}");
        assertEquals("E_BRIDGE_ERROR\n", r.stderr);
        assertFalse(r.stderr.contains("leak"));
        assertFalse(r.stderr.contains("/tmp"));
    }

    @Test
    public void transportExceptionMessagesAreNeverRepeatedOnStderr() {
        FakeCompatTransport t = new FakeCompatTransport().failing(
            new CompatTransport.CompatTransportException(CompatError.E_CONNECT, false,
                "connect failed: /data/data/com.termux/ SECRET"));
        Run r = new Run(new String[]{"BatteryStatus"}, null, t);
        assertEquals("E_CONNECT\n", r.stderr);
        assertFalse(r.stderr.contains("/data"));
    }

    @Test
    public void argvValuesAreNeverRepeatedOnStderr() {
        // unknown method, unknown option and bad typed value carrying
        // control characters: only the code is printed, never the argv text
        Run method = ok(new String[]{"Bad\u0007Method\n/path"}, "{}");
        assertEquals("E_UNKNOWN_METHOD\n", method.stderr);
        Run option = ok(new String[]{"BatteryStatus", "--es", "x\u0007y", "v"}, "{}");
        assertEquals("E_UNSUPPORTED_OPTION\n", option.stderr);
        Run type = ok(new String[]{"Brightness", "--ei", "brightness", "no\u0007ta number"}, "{}");
        assertEquals("E_BAD_TYPE\n", type.stderr);
        assertFalse(type.stderr.contains("number"));
    }

    @Test
    public void stderrIsAlwaysExactlyOneLineFromTheVocabulary() {
        String[] codes = {"E_UNKNOWN_METHOD", "E_UNSUPPORTED_OPTION", "E_BAD_TYPE", "E_BAD_USAGE",
            "E_BAD_VALUE", "E_STDIN_TOO_LARGE", "E_REQUEST_TOO_LARGE", "E_CONNECT", "E_EOF",
            "E_TIMEOUT", "E_RESPONSE_TOO_LARGE", "E_INVALID_RESPONSE", "E_BRIDGE_ERROR", "E_INTERNAL"};
        java.util.Set<String> vocabulary = new java.util.HashSet<>(java.util.Arrays.asList(codes));
        for (CompatError code : CompatError.values()) {
            assertTrue("code not in the documented vocabulary: " + code, vocabulary.contains(code.name()));
            assertFalse("stderr line must be the bare code: " + code, code.stderrLine().contains(" "));
            assertFalse(code.stderrLine().contains("\n"));
        }
    }

    // --- regression: transient v1 wire, gate v2 stays closed ------------

    @Test
    public void theWireLineNeverCarriesProtocolOrV2Fields() throws Exception {
        // no protocol=2, no requestId, no input field: the client is v1-only
        // (transient v1 wire only) and V2_ENABLED/INPUT_UNSUPPORTED in AiV2Exchange are untouched
        Run r = ok(new String[]{"BatteryStatus"},
            "{\"level\":50,\"charging\":false,\"current_ua\":0,\"charge_uah\":0}");
        assertEquals("{\"cmd\":\"sys.battery\",\"args\":{}}", r.transport.lastRequest);
        org.json.JSONObject wire = new org.json.JSONObject(r.transport.lastRequest);
        assertFalse(wire.has("protocol"));
        assertFalse(wire.has("requestId"));
        assertFalse(wire.has("input"));
        assertEquals(0, r.exit);
    }

    @Test
    public void stdinIsNeverPlacedInArgvOrAShellCommand() throws Exception {
        // the text rides as a JSON string arg of sys.clipboard.set, not as a
        // command and not through any shell substitution
        Run r = okStdin(new String[]{"Clipboard", "--ez", "set", "true"}, "x; rm -rf /", "{\"status\":\"ok\"}");
        org.json.JSONObject wire = new org.json.JSONObject(r.transport.lastRequest);
        assertEquals("x; rm -rf /", wire.getJSONObject("args").getString("text"));
        assertEquals(0, r.exit);
    }

    @Test
    public void toastAndTtsSpeakConsumeStdinByteExact() throws Exception {
        Run toast = okStdin(new String[]{"Toast"}, "toast \"quoted\" back\\slash\n", "{\"status\":\"shown\"}");
        org.json.JSONObject wire = new org.json.JSONObject(toast.transport.lastRequest);
        assertEquals("toast \"quoted\" back\\slash\n", wire.getJSONObject("args").getString("text"));
        Run tts = okStdin(new String[]{"TextToSpeech"}, "speak this", "{\"queued\":true,\"chars\":10}");
        assertTrue(tts.transport.lastRequest.contains("\"text\":\"speak this\""));
        assertEquals("", tts.stdout); // speak success prints nothing
    }

    @Test
    public void notificationPreservesReceivedNewlines() throws Exception {
        // Newline policy: the client preserves what it receives; it does NOT promise to
        // rebuild the newlines the upstream wrapper already trimmed at the pin
        Run r = okStdin(new String[]{"Notification", "--es", "title", "t"}, "line1\nline2\n\n",
            "{\"status\":\"posted\"}");
        org.json.JSONObject wire = new org.json.JSONObject(r.transport.lastRequest);
        assertEquals("line1\nline2\n\n", wire.getJSONObject("args").getString("text"));
    }
}
