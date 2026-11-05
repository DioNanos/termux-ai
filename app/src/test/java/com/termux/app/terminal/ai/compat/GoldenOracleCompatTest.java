package com.termux.app.terminal.ai.compat;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * The frozen compat goldens are the ONLY oracle of bytes, newline and format
 * For EVERY golden under fixtures/golden/compat, the paired
 * native input under fixtures/golden/s1b1-native (same basename, the data
 * object of an ok:true envelope) must drive the real client — parser,
 * adapter, exit-code mapping — to byte-identical stdout, empty stderr and
 * exit 0. No expectation is rewritten by hand here: the golden file is the
 * assertion. A golden may only change with a proof at the upstream pin
 * (GOLDEN_CONTRACT.md).
 *
 * What this certifies and what it does not: byte-exactness of the client
 * against the frozen synthetic goldens for the branches the goldens
 * represent (GOLDEN_CONTRACT.md §2). Branches without a golden (battery
 * optional-key gaps, camera external lens, wifi redacted ssid, channel
 * "ignored", audio-info without the additive track keys) stay covered only
 * by the suite's synthetic expectations in TermuxApiCompatClientTest, and
 * real-device byte qualification remains CI material.
 */
public class GoldenOracleCompatTest {

    /** golden basename (no extension) → the compat argv that requests it. */
    private static Map<String, String[]> argvByGolden() {
        Map<String, String[]> m = new HashMap<>();
        m.put("AudioInfo", new String[]{"AudioInfo"});
        m.put("BatteryStatus", new String[]{"BatteryStatus"});
        m.put("Brightness-empty", new String[]{"Brightness", "--ei", "brightness", "128"});
        m.put("CameraInfo", new String[]{"CameraInfo"});
        m.put("Clipboard-get", new String[]{"Clipboard"});
        // content branches without an explicit extra: the empty-but-closed
        // stdin delivers "", the golden pins the empty stdout
        m.put("Clipboard-set-empty", new String[]{"Clipboard", "--ez", "set", "true"});
        m.put("Notification-empty", new String[]{"Notification", "--es", "title", "t"});
        m.put("NotificationChannel-create",
            new String[]{"NotificationChannel", "--es", "id", "synthetic-channel", "--es", "name", "Synthetic"});
        m.put("NotificationChannel-delete",
            new String[]{"NotificationChannel", "--ez", "delete", "true", "--es", "id", "synthetic-channel"});
        m.put("NotificationRemove-empty", new String[]{"NotificationRemove", "--es", "id", "7"});
        m.put("TextToSpeech-LIST_AVAILABLE", new String[]{"TextToSpeech", "--es", "engine", "LIST_AVAILABLE"});
        m.put("TextToSpeech-speak-empty", new String[]{"TextToSpeech", "--es", "text", "hello"});
        m.put("Toast-empty", new String[]{"Toast", "--es", "text", "hi"});
        m.put("Torch-empty", new String[]{"Torch", "--ez", "enabled", "true"});
        m.put("Vibrate-empty", new String[]{"Vibrate", "--ei", "duration_ms", "900"});
        m.put("Volume-list", new String[]{"Volume"});
        m.put("Volume-set-empty",
            new String[]{"Volume", "-a", "set-volume", "--es", "stream", "music", "--ei", "volume", "5"});
        m.put("WifiConnectionInfo", new String[]{"WifiConnectionInfo"});
        m.put("WifiConnectionInfo-unconnected", new String[]{"WifiConnectionInfo"});
        return m;
    }

    @Test
    public void everyGoldenIsReproducedByteExactlyFromItsPairedNativeInput() throws Exception {
        File compatDir = new File(compatRoot(), "fixtures/golden/compat");
        File[] goldens = compatDir.listFiles();
        assertNotNull("compat golden directory not found: " + compatDir, goldens);
        Arrays.sort(goldens);
        assertTrue("no goldens found in " + compatDir, goldens.length > 0);
        for (File golden : goldens) {
            String base = baseName(golden);
            String[] argv = argvByGolden().get(base);
            assertNotNull("no argv wired for golden " + golden.getName()
                + " — wire it or the golden is not the oracle for it", argv);

            String nativeJson = read(new File(compatDir.getParentFile(), "s1b1-native/" + base + ".json")).trim();
            byte[] expected = Files.readAllBytes(golden.toPath());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            FakeCompatTransport t = new FakeCompatTransport()
                .answering("{\"ok\":true,\"data\":" + nativeJson + "}");
            int exit = TermuxApiCompatClient.run(argv, FakeCompatTransport.noStdin(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), t, CompatClock.SYSTEM);

            assertArrayEquals("stdout bytes must equal the frozen golden " + golden.getName()
                + " (newline and format included)", expected, out.toByteArray());
            assertEquals("stderr for " + golden.getName(), "", err.toString());
            assertEquals("exit for " + golden.getName(), 0, exit);
            assertEquals("exactly one exchange for " + golden.getName(), 1, t.exchanges);
        }
    }

    @Test
    public void thePairingHasNoOrphanOnEitherSide() throws Exception {
        File compatDir = new File(compatRoot(), "fixtures/golden/compat");
        File nativeDir = new File(compatRoot(), "fixtures/golden/s1b1-native");
        Map<String, String[]> wired = argvByGolden();
        for (String name : compatDir.list()) {
            assertTrue("golden without a wired argv/native pair: " + name, wired.containsKey(baseName(new File(name))));
        }
        for (String name : nativeDir.list()) {
            String base = baseName(new File(name));
            assertTrue("native input without a golden: " + name, wired.containsKey(base));
            assertTrue("native input whose golden is missing: " + name, goldenFileFor(compatDir, base).isFile());
        }
        assertEquals("every wired entry must have its golden on disk (19 expected)", 19, wired.size());
    }

    // --- helpers -------------------------------------------------------------

    private static File goldenFileFor(File compatDir, String base) {
        File json = new File(compatDir, base + ".json");
        return json.isFile() ? json : new File(compatDir, base + ".txt");
    }

    private static String baseName(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(0, i);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * tests/compat via the system property the harness passes, else the same
     * walk-up AiCompatContractTest uses (app module dir or repo root).
     */
    private static File compatRoot() throws Exception {
        String viaProperty = System.getProperty("termuxai.compat.root");
        if (viaProperty != null) {
            File dir = new File(viaProperty);
            assertTrue("termuxai.compat.root is not tests/compat: " + viaProperty,
                new File(dir, "MATRIX.md").isFile());
            return dir;
        }
        for (String candidate : new String[]{"../tests/compat", "tests/compat"}) {
            File dir = new File(candidate);
            if (new File(dir, "MATRIX.md").isFile()) return dir;
        }
        throw new AssertionError("tests/compat not found from the test working directory"
            + " (pass -Dtermuxai.compat.root=…)");
    }
}
