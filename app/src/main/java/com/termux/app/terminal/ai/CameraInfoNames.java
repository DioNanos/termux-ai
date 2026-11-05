package com.termux.app.terminal.ai;

import android.hardware.camera2.CameraMetadata;

/**
 * Pin-exact name tables of the camera enums the cameraInfo enrichment
 * emits (CameraInfoAPI.java:66-139 at the pinned upstream blob 760c1777).
 *
 * An UNKNOWN value returns null: at the pin the writer emits the raw NUMBER
 * (out.value(flashMode) / out.value(capability)), never an invented token
 * like "capability_N" or "CONTROL_AE_MODE_N". Keeping this table
 * in a pure class lets the JVM harness enumerate every known value and the
 * unknown fallback without an Android runtime.
 */
public final class CameraInfoNames {

    private CameraInfoNames() {}

    /** @return the upstream CONTROL_AE_MODE_* name, or null when unknown. */
    public static String aeModeName(int mode) {
        switch (mode) {
            case CameraMetadata.CONTROL_AE_MODE_OFF: return "CONTROL_AE_MODE_OFF";
            case CameraMetadata.CONTROL_AE_MODE_ON: return "CONTROL_AE_MODE_ON";
            case CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH: return "CONTROL_AE_MODE_ON_AUTO_FLASH";
            case CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH: return "CONTROL_AE_MODE_ON_ALWAYS_FLASH";
            case CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE: return "CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE";
            case CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH: return "CONTROL_AE_MODE_ON_EXTERNAL_FLASH";
            default: return null;
        }
    }

    /** @return the upstream lowercase capability token, or null when unknown. */
    public static String capabilityName(int capability) {
        switch (capability) {
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE: return "backward_compatible";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE: return "burst_capture";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO:
                return "constrained_high_speed_video";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT: return "depth_output";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA: return "logical_multi_camera";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING: return "manual_post_processing";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR: return "manual_sensor";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME: return "monochrome";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING: return "motion_tracking";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING: return "private_reprocessing";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW: return "raw";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS: return "read_sensor_settings";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING: return "yuv_reprocessing";
            default: return null;
        }
    }
}
