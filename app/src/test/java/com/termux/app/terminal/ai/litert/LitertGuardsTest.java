package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class LitertGuardsTest {
    private static final java.util.List<String> ARM_ONLY = Collections.singletonList("arm64-v8a");

    private static LitertErrorCode codeOf(LitertGuards guards, LitertBackend backend) {
        try {
            guards.require(backend);
            return null;
        } catch (LitertFailure f) {
            assertEquals("guard", f.phase);
            assertEquals(backend.wire, f.backendRequested);
            return f.code;
        }
    }

    @Test public void anOldAndroidIsRefusedForEveryBackend() {
        LitertGuards guards = new LitertGuards(23, ARM_ONLY, name -> true);
        for (LitertBackend b : LitertBackend.values()) assertEquals(LitertErrorCode.RUNTIME_TOO_OLD, codeOf(guards, b));
        assertEquals(null, codeOf(new LitertGuards(24, ARM_ONLY, name -> true), LitertBackend.CPU));
    }

    @Test public void onlyArm64IsSupported() {
        assertEquals(LitertErrorCode.ABI_UNSUPPORTED, codeOf(new LitertGuards(34, Arrays.asList("x86_64", "x86"), name -> true), LitertBackend.CPU));
        assertEquals(LitertErrorCode.ABI_UNSUPPORTED, codeOf(new LitertGuards(34, Collections.<String>emptyList(), name -> true), LitertBackend.GPU));
        assertEquals(LitertErrorCode.ABI_UNSUPPORTED, codeOf(new LitertGuards(34, null, name -> true), LitertBackend.CPU));
        assertEquals(null, codeOf(new LitertGuards(34, Arrays.asList("arm64-v8a", "armeabi-v7a"), name -> true), LitertBackend.GPU));
    }

    @Test public void theNpuNeedsAndroid12AndTheDispatchLibrary() {
        assertEquals(LitertErrorCode.RUNTIME_TOO_OLD, codeOf(new LitertGuards(30, ARM_ONLY, name -> true), LitertBackend.NPU));
        assertEquals(LitertErrorCode.NATIVE_LIBRARY_UNAVAILABLE, codeOf(new LitertGuards(34, ARM_ONLY, name -> false), LitertBackend.NPU));
        assertEquals(null, codeOf(new LitertGuards(31, ARM_ONLY, name -> name.equals(LitertGuards.NPU_DISPATCH_LIBRARY)), LitertBackend.NPU));
        // The CPU and the GPU never look for the NPU library.
        assertEquals(null, codeOf(new LitertGuards(34, ARM_ONLY, name -> false), LitertBackend.CPU));
        assertEquals(null, codeOf(new LitertGuards(34, ARM_ONLY, name -> false), LitertBackend.GPU));
    }

    @Test public void availabilityExplainsWhyNot() throws Exception {
        LitertGuards guards = new LitertGuards(34, ARM_ONLY, name -> false);
        assertTrue(guards.availability(LitertBackend.CPU).getBoolean("available"));
        org.json.JSONObject npu = guards.availability(LitertBackend.NPU);
        assertFalse(npu.getBoolean("available"));
        assertEquals("NATIVE_LIBRARY_UNAVAILABLE", npu.getString("error_name"));
        assertTrue(npu.getString("reason").contains(LitertGuards.NPU_DISPATCH_LIBRARY));
    }
}
