package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.Candidate;
import com.google.mlkit.genai.prompt.GenerateContentRequest;
import com.google.mlkit.genai.prompt.GenerationConfig;
import com.google.mlkit.genai.prompt.ModelPreference;
import com.google.mlkit.genai.prompt.ModelReleaseStage;

import org.junit.Test;

import java.time.Duration;

/** The Kotlin adapter against the real SDK classes: constants, request and error mapping. */
public class MlKitGenAiPortTest {

    // ------------------------------------------------------------ SDK constants, never numbers by hand
    @Test
    public void stageAndPreferenceUseTheSdkConstants() {
        assertEquals(ModelReleaseStage.STABLE, MlKitGenAiPort.releaseStageOf(ModelSelection.Stage.STABLE));
        assertEquals(ModelReleaseStage.PREVIEW, MlKitGenAiPort.releaseStageOf(ModelSelection.Stage.PREVIEW));
        assertEquals(ModelPreference.FULL, MlKitGenAiPort.preferenceOf(ModelSelection.Preference.FULL));
        assertEquals(ModelPreference.FAST, MlKitGenAiPort.preferenceOf(ModelSelection.Preference.FAST));
    }

    @Test
    public void fullAndFastAreDistinctAndTheOldHandWrittenNumbersAreNotAssumed() {
        // The server used to map full to 0 and fast to 1 by hand; the SDK says FAST=1, FULL=2.
        assertNotEquals(MlKitGenAiPort.preferenceOf(ModelSelection.Preference.FULL),
            MlKitGenAiPort.preferenceOf(ModelSelection.Preference.FAST));
        assertNotEquals("FULL is not 0", 0, MlKitGenAiPort.preferenceOf(ModelSelection.Preference.FULL));
        assertNotEquals(MlKitGenAiPort.releaseStageOf(ModelSelection.Stage.STABLE),
            MlKitGenAiPort.releaseStageOf(ModelSelection.Stage.PREVIEW));
    }

    @Test
    public void everySelectionBuildsItsOwnModelConfig() {
        for (ModelSelection s : ModelSelection.all()) {
            GenerationConfig config = MlKitGenAiPort.configFor(s);
            assertEquals(s.toString(), MlKitGenAiPort.releaseStageOf(s.stage), config.getModelConfig().getReleaseStage());
            assertEquals(s.toString(), MlKitGenAiPort.preferenceOf(s.preference), config.getModelConfig().getPreference());
        }
    }

    // ------------------------------------------------------------ the request carries the parameters
    @Test
    public void theRequestCarriesPromptTemperatureMaxTokensAndTopK() {
        GenerateContentRequest request = MlKitGenAiPort.requestFor(new GenParams("hello", 64, 0.0f, 40));
        assertEquals("hello", request.getText().getTextString());
        assertEquals(0.0f, request.getTemperature(), 0.0f);
        assertEquals(64, request.getMaxOutputTokens());
        assertEquals(40, request.getTopK());
    }

    @Test
    public void differentValuesGiveADifferentRequest() {
        GenerateContentRequest a = MlKitGenAiPort.requestFor(new GenParams("p", 8, 0.1f, 3));
        GenerateContentRequest b = MlKitGenAiPort.requestFor(new GenParams("p", 512, 0.9f, 9));
        assertEquals(8, a.getMaxOutputTokens());
        assertEquals(512, b.getMaxOutputTokens());
        assertEquals(0.1f, a.getTemperature(), 0.0f);
        assertEquals(0.9f, b.getTemperature(), 0.0f);
        assertEquals(3, a.getTopK());
        assertEquals(9, b.getTopK());
    }

    @Test
    public void withoutTopKTheSdkDefaultApplies() {
        GenerateContentRequest withK = MlKitGenAiPort.requestFor(new GenParams("p", 8, 0.1f, 5));
        GenerateContentRequest without = MlKitGenAiPort.requestFor(new GenParams("p", 8, 0.1f, null));
        assertNotEquals(withK.getTopK(), without.getTopK());
    }

    // ------------------------------------------------------------ finish reasons
    @Test
    public void finishReasonsComeFromTheSdkNotFromAConstant() {
        assertEquals("stop", MlKitGenAiPort.finishName(Candidate.FinishReason.STOP));
        assertEquals("max_tokens", MlKitGenAiPort.finishName(Candidate.FinishReason.MAX_TOKENS));
        assertEquals("other", MlKitGenAiPort.finishName(Candidate.FinishReason.OTHER));
        assertEquals("other", MlKitGenAiPort.finishName(null));
    }

    // ------------------------------------------------------------ error mapping
    @Test
    public void theDocumentedErrorCodesKeepTheirNamesAndNumbers() {
        assertEquals("BUSY", GenAiErrors.name(9));
        assertEquals("BACKGROUND_USE_BLOCKED", GenAiErrors.name(30));
        assertEquals("PER_APP_BATTERY_USE_QUOTA_EXCEEDED", GenAiErrors.name(27));
        assertEquals("NEEDS_SYSTEM_UPDATE", GenAiErrors.name(604));
        assertEquals("ERROR_12345", GenAiErrors.name(12345));
        // and the SDK constants still carry those numbers
        assertEquals(9, GenAiException.ErrorCode.BUSY);
        assertEquals(30, GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED);
        assertEquals(27, GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED);
        assertEquals(604, GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE);
    }

    @Test
    public void aGenAiExceptionBecomesAnAiCoreFailureWithTheRetryDelay() {
        GenAiException busy = new GenAiException("quota", null, GenAiException.ErrorCode.BUSY, Duration.ofSeconds(3));
        Throwable mapped = GenAiErrors.map(busy);
        assertTrue(mapped instanceof AiCoreFailure);
        AiCoreFailure f = (AiCoreFailure) mapped;
        assertEquals("BUSY", f.name);
        assertEquals(9, f.code);
        assertEquals(3000, f.retryDelayMs);
        assertEquals("quota", f.getMessage());
        assertSame(busy, f.getCause());
    }

    @Test
    public void aGenAiExceptionWithoutDelayGivesMinusOne() {
        GenAiException blocked = new GenAiException("bg", null, GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED);
        AiCoreFailure f = (AiCoreFailure) GenAiErrors.map(blocked);
        assertEquals("BACKGROUND_USE_BLOCKED", f.name);
        assertEquals(30, f.code);
        assertEquals(-1, f.retryDelayMs);
    }

    @Test
    public void otherThrowablesPassThroughUnchanged() {
        IllegalStateException other = new IllegalStateException("x");
        assertSame(other, GenAiErrors.map(other));
        assertNull(other.getCause());
    }
}
