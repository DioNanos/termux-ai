package com.termux.app.terminal.ai;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Backend-path test: the REAL cameraInfo()/torch() producers driven
 * with controlled synthetic measurements through the executable
 * CameraCharacteristics double (identity-keyed get, platform surface only).
 * This is the producer side of the facing/facing_id coherence the fixture
 * contract asserts on the inputs: facing and facing_id must come out of ONE
 * LENS_FACING value, and torch must name a camera that has a flash. The
 * double lives in the harness (not app/src/test) because it needs the
 * executable constructor; Android-faithful behaviour stays CI material.
 */
public class CameraProducerPathTest {

    static final class SyntheticCameras extends CameraManager {
        // insertion order: the producer walks getCameraIdList() in order
        final Map<String, CameraCharacteristics> cameras = new java.util.LinkedHashMap<>();

        @Override public String[] getCameraIdList() {
            return cameras.keySet().toArray(new String[0]);
        }

        @Override public CameraCharacteristics getCameraCharacteristics(String cameraId) {
            return cameras.get(cameraId);
        }

        @Override public void setTorchMode(String cameraId, boolean enabled) {}
    }

    static final class CameraContext extends Context {
        final SyntheticCameras cameras;
        CameraContext(SyntheticCameras cameras) { this.cameras = cameras; }
        @Override public Object getSystemService(String name) {
            return Context.CAMERA_SERVICE.equals(name) ? cameras : null;
        }
        @Override public Context getApplicationContext() { return this; }
    }

    private static CameraCharacteristics camera(Integer facing, Boolean flash) {
        Map<CameraCharacteristics.Key<?>, Object> values = new HashMap<>();
        if (facing != null) values.put(CameraCharacteristics.LENS_FACING, facing);
        if (flash != null) values.put(CameraCharacteristics.FLASH_INFO_AVAILABLE, flash);
        return new CameraCharacteristics(values);
    }

    private static SyntheticCameras twoLensDevice() {
        SyntheticCameras manager = new SyntheticCameras();
        // exactly the measurement set the s1b1-native CameraInfo fixture
        // represents: a back camera (LENS_FACING_BACK=1) with flash and a
        // front camera (LENS_FACING_FRONT=0) without
        manager.cameras.put("0", camera(1, true));
        manager.cameras.put("1", camera(0, false));
        return manager;
    }

    @Test
    public void facingAndFacingIdComeFromOneLensFacingValue() throws Exception {
        Context context = new CameraContext(twoLensDevice());
        JSONObject data = dispatchCameraInfo(context);
        JSONArray cameras = data.getJSONArray("cameras");
        assertEquals(2, cameras.length());
        for (int i = 0; i < cameras.length(); i++) {
            JSONObject c = cameras.optJSONObject(i);
            String facing = c.getString("facing");
            if (c.has("facing_id")) {
                int facingId = c.getInt("facing_id");
                int expected = facing.equals("front") ? 0 : facing.equals("back") ? 1 : 2;
                assertEquals("facing/facing_id derive from the same LENS_FACING",
                    expected, facingId);
            } else {
                assertEquals("no facing_id only when LENS_FACING was unreadable",
                    "unknown", facing);
            }
            // flash_available is always emitted, defaulted to false when null
            assertEquals(Boolean.class, c.get("flash_available").getClass());
        }
        // the synthetic back camera is id 0 → facing back, facing_id 1
        JSONObject back = cameras.optJSONObject(0);
        assertEquals("0", back.getString("id"));
        assertEquals("back", back.getString("facing"));
        assertEquals(1, back.getInt("facing_id"));
        assertTrue(back.getBoolean("flash_available"));
        // ...and the synthetic front camera is id 1 → front, facing_id 0
        JSONObject front = cameras.optJSONObject(1);
        assertEquals("1", front.getString("id"));
        assertEquals("front", front.getString("facing"));
        assertEquals(0, front.getInt("facing_id"));
        assertFalse(front.getBoolean("flash_available"));
    }

    @Test
    public void unknownLensFacingEmitsUnknownWithoutFacingId() throws Exception {
        SyntheticCameras manager = new SyntheticCameras();
        manager.cameras.put("7", camera(null, null));
        JSONObject c = dispatchCameraInfo(new CameraContext(manager))
            .getJSONArray("cameras").optJSONObject(0);
        assertEquals("unknown", c.getString("facing"));
        assertFalse("an unreadable LENS_FACING cannot carry a facing_id", c.has("facing_id"));
        assertFalse(c.getBoolean("flash_available"));
    }

    @Test
    public void torchNamesTheFirstCameraThatActuallyHasAFlash() throws Exception {
        Context context = new CameraContext(twoLensDevice());
        JSONObject torch = dispatch(context, "{\"cmd\":\"camera.torch\",\"args\":{\"enabled\":true}}")
            .getJSONObject("data");
        assertEquals("on", torch.getString("status"));
        // the producer walks getCameraIdList() and stops at the first
        // FLASH_INFO_AVAILABLE camera: id 0, the back one
        assertEquals("0", torch.getString("camera"));
    }

    private static JSONObject dispatchCameraInfo(Context context) throws Exception {
        return dispatch(context, "{\"cmd\":\"camera.info\",\"args\":{}}").getJSONObject("data");
    }

    private static JSONObject dispatch(Context context, String request) throws Exception {
        JSONObject envelope = new JSONObject(TermuxAiSocketServer.processRequest(context, request));
        assertTrue(envelope.getBoolean("ok"));
        return envelope;
    }
}
