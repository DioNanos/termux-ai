package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class LitertParamsTest {
    private static JSONObject base() throws Exception {
        return new JSONObject().put("request_id", "r-1").put("model", "qwen3-0.6b").put("backend", "cpu").put("prompt", "hi");
    }

    private static void rejects(JSONObject args, String fragment) {
        try {
            LitertParams.fromArgs(args);
            fail("expected a rejection mentioning: " + fragment);
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.INVALID_ARGUMENT, f.code);
            assertTrue(f.getMessage(), f.getMessage().contains(fragment));
        }
    }

    @Test public void minimalRequestGetsTheContractDefaults() throws Exception {
        LitertParams p = LitertParams.fromArgs(base());
        assertEquals("r-1", p.requestId);
        assertEquals(LitertBackend.CPU, p.backend);
        assertEquals(256, p.maxTokens);
        assertEquals(0.2, p.temperature, 0);
        assertEquals(40, p.topK);
    }

    @Test public void backendIsRequiredAndStrict() throws Exception {
        JSONObject noBackend = base(); noBackend.remove("backend");
        rejects(noBackend, "backend is required");
        rejects(base().put("backend", "CPU"), "cpu, gpu or npu");
        rejects(base().put("backend", "tpu"), "cpu, gpu or npu");
        rejects(base().put("backend", ""), "cpu, gpu or npu");
        rejects(base().put("backend", 3), "backend must be a string");
        rejects(base().put("backend", JSONObject.NULL), "backend is required");
        for (String ok : new String[] {"cpu", "gpu", "npu"}) {
            assertEquals(ok, LitertParams.fromArgs(base().put("backend", ok)).backend.wire);
        }
    }

    @Test public void aicoreSelectorsAreNotAcceptedHere() throws Exception {
        rejects(base().put("stage", "stable"), "backend");
        rejects(base().put("preference", "fast"), "backend");
    }

    @Test public void requestIdAndModelAreRequiredAndShaped() throws Exception {
        JSONObject noId = base(); noId.remove("request_id");
        rejects(noId, "request_id is required");
        rejects(base().put("request_id", "a b"), "request_id must be");
        rejects(base().put("request_id", new String(new char[65]).replace('\0', 'a')), "request_id must be");
        JSONObject noModel = base(); noModel.remove("model");
        rejects(noModel, "model is required");
        rejects(base().put("prompt", "   "), "prompt is required");
        JSONObject noPrompt = base(); noPrompt.remove("prompt");
        rejects(noPrompt, "prompt is required");
    }

    @Test public void aPromptThatCannotCrossTheWorkerBoundaryIsRejected() throws Exception {
        String big = new String(new char[LitertParams.MAX_PROMPT_BYTES + 1]).replace('\0', 'a');
        rejects(base().put("prompt", big), "prompt is larger");
        // Bytes count, not characters: a multibyte character takes more than one.
        String multibyte = new String(new char[LitertParams.MAX_PROMPT_BYTES / 2 + 1]).replace('\0', '\u00e8');
        rejects(base().put("prompt", multibyte), "prompt is larger");
        String fits = new String(new char[LitertParams.MAX_PROMPT_BYTES]).replace('\0', 'a');
        assertEquals(LitertParams.MAX_PROMPT_BYTES, LitertParams.fromArgs(base().put("prompt", fits)).prompt.length());
    }

    @Test public void numbersAreRejectedNotClamped() throws Exception {
        rejects(base().put("max_tokens", 0), "max_tokens");
        rejects(base().put("max_tokens", 4097), "max_tokens");
        rejects(base().put("max_tokens", 1.5), "max_tokens");
        rejects(base().put("max_tokens", "9"), "max_tokens");
        rejects(base().put("temperature", -0.1), "temperature");
        rejects(base().put("temperature", 1.1), "temperature");
        rejects(base().put("temperature", "0.2"), "temperature");
        rejects(base().put("top_k", 0), "top_k");
        rejects(base().put("top_k", 1001), "top_k");
        LitertParams edge = LitertParams.fromArgs(base().put("max_tokens", 4096).put("temperature", 0).put("top_k", 1));
        assertEquals(4096, edge.maxTokens);
        assertEquals(0.0, edge.temperature, 0);
        assertEquals(1, edge.topK);
    }
}
