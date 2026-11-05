package android.hardware.camera2;

// Signature stub.
public abstract class CameraManager {
    public abstract String[] getCameraIdList() throws Exception;
    public abstract CameraCharacteristics getCameraCharacteristics(String cameraId) throws Exception;
    public abstract void setTorchMode(String cameraId, boolean enabled) throws Exception;
}
