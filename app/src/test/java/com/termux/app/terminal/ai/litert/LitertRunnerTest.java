package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class LitertRunnerTest {
    private final FakeRuntime runtime = new FakeRuntime();
    private final AtomicLong clock = new AtomicLong(1000);
    private final LitertRunner runner = new LitertRunner(runtime, () -> clock.addAndGet(5));

    private static String request(String id, String backend, String model) throws Exception {
        return new JSONObject().put("op", "generate").put("request_id", id).put("model", model)
            .put("model_path", "/models/" + model + ".litertlm").put("backend", backend).put("context_tokens", 4096)
            .put("prompt", "hi").put("max_tokens", 64).put("temperature", 0.5).put("top_k", 20).put("top_p", 1.0).put("seed", 0).toString();
    }

    private static JSONObject json(String s) throws Exception { return new JSONObject(s); }

    @Test public void aCpuAnswerIsVerifiedAsCpu() throws Exception {
        JSONObject r = json(runner.handle(request("a", "cpu", "m")));
        assertTrue(r.getBoolean("ok"));
        JSONObject data = r.getJSONObject("data");
        assertEquals("answer", data.getString("text"));
        assertEquals("cpu", data.getString("backend_requested"));
        assertEquals("cpu", data.getString("backend_effective"));
        assertTrue(data.getBoolean("backend_verified"));
        assertFalse(data.getBoolean("engine_reused"));
        assertEquals("generate hi 64 0.5 20 1.0 0", runtime.log.get(1));
    }

    @Test public void gpuAndNpuNeverClaimAnExecutor() throws Exception {
        for (String backend : new String[] {"gpu", "npu"}) {
            JSONObject data = json(runner.handle(request("a-" + backend, backend, backend))).getJSONObject("data");
            assertEquals(backend, data.getString("backend_requested"));
            assertTrue(data.isNull("backend_effective"));
            assertFalse(data.getBoolean("backend_verified"));
            assertEquals(LitertRunner.EVIDENCE_NONE, data.getString("backend_evidence"));
        }
    }

    @Test public void evidenceFromTheRuntimeIsReportedButNeverMakesItVerified() throws Exception {
        runtime.evidence = "libLiteRtDispatch_GoogleTensor.so is mapped";
        JSONObject data = json(runner.handle(request("a", "npu", "m"))).getJSONObject("data");
        assertEquals("libLiteRtDispatch_GoogleTensor.so is mapped", data.getString("backend_evidence"));
        assertFalse(data.getBoolean("backend_verified"));
        assertTrue(data.isNull("backend_effective"));
    }

    @Test public void aFailedNpuOpenIsReportedAsIsAndNeverRetriedOnTheCpu() throws Exception {
        runtime.loadFailure = new LitertFailure(LitertErrorCode.BACKEND_INIT_FAILED, "npu", "init", null, "dispatch rejected the model", null);
        JSONObject r = json(runner.handle(request("a", "npu", "m")));
        assertFalse(r.getBoolean("ok"));
        assertEquals("BACKEND_INIT_FAILED", r.getString("error_name"));
        assertEquals("dispatch rejected the model", r.getString("error"));
        assertEquals("npu", r.getString("backend_requested"));
        assertEquals(Arrays.asList("load npu /models/m.litertlm 4096"), runtime.log);
    }

    @Test public void aCrashWhileOpeningIsABackendFailureWithTheOriginalMessage() throws Exception {
        runtime.loadCrash = new IllegalStateException("libX.so not found");
        JSONObject r = json(runner.handle(request("a", "gpu", "m")));
        assertEquals("BACKEND_INIT_FAILED", r.getString("error_name"));
        assertTrue(r.getString("error").contains("libX.so not found"));
        assertEquals(1, runtime.log.size());
    }

    @Test public void theSameKeyReusesTheEngine() throws Exception {
        runner.handle(request("a", "cpu", "m"));
        JSONObject data = json(runner.handle(request("b", "cpu", "m"))).getJSONObject("data");
        assertTrue(data.getBoolean("engine_reused"));
        assertEquals(1, runtime.log.stream().filter(l -> l.startsWith("load")).count());
    }

    @Test public void aDifferentKeyClosesTheOldEngineBeforeOpeningTheNewOne() throws Exception {
        runner.handle(request("a", "cpu", "m"));
        runner.handle(request("b", "gpu", "m"));
        runner.handle(request("c", "gpu", "other"));
        assertEquals(Arrays.asList(
            "load cpu /models/m.litertlm 4096", "generate hi 64 0.5 20 1.0 0",
            "close", "load gpu /models/m.litertlm 4096", "generate hi 64 0.5 20 1.0 0",
            "close", "load gpu /models/other.litertlm 4096", "generate hi 64 0.5 20 1.0 0"), runtime.log);
    }

    @Test public void aFailedOpenLeavesNothingLoadedAndTheNextRequestStartsClean() throws Exception {
        runner.handle(request("a", "cpu", "m"));
        runtime.loadFailure = new LitertFailure(LitertErrorCode.BACKEND_INIT_FAILED, "gpu", "init", null, "no gpu", null);
        runner.handle(request("b", "gpu", "m"));
        runtime.loadFailure = null;
        JSONObject data = json(runner.handle(request("c", "cpu", "m"))).getJSONObject("data");
        assertFalse("the first engine was closed when the second open began", data.getBoolean("engine_reused"));
        assertEquals("loaded", json(runner.handle("{\"op\":\"status\"}")).getJSONObject("data").getString("state"));
    }

    @Test public void aSecondRequestWhileOneRunsIsBusyAndTheFirstIsUntouched() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        String[] first = new String[1];
        Thread t = new Thread(() -> { try { first[0] = runner.handle(request("one", "cpu", "m")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        // The second request runs on its own thread: if the runner let it through it would block on the
        // generation in progress, and this must show as a failed assertion, not as a hung test.
        String[] second = new String[1];
        Thread t2 = new Thread(() -> { try { second[0] = runner.handle(request("two", "cpu", "m")); } catch (Exception e) { throw new RuntimeException(e); } });
        t2.start();
        t2.join(2000);
        if (second[0] == null) runtime.release.countDown();
        assertTrue("the second request must be answered while the first one runs", second[0] != null);
        JSONObject busy = json(second[0]);
        assertEquals("BUSY", busy.getString("error_name"));
        assertTrue(busy.getString("error").contains("one"));
        assertEquals("busy", json(runner.handle("{\"op\":\"status\"}")).getJSONObject("data").getString("state"));
        runtime.release.countDown();
        t.join(5000);
        assertTrue(json(first[0]).getBoolean("ok"));
        assertEquals(1, runtime.log.stream().filter(l -> l.startsWith("generate")).count());
    }

    @Test public void cancelStopsTheActiveRequestOnly() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        String[] first = new String[1];
        Thread t = new Thread(() -> { try { first[0] = runner.handle(request("one", "cpu", "m")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        assertFalse(json(runner.handle("{\"op\":\"cancel\",\"request_id\":\"other\"}")).getJSONObject("data").getBoolean("cancelled"));
        assertFalse(runtime.cancelled);
        assertTrue(json(runner.handle("{\"op\":\"cancel\",\"request_id\":\"one\"}")).getJSONObject("data").getBoolean("cancelled"));
        t.join(5000);
        assertEquals("CANCELLED", json(first[0]).getString("error_name"));
        // Nothing runs now: a cancel finds nothing.
        assertFalse(json(runner.handle("{\"op\":\"cancel\",\"request_id\":\"one\"}")).getJSONObject("data").getBoolean("cancelled"));
    }

    @Test public void generationFailuresKeepTheirCodeAndAnUnexpectedCrashIsAGenerationFailure() throws Exception {
        runtime.generateFailure = new LitertFailure(LitertErrorCode.CONTEXT_EXCEEDED, "cpu", "generate", null, "prompt too long", null);
        assertEquals("CONTEXT_EXCEEDED", json(runner.handle(request("a", "cpu", "m"))).getString("error_name"));
        runtime.generateFailure = null;
        runtime.generateCrash = new IllegalStateException("native oops");
        JSONObject r = json(runner.handle(request("b", "cpu", "m")));
        assertEquals("GENERATION_FAILED", r.getString("error_name"));
        assertTrue(r.getString("error").contains("native oops"));
        // The request is over either way: the worker accepts the next one.
        runtime.generateCrash = null;
        assertTrue(json(runner.handle(request("c", "cpu", "m"))).getBoolean("ok"));
    }

    @Test public void malformedAndUnknownRequestsAreInvalidArguments() throws Exception {
        assertEquals("INVALID_ARGUMENT", json(runner.handle("not json")).getString("error_name"));
        assertEquals("INVALID_ARGUMENT", json(runner.handle("{\"op\":\"reboot\"}")).getString("error_name"));
        assertEquals("INVALID_ARGUMENT", json(runner.handle(new JSONObject().put("op", "generate").put("request_id", "x").put("backend", "tpu").toString())).getString("error_name"));
        assertTrue(runtime.log.isEmpty());
    }

    @Test public void shutdownClosesTheEngine() throws Exception {
        runner.handle(request("a", "cpu", "m"));
        runner.shutdown();
        assertEquals("close", runtime.log.get(runtime.log.size() - 1));
        assertEquals("idle", json(runner.handle("{\"op\":\"status\"}")).getJSONObject("data").getString("state"));
    }

    /** The runner answers a request on its own thread so that a runner that wrongly waits shows as a failed assertion. */
    private String handleOnThread(String json, long waitMs) throws Exception {
        String[] out = new String[1];
        Thread t = new Thread(() -> out[0] = runner.handle(json));
        t.start();
        t.join(waitMs);
        return out[0];
    }

    @Test public void aCancelDuringTheLoadStopsTheRequestBeforeAnyInference() throws Exception {
        runtime.loading = new CountDownLatch(1);
        runtime.releaseLoad = new CountDownLatch(1);
        String[] first = new String[1];
        Thread t = new Thread(() -> { try { first[0] = runner.handle(request("slow", "cpu", "m")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.loading.await(5, TimeUnit.SECONDS));
        // The engine is still being opened: there is no engine to cancel, but the request is the active one.
        assertTrue(json(runner.handle("{\"op\":\"cancel\",\"request_id\":\"slow\"}")).getJSONObject("data").getBoolean("cancelled"));
        assertFalse("another request id is still not the active one", json(runner.handle("{\"op\":\"cancel\",\"request_id\":\"other\"}")).getJSONObject("data").getBoolean("cancelled"));
        runtime.releaseLoad.countDown();
        t.join(5000);
        JSONObject reply = json(first[0]);
        assertFalse(reply.getBoolean("ok"));
        assertEquals("CANCELLED", reply.getString("error_name"));
        assertTrue(reply.getString("error").contains("loading"));
        assertEquals("no inference was started", 0, runtime.log.stream().filter(l -> l.startsWith("generate")).count());
        // The engine that was opened is kept, and the next request is not cancelled by the old one.
        runtime.loading = null;
        runtime.releaseLoad = null;
        JSONObject next = json(runner.handle(request("next", "cpu", "m")));
        assertTrue(next.getBoolean("ok"));
        assertTrue(next.getJSONObject("data").getBoolean("engine_reused"));
    }

    @Test public void aCancelThatCameAfterTheRequestEndedDoesNotCancelTheNextOne() throws Exception {
        runner.handle(request("done", "cpu", "m"));
        assertFalse(json(runner.handle("{\"op\":\"cancel\",\"request_id\":\"done\"}")).getJSONObject("data").getBoolean("cancelled"));
        assertTrue(json(runner.handle(request("after", "cpu", "m"))).getBoolean("ok"));
    }

    @Test public void aBusyWorkerAnswersAtOnceWhateverThePreviousRequestIsDoing() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Thread t = new Thread(() -> runner.handle(requestUnchecked("one")));
        t.start();
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        String second = handleOnThread(requestUnchecked("two"), 2000);
        runtime.release.countDown();
        t.join(5000);
        assertTrue("a second request must be answered while the first runs", second != null);
        assertEquals("BUSY", json(second).getString("error_name"));
    }

    private static String requestUnchecked(String id) {
        try { return request(id, "cpu", "m"); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // --- the cancel belongs to its request, not to the engine -------------------------------------------------

    @Test public void aCancelThatArrivesAfterTheGenerationHasEndedDoesNotCancelTheNextRequest() throws Exception {
        runtime.inEvidence = new CountDownLatch(1);
        runtime.releaseEvidence = new CountDownLatch(1);
        String[] first = new String[1];
        Thread t = new Thread(() -> { try { first[0] = runner.handle(request("one", "cpu", "m")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        // The inference of "one" has finished; the runner is only collecting the evidence for the reply.
        assertTrue(runtime.inEvidence.await(5, TimeUnit.SECONDS));
        JSONObject late = json(runner.handle("{\"op\":\"cancel\",\"request_id\":\"one\"}"));
        assertFalse("nothing is left to stop: the cancel is a no-op", late.getJSONObject("data").getBoolean("cancelled"));
        runtime.releaseEvidence.countDown();
        t.join(5000);
        assertTrue(json(first[0]).getBoolean("ok"));
        runtime.inEvidence = null;
        runtime.releaseEvidence = null;
        JSONObject second = json(runner.handle(request("two", "cpu", "m")));
        assertTrue("the next request on the same engine must not be cancelled: " + second, second.getBoolean("ok"));
        assertTrue(second.getJSONObject("data").getBoolean("engine_reused"));
        assertEquals("no native stop was ever triggered", 0, runtime.stopsTriggered.get());
    }

    @Test public void aCancelOfAFinishedRequestIsANoOpOnTheEngine() throws Exception {
        runner.handle(request("done", "cpu", "m"));
        assertFalse(json(runner.handle("{\"op\":\"cancel\",\"request_id\":\"done\"}")).getJSONObject("data").getBoolean("cancelled"));
        assertTrue(json(runner.handle(request("after", "cpu", "m"))).getBoolean("ok"));
        assertEquals(0, runtime.stopsTriggered.get());
    }

    @Test public void aCancelDuringTheGenerationStopsOnlyThatGeneration() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        String[] first = new String[1];
        Thread t = new Thread(() -> { try { first[0] = runner.handle(request("one", "cpu", "m")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        assertTrue(json(runner.handle("{\"op\":\"cancel\",\"request_id\":\"one\"}")).getJSONObject("data").getBoolean("cancelled"));
        t.join(5000);
        assertEquals("CANCELLED", json(first[0]).getString("error_name"));
        assertEquals(1, runtime.stopsTriggered.get());
        // The engine is reused and the next request runs to the end.
        runtime.release = null;
        runtime.generating = null;
        assertTrue(json(runner.handle(request("two", "cpu", "m"))).getBoolean("ok"));
        assertEquals("the second request triggered no stop", 1, runtime.stopsTriggered.get());
    }

    @Test public void aCancelThatCameDuringTheLoadIsHonouredAtTheStartOfTheGeneration() throws Exception {
        runtime.loading = new CountDownLatch(1);
        runtime.releaseLoad = new CountDownLatch(1);
        String[] first = new String[1];
        Thread t = new Thread(() -> { try { first[0] = runner.handle(request("slow", "cpu", "m")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.loading.await(5, TimeUnit.SECONDS));
        assertTrue(json(runner.handle("{\"op\":\"cancel\",\"request_id\":\"slow\"}")).getJSONObject("data").getBoolean("cancelled"));
        runtime.releaseLoad.countDown();
        t.join(5000);
        assertEquals("CANCELLED", json(first[0]).getString("error_name"));
        assertEquals(0, runtime.log.stream().filter(l -> l.startsWith("generate")).count());
    }
}
