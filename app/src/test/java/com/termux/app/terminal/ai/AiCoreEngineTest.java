package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AiCoreEngineTest {

    private final FakeGenAiPort port = new FakeGenAiPort();
    private AiCoreEngine engine(int sdk) { return new AiCoreEngine(port, () -> sdk, System::currentTimeMillis); }
    private final AiCoreEngine engine = engine(34);

    private static ModelSelection sel(ModelSelection.Stage s, ModelSelection.Preference p) { return new ModelSelection(s, p); }

    // ------------------------------------------------------------ models
    @Test
    public void modelsProbesTheFourCombinationsWithStatusAndBaseModelName() throws Exception {
        JSONObject out = engine.models();        JSONArray models = out.getJSONArray("models");
        assertEquals(4, models.length());
        assertEquals(4, port.statusSelections.size());
        assertEquals(4, port.calls.stream().filter(c -> c.startsWith("baseModelName")).count());
        String[] expected = {"stable/full", "stable/fast", "preview/full", "preview/fast"};
        for (int i = 0; i < 4; i++) {
            JSONObject m = models.getJSONObject(i);
            assertEquals(expected[i], m.getString("stage") + "/" + m.getString("preference"));
            assertEquals("AVAILABLE", m.getString("status"));
            assertTrue(m.getBoolean("available"));
            assertEquals("model-" + expected[i], m.getString("base_model_name"));
        }
        assertTrue(out.getString("note").contains("checkStatus"));
    }

    @Test
    public void anUnavailableCombinationIsReportedAndItsNameIsNotAsked() throws Exception {
        port.status.put(sel(ModelSelection.Stage.PREVIEW, ModelSelection.Preference.FAST), AiCoreEngine.STATUS_UNAVAILABLE);
        port.status.put(sel(ModelSelection.Stage.PREVIEW, ModelSelection.Preference.FULL), AiCoreEngine.STATUS_DOWNLOADABLE);
        JSONArray models = engine.models().getJSONArray("models");
        JSONObject previewFast = models.getJSONObject(3);
        assertEquals("UNAVAILABLE", previewFast.getString("status"));
        assertFalse(previewFast.getBoolean("available"));
        assertFalse(previewFast.has("base_model_name"));
        JSONObject previewFull = models.getJSONObject(2);
        assertEquals("DOWNLOADABLE", previewFull.getString("status"));
        assertTrue(previewFull.has("base_model_name"));
        assertFalse(port.calls.contains("baseModelName preview/fast"));
    }

    @Test
    public void aFailingNameLookupOnlyAffectsItsOwnEntry() throws Exception {
        port.nameFailure = new IllegalStateException("no name");
        JSONArray models = engine.models().getJSONArray("models");
        assertEquals(4, models.length());
        assertTrue(models.getJSONObject(0).getString("base_model_name_error").contains("no name"));
        assertTrue(models.getJSONObject(0).getBoolean("available"));
    }

    // ------------------------------------------------------------ info
    @Test
    public void infoReportsRequestedAndEffectiveSelectionAndTheSdkCapabilities() throws Exception {
        ModelSelection s = sel(ModelSelection.Stage.PREVIEW, ModelSelection.Preference.FULL);
        port.names.put(s, "gemini-nano-4-full");
        JSONObject info = engine.info(s);
        assertTrue(info.getBoolean("available"));
        assertEquals("preview", info.getJSONObject("requested").getString("stage"));
        assertEquals("full", info.getJSONObject("requested").getString("preference"));
        assertEquals("gemini-nano-4-full", info.getJSONObject("effective").getString("base_model_name"));
        JSONObject caps = info.getJSONObject("capabilities");
        assertEquals(4096, caps.getInt("token_limit"));
        assertTrue(caps.getBoolean("system_prompt"));
        assertFalse(caps.getBoolean("structured_output"));
        assertTrue(caps.isNull("thinking"));
        assertEquals("1.0.0-beta4", info.getString("sdk_version"));
        assertEquals("only the requested client was asked", 1, port.statusSelections.size());
        assertEquals(s, port.statusSelections.get(0));
    }

    @Test
    public void infoWhenTheModelIsNotAvailableSaysWhyAndSkipsTheCapabilities() throws Exception {
        port.defaultStatus = AiCoreEngine.STATUS_DOWNLOADABLE;
        JSONObject info = engine.info(ModelSelection.DEFAULT);
        assertFalse(info.getBoolean("available"));
        assertEquals("DOWNLOADABLE", info.getString("status"));
        assertTrue(info.getString("error").contains("downloadable"));
    }

    @Test
    public void infoBelowAndroid12IsUnavailableAndAsksNothing() throws Exception {
        JSONObject info = engine(30).info(ModelSelection.DEFAULT);
        assertFalse(info.getBoolean("available"));
        assertTrue(info.getString("error").contains("Android < 12"));
        assertTrue(port.calls.isEmpty());
    }

    @Test
    public void theSdkVersionInTheAnswerMatchesTheGradleDependency() throws Exception {
        // Gradle runs unit tests from the module directory; a manual run may start at the root.
        for (String candidate : new String[] {"build.gradle", "app/build.gradle"}) {
            File gradle = new File(candidate);
            if (!gradle.isFile()) continue;
            String text = new String(Files.readAllBytes(gradle.toPath()), StandardCharsets.UTF_8);
            if (!text.contains("com.google.mlkit:genai-prompt")) continue;
            assertTrue("build.gradle must depend on genai-prompt:" + AiCoreEngine.SDK_VERSION,
                text.contains("com.google.mlkit:genai-prompt:" + AiCoreEngine.SDK_VERSION));
            return;
        }
        fail("no build.gradle with the genai-prompt dependency was found from " + new File(".").getAbsolutePath());
    }

    // ------------------------------------------------------------ one client per selection
    @Test
    public void statusDownloadAndGenerateOfASelectionUseThatSameSelection() throws Exception {
        ModelSelection s = sel(ModelSelection.Stage.PREVIEW, ModelSelection.Preference.FAST);
        engine.download(s);
        engine.generate(new GenParams("hi", 8, 0.1f, null), s);
        assertEquals(s, port.downloadSelections.get(0));
        assertEquals(s, port.generateSelections.get(0));
        for (ModelSelection seen : port.statusSelections) assertEquals(s, seen);
    }

    @Test
    public void anAsyncDownloadAnswersAtOnceAndTheStatusSaysWhenItIsOver() throws Exception {
        ModelSelection s = ModelSelection.DEFAULT;
        JSONObject out = engine.downloadAsync(s);
        assertTrue(out.getBoolean("started"));
        assertFalse(out.optBoolean("already_running", false));
        // the download runs in the background: wait for it to be reported over
        long deadline = System.currentTimeMillis() + 5_000;
        while (engine.downloadStatus(s).getBoolean("downloading")) {
            assertTrue("the background download never finished", System.currentTimeMillis() < deadline);
            Thread.sleep(10);
        }
        assertTrue(port.calls.stream().anyMatch(c -> c.startsWith("download ")));
        JSONObject status = engine.downloadStatus(s);
        assertFalse(status.getBoolean("downloading"));
    }

    @Test
    public void theStatusOnlyDownloadStartsNothing() throws Exception {
        engine.downloadStatus(ModelSelection.DEFAULT);
        assertTrue(port.calls.stream().noneMatch(c -> c.startsWith("download ")));
    }

    @Test
    public void twoSelectionsDoNotContaminateEachOther() throws Exception {
        ModelSelection a = sel(ModelSelection.Stage.STABLE, ModelSelection.Preference.FULL);
        ModelSelection b = sel(ModelSelection.Stage.PREVIEW, ModelSelection.Preference.FAST);
        port.status.put(a, AiCoreEngine.STATUS_AVAILABLE);
        port.status.put(b, AiCoreEngine.STATUS_DOWNLOADABLE);
        engine.generate(new GenParams("x", 8, 0.1f, null), a);
        try {
            engine.generate(new GenParams("y", 8, 0.1f, null), b);
            fail("b is not downloaded");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("DOWNLOADABLE"));
        }
        engine.generate(new GenParams("z", 8, 0.1f, null), a);
        assertEquals(2, port.generateSelections.size());
        assertEquals(a, port.generateSelections.get(0));
        assertEquals(a, port.generateSelections.get(1));
    }

    // ------------------------------------------------------------ generate / download / sdk
    @Test
    public void generateWithAnUnavailableModelFailsBeforeAskingTheModel() {
        port.defaultStatus = AiCoreEngine.STATUS_DOWNLOADING;
        try {
            engine.generate(new GenParams("hi", 8, 0.1f, null), ModelSelection.DEFAULT);
            fail("expected a refusal");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("DOWNLOADING"));
        }
        assertEquals(0, port.modelCalls());
    }

    @Test
    public void belowAndroid12NothingReachesTheService() {
        AiCoreEngine old = engine(30);
        try { old.generate(new GenParams("hi", 8, 0.1f, null), ModelSelection.DEFAULT); fail(); } catch (Exception e) { assertTrue(e.getMessage().contains("Android 12")); }
        try { old.download(ModelSelection.DEFAULT); fail(); } catch (Exception e) { assertTrue(e.getMessage().contains("Android 12")); }
        assertTrue(port.calls.isEmpty());
    }

    @Test
    public void aFailedDownloadIsAnErrorNotAnInnerOkFalse() {
        port.downloadFailure = new AiCoreFailure("NOT_ENOUGH_DISK_SPACE", 501, -1, "no space", null);
        try {
            engine.download(ModelSelection.DEFAULT);
            fail("expected the failure to propagate");
        } catch (AiCoreFailure f) {
            assertEquals(501, f.code);
        } catch (Exception e) {
            fail("wrong exception " + e);
        }
    }

    @Test
    public void aSuccessfulDownloadReportsTheStatusAfterwards() throws Exception {
        JSONObject out = engine.download(ModelSelection.DEFAULT);
        assertTrue(out.getBoolean("available"));
        assertEquals("AVAILABLE", out.getString("status"));
    }

    // ------------------------------------------------------------ stream
    @Test
    public void streamPassesTheParametersAndChunksAndCancelInterrupts() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder chunks = new StringBuilder();
        AtomicReference<String> finish = new AtomicReference<>();
        engine.stream("req-1", new GenParams("hi", 12, 0.3f, 7), ModelSelection.DEFAULT, new AICoreStreamCallback() {
            @Override public void onChunk(String text) { chunks.append(text); }
            @Override public void onComplete(String finishReason, int outputCharsApprox) { finish.set(finishReason); done.countDown(); }
            @Override public void onError(Throwable error) { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("ab", chunks.toString());
        assertEquals("stop", finish.get());
        assertEquals(12, port.streamParams.get(0).maxTokens);
        assertEquals(Integer.valueOf(7), port.streamParams.get(0).topK);
        assertFalse("an unknown request cannot be cancelled", engine.cancel("nope"));
    }
}
