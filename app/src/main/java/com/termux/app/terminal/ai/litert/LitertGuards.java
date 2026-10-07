package com.termux.app.terminal.ai.litert;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.function.Predicate;

/** What the device must offer before a backend is even tried: SDK level, ABI and, for the NPU, the dispatch library. */
public final class LitertGuards {
    /** litertlm-android 0.18.0 declares minSdk 24. */
    public static final int MIN_SDK = 24;
    /** The NPU path needs Android 12. */
    public static final int NPU_MIN_SDK = 31;
    public static final String ABI = "arm64-v8a";
    public static final String NPU_DISPATCH_LIBRARY = "libLiteRtDispatch_GoogleTensor.so";

    private final int sdkInt;
    private final List<String> supportedAbis;
    private final Predicate<String> nativeLibraryPresent;

    /** @param nativeLibraryPresent whether a library file exists in the app's native library directory */
    public LitertGuards(int sdkInt, List<String> supportedAbis, Predicate<String> nativeLibraryPresent) {
        this.sdkInt = sdkInt;
        this.supportedAbis = supportedAbis;
        this.nativeLibraryPresent = nativeLibraryPresent;
    }

    /** Throws when the backend cannot be tried on this device. */
    public void require(LitertBackend backend) throws LitertFailure {
        if (sdkInt < MIN_SDK) {
            throw failure(LitertErrorCode.RUNTIME_TOO_OLD, backend, "the LiteRT-LM runtime needs Android API " + MIN_SDK + " (this is " + sdkInt + ")");
        }
        if (supportedAbis == null || !supportedAbis.contains(ABI)) {
            throw failure(LitertErrorCode.ABI_UNSUPPORTED, backend, "the LiteRT-LM runtime is built for " + ABI + " only (device ABIs: " + supportedAbis + ")");
        }
        if (backend == LitertBackend.NPU) {
            if (sdkInt < NPU_MIN_SDK) {
                throw failure(LitertErrorCode.RUNTIME_TOO_OLD, backend, "the NPU backend needs Android API " + NPU_MIN_SDK + " (this is " + sdkInt + ")");
            }
            if (!nativeLibraryPresent.test(NPU_DISPATCH_LIBRARY)) {
                throw failure(LitertErrorCode.NATIVE_LIBRARY_UNAVAILABLE, backend, NPU_DISPATCH_LIBRARY + " is not in the app's native library directory");
            }
        }
    }

    /** For {@code litert.info}: whether the backend can be tried here, and why not. */
    public JSONObject availability(LitertBackend backend) throws JSONException {
        try {
            require(backend);
            return new JSONObject().put("available", true);
        } catch (LitertFailure failure) {
            return new JSONObject().put("available", false).put("error_name", failure.code.name()).put("reason", failure.getMessage());
        }
    }

    private static LitertFailure failure(LitertErrorCode code, LitertBackend backend, String message) {
        return new LitertFailure(code, backend.wire, "guard", null, message, null);
    }
}
