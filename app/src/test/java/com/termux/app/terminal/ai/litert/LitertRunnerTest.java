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

    @Test public void aRequestWithMessagesReachesTheRuntimeWithItsRolesNotFlattened() throws Exception {
        JSONObject req = json(request("a", "cpu", "m"));
        req.remove("prompt");
        req.put("messages", new org.json.JSONArray("[{\"role\":\"system\",\"content\":\"s\"},"
            + "{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":\"a\"},"
            + "{\"role\":\"user\",\"content\":\"last\"}]"));
        assertTrue(json(runner.handle(req.toString())).getBoolean("ok"));
        LitertChat chat = runtime.lastChat;
        assertEquals("s", chat.system);
        assertEquals(2, chat.history.size());
        assertEquals("last", chat.last.text);
    }

    @Test public void aRequestWithBadMessagesIsAnInvalidArgument() throws Exception {
        JSONObject req = json(request("a", "cpu", "m"));
        req.remove("prompt");
        req.put("messages", new org.json.JSONArray("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":\"a\"}]"));
        assertEquals("INVALID_ARGUMENT", json(runner.handle(req.toString())).getString("error_name"));
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

    @Test public void aStuckNativeCancelDropsTheEngineWithoutClosingItAndTheNextRequestOpensANewOne() throws Exception {
        runtime.generateFailure = new LitertFailure(LitertErrorCode.CANCEL_TIMEOUT, "cpu", "generate", null, "the native cancel did not return", null);
        JSONObject r = json(runner.handle(request("stuck", "cpu", "m")));
        assertEquals("CANCEL_TIMEOUT", r.getString("error_name"));
        assertFalse("the engine is abandoned, not closed under a stuck call", runtime.log.contains("close"));
        runtime.generateFailure = null;
        JSONObject next = json(runner.handle(request("next", "cpu", "m")));
        assertTrue(next.getBoolean("ok"));
        assertFalse("a new engine, not the suspect one", next.getJSONObject("data").getBoolean("engine_reused"));
        assertEquals(2, runtime.log.stream().filter(l -> l.startsWith("load")).count());
    }

    // --- a native stop that blocks never blocks the IPC thread ------------------------------------------------

    @Test public void aBlockedNativeStopNeverBlocksTheCancelTheStatusOrTheNextRequest() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        runtime.stopHold = new CountDownLatch(1);      // cancelProcess blocks until the test lets it go
        runtime.finishWaitMs = 300;
        String[] first = new String[1];
        Thread t = new Thread(() -> { try { first[0] = runner.handle(request("one", "cpu", "m")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));

        // The cancel arrives on the one IPC thread: it must be answered at once, whatever the native stop does.
        String cancel = handleOnThread("{\"op\":\"cancel\",\"request_id\":\"one\"}", 2000);
        assertTrue("the cancel must not wait for the native stop", cancel != null);
        assertTrue(json(cancel).getJSONObject("data").getBoolean("cancelled"));

        // The generation gives up on the stuck stop with the typed error.
        t.join(5000);
        assertEquals("CANCEL_TIMEOUT", json(first[0]).getString("error_name"));

        // The service still answers: the status says a stop is running ...
        String status = handleOnThread("{\"op\":\"status\"}", 2000);
        assertTrue("status must answer while a stop is stuck", status != null);
        JSONObject data = json(status).getJSONObject("data");
        assertEquals("stopping", data.getString("state"));
        assertEquals("running", data.getString("native_stop"));

        // ... and a new generate gets an immediate, typed answer instead of hanging.
        String busy = handleOnThread(request("two", "cpu", "m"), 2000);
        assertTrue("a new generate must be answered while a stop is stuck", busy != null);
        assertEquals("BUSY", json(busy).getString("error_name"));
        assertTrue(json(busy).getString("error").contains("native cancel"));
        assertEquals("no second inference was started", 1, runtime.log.stream().filter(l -> l.startsWith("generate")).count());

        // A second cancel (any request) adds no thread: the stopper is the one in flight.
        assertFalse(json(handleOnThread("{\"op\":\"cancel\",\"request_id\":\"two\"}", 2000)).getJSONObject("data").getBoolean("cancelled"));

        // The stop returns: the worker is free, on a new engine.
        runtime.stopHold.countDown();
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end && !"none".equals(json(runner.handle("{\"op\":\"status\"}")).getJSONObject("data").getString("native_stop"))) Thread.sleep(10);
        runtime.release = null;
        runtime.generating = null;
        runtime.stopHold = null;
        JSONObject next = json(runner.handle(request("three", "cpu", "m")));
        assertTrue("accepted again once the stop returned: " + next, next.getBoolean("ok"));
        assertFalse("the engine of the stuck stop was dropped", next.getJSONObject("data").getBoolean("engine_reused"));
    }

    // ---- 6c: an empty answer is not a success, and what the model also produced is reported

    @Test public void anEmptyAnswerIsATypedErrorNeverOkTrue() throws Exception {
        runtime.answer = "";
        runtime.thinking = "step one";
        JSONObject r = json(runner.handle(request("e1", "cpu", "m")));
        assertFalse(r.toString(), r.getBoolean("ok"));
        assertEquals("EMPTY_OUTPUT", r.getString("error_name"));
        assertEquals("generate", r.getString("phase"));
        assertTrue(r.getString("error"), r.getString("error").contains("thinking 8 chars"));
        assertTrue(r.getString("error"), r.getString("error").contains("tool calls 0"));
    }

    @Test public void anAnswerOfOnlyWhitespaceIsEmptyToo() throws Exception {
        runtime.answer = "  \n\t ";
        assertEquals("EMPTY_OUTPUT", json(runner.handle(request("e2", "cpu", "m"))).getString("error_name"));
    }

    @Test public void anEmptyAnswerDoesNotCostTheEngine() throws Exception {
        runtime.answer = "";
        runner.handle(request("e3", "cpu", "m"));
        runtime.answer = "back";
        JSONObject data = json(runner.handle(request("e4", "cpu", "m"))).getJSONObject("data");
        assertEquals("back", data.getString("text"));
        assertTrue("the engine opened for the empty answer is reused", data.getBoolean("engine_reused"));
        assertEquals(1, java.util.Collections.frequency(runtime.log, "load cpu /models/m.litertlm 4096"));
        assertFalse(runtime.log.contains("close"));
    }

    @Test public void thinkingAndToolCallsAreReportedNextToTheAnswer() throws Exception {
        runtime.thinking = "because";
        runtime.toolCalls.add(new LitertChat.Call("", "a", new JSONObject()));
        runtime.toolCalls.add(new LitertChat.Call("", "b", new JSONObject()));
        JSONObject data = json(runner.handle(request("e5", "cpu", "m"))).getJSONObject("data");
        assertEquals("answer", data.getString("text"));
        assertEquals("because", data.getString("thinking"));
        assertEquals(2, data.getInt("tool_calls_count"));
        assertEquals(2, data.getJSONArray("tool_calls").length());
    }

    private static final String WEATHER_TOOL = "{\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
        + "\"description\":\"Weather of a city\",\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}}";

    private static JSONObject chatRequest(String id) throws Exception {
        JSONObject req = json(request(id, "cpu", "m"));
        req.remove("prompt");
        return req.put("messages", new org.json.JSONArray("[{\"role\":\"user\",\"content\":\"weather in Rome?\"}]"));
    }

    @Test public void theToolsOfTheRequestReachTheRuntimeAndTheCallsComeBackStructured() throws Exception {
        runtime.answer = "";
        runtime.toolCalls.add(new LitertChat.Call("", "get_weather", new JSONObject().put("city", "Rome")));
        JSONObject req = chatRequest("t1").put("tools", new org.json.JSONArray("[" + WEATHER_TOOL + "]"));
        JSONObject r = json(runner.handle(req.toString()));
        assertTrue("a turn made of tool calls only is an answer, not an empty output", r.getBoolean("ok"));
        assertEquals(1, runtime.lastChat.tools.size());
        assertTrue(runtime.lastChat.tools.get(0).contains("get_weather"));
        JSONObject data = r.getJSONObject("data");
        assertEquals("tool_calls", data.getString("finish_reason"));
        assertEquals(1, data.getInt("tool_calls_count"));
        JSONObject call = data.getJSONArray("tool_calls").getJSONObject(0);
        assertEquals("get_weather", call.getString("name"));
        assertEquals("Rome", call.getJSONObject("arguments").getString("city"));
    }

    @Test public void noTextAndNoCallsIsStillAnEmptyOutput() throws Exception {
        runtime.answer = "";
        assertEquals("EMPTY_OUTPUT", json(runner.handle(chatRequest("t2").toString())).getString("error_name"));
    }

    @Test public void withoutThinkingTheFieldsStillExistAndAreEmpty() throws Exception {
        JSONObject data = json(runner.handle(request("e6", "cpu", "m"))).getJSONObject("data");
        assertEquals("", data.getString("thinking"));
        assertEquals(0, data.getInt("tool_calls_count"));
    }

    // ---- the activation precision is part of the engine key

    private static String requestWithActivation(String id, String activation) throws Exception {
        JSONObject r = new JSONObject(request(id, "gpu", "m"));
        if (activation != null) r.put("activation", activation);
        return r.toString();
    }

    @Test public void theActivationReachesTheRuntimeAndAnAbsentOneIsTheDefault() throws Exception {
        runner.handle(requestWithActivation("a1", "fp32"));
        assertEquals("load gpu /models/m.litertlm 4096 fp32", runtime.log.get(0));
        runtime.log.clear();
        LitertRunner other = new LitertRunner(runtime, () -> clock.addAndGet(5));
        other.handle(requestWithActivation("a2", null));
        assertEquals("the default adds nothing to what the runtime sees", "load gpu /models/m.litertlm 4096", runtime.log.get(0));
    }

    @Test public void theSameActivationReusesTheEngine() throws Exception {
        runner.handle(requestWithActivation("a1", "fp32"));
        JSONObject second = json(runner.handle(requestWithActivation("a2", "fp32"))).getJSONObject("data");
        assertTrue(second.getBoolean("engine_reused"));
        assertEquals(1, runtime.log.stream().filter(l -> l.startsWith("load")).count());
    }

    @Test public void aDifferentActivationClosesTheOldEngineBeforeTheNewOneOpens() throws Exception {
        runner.handle(requestWithActivation("a1", null));
        JSONObject second = json(runner.handle(requestWithActivation("a2", "fp32"))).getJSONObject("data");
        assertFalse("an fp16 engine is not an fp32 one", second.getBoolean("engine_reused"));
        java.util.List<String> kinds = new java.util.ArrayList<>();
        for (String line : runtime.log) if (line.startsWith("load") || line.equals("close")) kinds.add(line.split(" ")[0]);
        assertEquals(java.util.Arrays.asList("load", "close", "load"), kinds);
    }

    @Test public void theResultAndTheStatusSayWhichActivationWasAskedFor() throws Exception {
        JSONObject data = json(runner.handle(requestWithActivation("a1", "fp32"))).getJSONObject("data");
        assertEquals("fp32", data.getString("activation_requested"));
        assertEquals("fp32", json(runner.handle("{\"op\":\"status\"}")).getJSONObject("data").getString("loaded_activation"));
        LitertRunner fresh = new LitertRunner(runtime, () -> clock.addAndGet(5));
        assertEquals("default", json(fresh.handle(requestWithActivation("a2", null))).getJSONObject("data").getString("activation_requested"));
    }
}
