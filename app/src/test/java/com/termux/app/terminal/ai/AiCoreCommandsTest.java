package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class AiCoreCommandsTest {

    private final FakeGenAiPort port = new FakeGenAiPort();
    private final AiCoreCommands commands =
        new AiCoreCommands(new AiCoreEngine(port, () -> 34, System::currentTimeMillis));

    private JSONObject generate(String json) throws Exception {
        return commands.handle("aicore.generate", new JSONObject(json));
    }

    private void rejects(String cmd, String json, String messagePart) throws Exception {
        int before = port.calls.size();
        try {
            commands.handle(cmd, new JSONObject(json));
            fail("expected a rejection of " + json);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage() + " should mention " + messagePart, e.getMessage().contains(messagePart));
        }
        assertEquals("a rejected request never touches the model service", before, port.calls.size());
    }

    // ------------------------------------------------------------ parameters reach the model
    @Test
    public void temperatureMaxTokensAndTopKReachTheModelClient() throws Exception {
        generate("{\"prompt\":\"hi\",\"max_tokens\":64,\"temperature\":0,\"top_k\":40}");
        assertEquals(1, port.generateParams.size());
        GenParams p = port.generateParams.get(0);
        assertEquals("hi", p.prompt);
        assertEquals(64, p.maxTokens);
        assertEquals(0.0f, p.temperature, 0.0f);
        assertEquals(Integer.valueOf(40), p.topK);
    }

    @Test
    public void defaultsApplyOnlyWhenTheArgumentsAreAbsent() throws Exception {
        generate("{\"prompt\":\"hi\"}");
        GenParams p = port.generateParams.get(0);
        assertEquals(GenParams.DEFAULT_MAX_TOKENS, p.maxTokens);
        assertEquals(GenParams.DEFAULT_TEMPERATURE, p.temperature, 0.0f);
        assertEquals(null, p.topK);
    }

    @Test
    public void theAnswerEchoesWhatWasAppliedAndTheRealFinishReason() throws Exception {
        port.generateResult = new GenResult("cut off", "max_tokens");
        JSONObject out = generate("{\"prompt\":\"hi\",\"max_tokens\":8,\"temperature\":0.5,\"top_k\":3,\"stage\":\"preview\",\"preference\":\"fast\"}");
        assertEquals("cut off", out.getString("text"));
        assertEquals("max_tokens", out.getString("finish_reason"));
        assertEquals(8, out.getInt("max_tokens"));
        assertEquals(0.5, out.getDouble("temperature"), 1e-6);
        assertEquals(3, out.getInt("top_k"));
        assertEquals("preview", out.getString("stage"));
        assertEquals("fast", out.getString("preference"));
        assertTrue(out.getLong("latency_ms") >= 0);
    }

    // ------------------------------------------------------------ invalid values are rejected
    @Test
    public void invalidMaxTokensAreRejected() throws Exception {
        for (String bad : new String[] {"0", "-1", "4097", "2.5", "\"64\"", "null", "true"}) {
            rejects("aicore.generate", "{\"prompt\":\"hi\",\"max_tokens\":" + bad + "}", "max_tokens");
        }
    }

    @Test
    public void invalidTemperaturesAreRejected() throws Exception {
        for (String bad : new String[] {"-0.1", "1.01", "2.5", "\"hot\"", "null", "false"}) {
            rejects("aicore.generate", "{\"prompt\":\"hi\",\"temperature\":" + bad + "}", "temperature");
        }
    }

    @Test
    public void invalidTopKAreRejected() throws Exception {
        for (String bad : new String[] {"0", "-3", "1.5", "\"x\"", "null", "3000000000"}) {
            rejects("aicore.generate", "{\"prompt\":\"hi\",\"top_k\":" + bad + "}", "top_k");
        }
    }

    @Test
    public void aMissingOrBlankOrNonTextPromptIsRejected() throws Exception {
        rejects("aicore.generate", "{}", "prompt");
        rejects("aicore.generate", "{\"prompt\":\"   \"}", "prompt");
        rejects("aicore.generate", "{\"prompt\":42}", "prompt");
        rejects("aicore.generate", "{\"prompt\":null}", "prompt");
    }

    @Test
    public void theLimitsOfTheRangesAreAccepted() throws Exception {
        generate("{\"prompt\":\"a\",\"max_tokens\":1,\"temperature\":0.0,\"top_k\":1}");
        generate("{\"prompt\":\"a\",\"max_tokens\":4096,\"temperature\":1.0,\"top_k\":100000}");
        assertEquals(2, port.generateParams.size());
    }

    // ------------------------------------------------------------ selection
    @Test
    public void stageAndPreferenceSelectTheClientAndUnknownValuesAreErrors() throws Exception {
        generate("{\"prompt\":\"hi\",\"stage\":\"preview\",\"preference\":\"fast\"}");
        assertEquals(new ModelSelection(ModelSelection.Stage.PREVIEW, ModelSelection.Preference.FAST), port.generateSelections.get(0));
        generate("{\"prompt\":\"hi\"}");
        assertEquals(ModelSelection.DEFAULT, port.generateSelections.get(1));
        for (String bad : new String[] {"\"nightly\"", "\"Stable\"", "\"\"", "1", "true"}) {
            rejects("aicore.generate", "{\"prompt\":\"hi\",\"stage\":" + bad + "}", "stage");
        }
        for (String bad : new String[] {"\"medium\"", "\"FAST\"", "\"\"", "0"}) {
            rejects("aicore.generate", "{\"prompt\":\"hi\",\"preference\":" + bad + "}", "preference");
        }
        rejects("aicore.info", "{\"stage\":\"nightly\"}", "stage");
        rejects("aicore.download", "{\"preference\":\"slow\"}", "preference");
    }

    @Test
    public void everyArgumentIsCheckedBeforeAnythingTouchesTheService() throws Exception {
        // a valid prompt with a bad selection: not even the status is asked
        rejects("aicore.generate", "{\"prompt\":\"hi\",\"stage\":\"nightly\"}", "stage");
        assertTrue(port.calls.isEmpty());
    }

    // ------------------------------------------------------------ errors
    @Test
    public void aicoreFailuresKeepTheirNameCodeAndRetryDelay() throws Exception {
        port.generateFailure = new AiCoreFailure("BUSY", 9, 2500, "service busy", null);
        try {
            generate("{\"prompt\":\"hi\"}");
            fail("expected the failure");
        } catch (AiCoreFailure f) {
            JSONObject json = new JSONObject(AiCoreCommands.errorJson(f));
            assertFalse(json.getBoolean("ok"));
            assertEquals("BUSY", json.getString("error_name"));
            assertEquals(9, json.getInt("error_code"));
            assertEquals(2500, json.getLong("retry_delay_ms"));
            assertEquals("service busy", json.getString("error"));
        }
    }

    @Test
    public void aFailureWithoutRetryDelayOmitsTheField() throws Exception {
        JSONObject json = new JSONObject(AiCoreCommands.errorJson(new AiCoreFailure("NEEDS_SYSTEM_UPDATE", 604, -1, "old", null)));
        assertFalse(json.has("retry_delay_ms"));
        assertEquals(604, json.getInt("error_code"));
    }

    @Test
    public void anUnknownCommandIsRejected() throws Exception {
        rejects("aicore.nonsense", "{}", "Unknown command");
        assertNotNull(AiCoreCommands.handles("aicore.info"));
        assertFalse(AiCoreCommands.handles("ping"));
    }
}
