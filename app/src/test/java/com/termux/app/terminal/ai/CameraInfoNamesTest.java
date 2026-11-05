package com.termux.app.terminal.ai;

import android.hardware.camera2.CameraMetadata;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The pin-exact camera name tables (CameraInfoAPI.java:66-139 at blob
 * 760c1777): every value the upstream switch knows maps to its
 * exact name, and an unknown value returns null so the backend emits the
 * raw NUMBER exactly like the pin writer (never an invented token).
 */
public class CameraInfoNamesTest {

    @Test
    public void everyKnownAeModeHasItsExactPinName() {
        assertEquals("CONTROL_AE_MODE_OFF", CameraInfoNames.aeModeName(CameraMetadata.CONTROL_AE_MODE_OFF));
        assertEquals("CONTROL_AE_MODE_ON", CameraInfoNames.aeModeName(CameraMetadata.CONTROL_AE_MODE_ON));
        assertEquals("CONTROL_AE_MODE_ON_AUTO_FLASH",
            CameraInfoNames.aeModeName(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH));
        assertEquals("CONTROL_AE_MODE_ON_ALWAYS_FLASH",
            CameraInfoNames.aeModeName(CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH));
        assertEquals("CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE",
            CameraInfoNames.aeModeName(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE));
        assertEquals("CONTROL_AE_MODE_ON_EXTERNAL_FLASH",
            CameraInfoNames.aeModeName(CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH));
    }

    @Test
    public void unknownAeModeIsNullSoTheBackendEmitsTheNumber() {
        assertNull(CameraInfoNames.aeModeName(99));
        assertNull(CameraInfoNames.aeModeName(-1));
    }

    @Test
    public void everyKnownCapabilityHasItsExactPinName() {
        assertEquals("backward_compatible",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE));
        assertEquals("burst_capture",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE));
        assertEquals("constrained_high_speed_video",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO));
        assertEquals("depth_output",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT));
        // the three names the previous switch was missing
        assertEquals("logical_multi_camera",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA));
        assertEquals("manual_post_processing",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING));
        assertEquals("manual_sensor",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR));
        assertEquals("monochrome",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME));
        assertEquals("motion_tracking",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING));
        assertEquals("private_reprocessing",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING));
        assertEquals("raw",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW));
        assertEquals("read_sensor_settings",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS));
        assertEquals("yuv_reprocessing",
            CameraInfoNames.capabilityName(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING));
    }

    @Test
    public void unknownCapabilityIsNullSoTheBackendEmitsTheNumber() {
        assertNull(CameraInfoNames.capabilityName(99));
        assertNull(CameraInfoNames.capabilityName(13)); // SECURE_IMAGE_DATA: not in the pin switch
    }
}
