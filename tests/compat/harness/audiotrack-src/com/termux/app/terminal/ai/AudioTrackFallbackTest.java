package com.termux.app.terminal.ai;

import android.content.Context;
import android.media.AudioManager;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests of the audio.info AudioTrack enrichment fallback, executed
 * against the REAL server dispatch (TermuxAiSocketServer.processRequest)
 * with a synthetic Context/AudioManager and the harness AudioTrack double
 * steered by system properties. The double carries the platform surface
 * only — no framework hook — and Android-faithful behaviour stays CI
 * material. The test lives in the harness (not app/src/test) precisely
 * because it needs the double: nothing here references a framework symbol
 * that the real SDK lacks.
 *
 * Invariants under test:
 *  - a RECOVERABLE measurement failure keeps the legacy six-key success;
 *  - a FATAL error (AssertionError) is never turned into that success;
 *  - a successfully built track is released EXACTLY once on every path.
 */
public class AudioTrackFallbackTest {

    private static final String REQUEST = "{\"cmd\":\"audio.info\",\"args\":{}}";

    static final class AudioContext extends Context {
        @Override public Object getSystemService(String name) {
            if (!Context.AUDIO_SERVICE.equals(name)) return null;
            return new AudioManager() {
                @Override public String getProperty(String key) { return "48000"; }
                @Override public boolean isBluetoothA2dpOn() { return false; }
                @Override public boolean isWiredHeadsetOn() { return false; }
                @Override public boolean isSpeakerphoneOn() { return false; }
                @Override public boolean isMusicActive() { return false; }
                @Override public int getStreamMaxVolume(int streamType) { return 15; }
                @Override public int getStreamVolume(int streamType) { return 5; }
                @Override public void setStreamVolume(int streamType, int index, int flags) {}
            };
        }
        @Override public Context getApplicationContext() { return this; }
    }

    private static void resetProperties() {
        System.setProperty("termuxai.harness.audiotrack.build", "ok");
        System.setProperty("termuxai.harness.audiotrack.measure", "ok");
        System.setProperty("termuxai.harness.audiotrack.releases", "0");
    }

    private static int releases() {
        return Integer.parseInt(System.getProperty("termuxai.harness.audiotrack.releases", "0"));
    }

    @Test
    public void recoverableFailureKeepsTheLegacySuccess() throws Exception {
        resetProperties();
        System.setProperty("termuxai.harness.audiotrack.build", "recoverable");
        JSONObject data = dispatchOk();
        // legacy keys all present, additive keys absent
        assertEquals("48000", data.getString("sample_rate"));
        assertEquals("48000", data.getString("frames_per_buffer"));
        assertFalse(data.has("audiotrack_sample_rate"));
        assertFalse(data.has("audiotrack_buffer_size_in_frames"));
        assertEquals(0, releases()); // nothing was built, nothing to release

        resetProperties();
        System.setProperty("termuxai.harness.audiotrack.measure", "recoverable");
        data = dispatchOk();
        assertFalse(data.has("audiotrack_sample_rate"));
        assertEquals(1, releases()); // the built track is released exactly once
    }

    @Test
    public void fatalInvariantFailureIsNeverALegacySuccess() throws Exception {
        resetProperties();
        System.setProperty("termuxai.harness.audiotrack.build", "fatal");
        try {
            TermuxAiSocketServer.processRequest(new AudioContext(), REQUEST);
            fail("an AssertionError during build must propagate, not succeed");
        } catch (AssertionError expected) {
            assertEquals("synthetic invariant failure in build", expected.getMessage());
        }

        resetProperties();
        System.setProperty("termuxai.harness.audiotrack.measure", "fatal");
        try {
            TermuxAiSocketServer.processRequest(new AudioContext(), REQUEST);
            fail("an AssertionError during measurement must propagate, not succeed");
        } catch (AssertionError expected) {
            assertEquals("synthetic invariant failure in measure", expected.getMessage());
        }
        assertEquals(1, releases()); // released on the error path too
    }

    @Test
    public void normalMeasurementAddsTheKeysAndReleasesOnce() throws Exception {
        resetProperties();
        JSONObject data = dispatchOk();
        assertEquals(48000, data.getInt("audiotrack_sample_rate"));
        assertEquals(256, data.getInt("audiotrack_buffer_size_in_frames"));
        assertEquals(1, releases());
    }

    private static JSONObject dispatchOk() throws Exception {
        String raw = TermuxAiSocketServer.processRequest(new AudioContext(), REQUEST);
        JSONObject envelope = new JSONObject(raw);
        assertTrue(envelope.getBoolean("ok"));
        return envelope.getJSONObject("data");
    }
}
