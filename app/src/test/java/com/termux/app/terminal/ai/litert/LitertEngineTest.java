package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class LitertEngineTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final FakeWorker worker = new FakeWorker();
    private File modelsDir;
    private Predicate<String> libs = name -> true;

    @Before public void setUp() throws Exception {
        modelsDir = tmp.newFolder("models");
        Files.write(new File(modelsDir, "m.litertlm").toPath(), new byte[16]);
    }

    private LitertEngine engine(int sdk) {
        LitertGuards guards = new LitertGuards(sdk, Collections.singletonList("arm64-v8a"), name -> libs.test(name));
        return new LitertEngine(worker, new LitertModelCatalog(modelsDir), guards, sdk, "/native", "/cache", 1000);
    }

    private static LitertParams params(String id, String backend) throws Exception {
        return LitertParams.fromArgs(new JSONObject().put("request_id", id).put("model", "m").put("backend", backend).put("prompt", "hi"));
    }

    private static LitertFailure failureOf(LitertEngine engine, LitertParams p) throws Exception {
        try {
            engine.generate(p);
            fail("expected a failure");
            return null;
        } catch (LitertFailure f) {
            return f;
        }
    }

    @Test public void infoNeverTouchesTheWorkerAndSaysWhichBackendsCanBeTried() throws Exception {
        libs = name -> false;
        JSONObject info = engine(34).info();
        assertEquals(0, worker.calls.size());
        assertEquals("litertlm", info.getString("backend"));
        assertEquals(":litert", info.getString("process"));
        assertTrue(info.getJSONObject("backends").getJSONObject("cpu").getBoolean("available"));
        assertFalse(info.getJSONObject("backends").getJSONObject("npu").getBoolean("available"));
        assertEquals(modelsDir.getPath(), info.getString("models_directory"));
        assertFalse(info.getBoolean("busy"));
    }

    @Test public void infoExplainsHowToReadTheBackendFieldsAndTheFinishReason() throws Exception {
        JSONObject info = engine(34).info();
        JSONObject reporting = info.getJSONObject("backend_reporting");
        assertTrue(reporting.getString("cpu").contains("no delegate"));
        assertTrue(reporting.getString("cpu").contains("verified is true"));
        for (String backend : new String[] {"gpu", "npu"}) {
            assertTrue(reporting.getString(backend).contains("null"));
            assertTrue(reporting.getString(backend).contains("verified is false"));
        }
        assertTrue(info.getString("finish_reason").startsWith("always other"));
    }

    @Test public void workerStatusDoesNotStartAWorkerThatIsNotRunning() throws Exception {
        worker.connected = false;
        assertEquals("not_started", engine(34).workerStatus().getString("state"));
        assertEquals(0, worker.calls.size());
    }

    @Test public void generateSendsTheRequestOnceWithTheExactBackendAndModelPath() throws Exception {
        JSONObject out = engine(34).generate(params("r1", "cpu"));
        assertEquals(1, worker.calls.size());
        JSONObject sent = new JSONObject(worker.calls.get(0));
        assertEquals("generate", sent.getString("op"));
        assertEquals("r1", sent.getString("request_id"));
        assertEquals("cpu", sent.getString("backend"));
        assertEquals(new File(modelsDir, "m.litertlm").getCanonicalPath(), sent.getString("model_path"));
        assertEquals(4096, sent.getInt("context_tokens"));
        assertEquals(1.0, sent.getDouble("top_p"), 0);
        assertEquals("/native", sent.getString("native_library_dir"));
        assertEquals("hello", out.getString("text"));
        assertEquals("r1", out.getString("request_id"));
        assertEquals("cpu", out.getString("backend_effective"));
        assertTrue(out.getBoolean("backend_verified"));
        assertEquals(256, out.getJSONObject("params").getInt("max_tokens"));
        assertEquals(34, out.getJSONObject("timing").getLong("generate_ms"));
    }

    @Test public void aReplyCannotClaimAnExecutorForAGpuOrNpuRequest() throws Exception {
        // The worker says "cpu, verified" for an npu request: the broker does not pass that on.
        JSONObject out = engine(34).generate(params("r1", "npu"));
        assertEquals("npu", out.getString("backend_requested"));
        assertTrue(out.isNull("backend_effective"));
        assertFalse(out.getBoolean("backend_verified"));
    }

    @Test public void theDeviceGuardAndTheCatalogFailBeforeTheWorkerIsReached() throws Exception {
        libs = name -> false;
        assertEquals(LitertErrorCode.NATIVE_LIBRARY_UNAVAILABLE, failureOf(engine(34), params("r1", "npu")).code);
        assertEquals(LitertErrorCode.RUNTIME_TOO_OLD, failureOf(engine(23), params("r2", "cpu")).code);
        LitertParams missing = LitertParams.fromArgs(new JSONObject().put("request_id", "r3").put("model", "absent").put("backend", "cpu").put("prompt", "x"));
        assertEquals(LitertErrorCode.MODEL_NOT_FOUND, failureOf(engine(34), missing).code);
        assertEquals(0, worker.calls.size());
    }

    @Test public void aSecondRequestWhileOneRunsIsBusyAndOnlyTheFirstReachesTheWorker() throws Exception {
        worker.entered = new CountDownLatch(1);
        worker.release = new CountDownLatch(1);
        LitertEngine engine = engine(34);
        Thread t = new Thread(() -> { try { engine.generate(params("one", "cpu")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(worker.entered.await(5, TimeUnit.SECONDS));
        assertTrue(engine.info().getBoolean("busy"));
        LitertFailure busy = failureOf(engine, params("two", "cpu"));
        assertEquals(LitertErrorCode.BUSY, busy.code);
        assertTrue(busy.getMessage().contains("one"));
        worker.release.countDown();
        t.join(5000);
        assertEquals(1, worker.calls.size());
        assertFalse(engine.info().getBoolean("busy"));
    }

    /** Dies once like the :litert process does around its first spawn, then answers. */
    private static final class DiesOnce implements LitertWorker {
        final java.util.List<String> calls = new java.util.ArrayList<>();
        @Override public String call(String requestJson, long timeoutMs) throws LitertFailure {
            calls.add(requestJson);
            if (calls.size() == 1) throw new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "the :litert process died", null);
            return "{\"ok\":true,\"data\":{\"text\":\"hello\",\"finish_reason\":\"stop\",\"backend_requested\":\"cpu\",\"backend_effective\":\"cpu\",\"backend_verified\":true,\"engine_reused\":false}}";
        }
        @Override public boolean isConnected() { return true; }
        @Override public void cancel(String requestId) { }
    }

    private LitertEngine engine(LitertWorker worker, int sdk) {
        LitertGuards guards = new LitertGuards(sdk, Collections.singletonList("arm64-v8a"), name -> libs.test(name));
        return new LitertEngine(worker, new LitertModelCatalog(modelsDir), guards, sdk, "/native", "/cache", 1000);
    }

    @Test public void aDeathAroundTheSpawnIsRetriedExactlyOnceAndTheRetryIsDeclared() throws Exception {
        DiesOnce diesOnce = new DiesOnce();
        JSONObject out = engine(diesOnce, 34).generate(params("r1", "gpu"));
        assertEquals("exactly one retry: two calls, one request id both times", 2, diesOnce.calls.size());
        assertEquals("r1", new JSONObject(diesOnce.calls.get(1)).getString("request_id"));
        assertEquals("hello", out.getString("text"));
        assertTrue("the answer says the spawn was retried", out.getBoolean("spawn_retried"));
    }

    @Test public void aDeathThatRepeatsGetsNoSecondRetryAndStaysTyped() throws Exception {
        worker.failure = new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "the :litert process died", null);
        LitertEngine engine = engine(34);
        LitertFailure f = failureOf(engine, params("r1", "npu"));
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED, f.code);
        assertEquals("npu", f.backendRequested);
        assertEquals("m", f.model);
        assertEquals("one retry, then the typed failure", 2, worker.calls.size());
        // The request is over: the next one is accepted (and goes out as its own request).
        worker.failure = null;
        engine.generate(params("r2", "npu"));
        assertEquals(3, worker.calls.size());
        assertEquals("r2", new JSONObject(worker.calls.get(2)).getString("request_id"));
    }

    @Test public void aDeadlineAsksTheWorkerToCancelThatRequest() throws Exception {
        worker.failure = new LitertFailure(LitertErrorCode.DEADLINE_EXCEEDED, null, "worker", null, "no reply in time", null);
        assertEquals(LitertErrorCode.DEADLINE_EXCEEDED, failureOf(engine(34), params("slow", "cpu")).code);
        assertEquals(Collections.singletonList("slow"), worker.cancels);
        assertEquals(1, worker.calls.size());
    }

    @Test public void aDeadlineSaysThatTheWorkerMayStillBeBusy() throws Exception {
        worker.failure = new LitertFailure(LitertErrorCode.DEADLINE_EXCEEDED, null, "worker", null, "no reply in time", null);
        LitertFailure f = failureOf(engine(34), params("slow", "cpu"));
        assertEquals(LitertErrorCode.DEADLINE_EXCEEDED, f.code);
        assertEquals("cpu", f.backendRequested);
        assertEquals("m", f.model);
        assertTrue(f.getMessage(), f.getMessage().contains("may still be finishing this request"));
        assertTrue(f.getMessage(), f.getMessage().contains("BUSY"));
        // The broker is free again; whether the worker is, is the worker's answer.
        worker.failure = null;
        worker.reply = "{\"ok\":false,\"error_name\":\"BUSY\",\"phase\":\"generate\",\"error\":\"request slow is still running\"}";
        assertEquals(LitertErrorCode.BUSY, failureOf(engine(34), params("next", "cpu")).code);
    }

    @Test public void aWorkerErrorKeepsItsCodePhaseAndOriginalMessage() throws Exception {
        worker.reply = "{\"ok\":false,\"error_name\":\"BACKEND_INIT_FAILED\",\"phase\":\"init\",\"error\":\"dispatch rejected the model\"}";
        LitertFailure f = failureOf(engine(34), params("r1", "npu"));
        assertEquals(LitertErrorCode.BACKEND_INIT_FAILED, f.code);
        assertEquals("init", f.phase);
        assertEquals("dispatch rejected the model", f.getMessage());
        JSONObject json = new JSONObject(f.toJson());
        assertFalse(json.getBoolean("ok"));
        assertEquals("BACKEND_INIT_FAILED", json.getString("error_name"));
        assertEquals(LitertErrorCode.BACKEND_INIT_FAILED.number, json.getInt("error_code"));
        assertEquals("npu", json.getString("backend_requested"));
    }

    @Test public void anUnknownWorkerCodeOrAMalformedReplyIsAGenerationFailure() throws Exception {
        worker.reply = "{\"ok\":false,\"error_name\":\"SOMETHING_NEW\",\"error\":\"x\"}";
        assertEquals(LitertErrorCode.GENERATION_FAILED, failureOf(engine(34), params("r1", "cpu")).code);
        worker.reply = "garbage";
        assertEquals(LitertErrorCode.GENERATION_FAILED, failureOf(engine(34), params("r2", "cpu")).code);
    }

    @Test public void errorCodesAreStable() {
        assertEquals(1001, LitertErrorCode.INVALID_ARGUMENT.number);
        assertEquals(1009, LitertErrorCode.BACKEND_INIT_FAILED.number);
        assertEquals(1011, LitertErrorCode.BUSY.number);
        assertEquals(1015, LitertErrorCode.MODEL_WORKER_DIED.number);
        assertEquals(19, LitertErrorCode.values().length);
        assertEquals(1019, LitertErrorCode.EMPTY_OUTPUT.number);
        assertEquals(1017, LitertErrorCode.CANCEL_TIMEOUT.number);
        java.util.Set<Integer> numbers = new java.util.HashSet<>();
        for (LitertErrorCode c : LitertErrorCode.values()) assertTrue(c.name(), numbers.add(c.number));
    }

    @Test public void thinkingAndToolCallsFromTheWorkerReachTheResult() throws Exception {
        worker.reply = "{\"ok\":true,\"data\":{\"text\":\"hi\",\"finish_reason\":\"stop\",\"thinking\":\"because\",\"tool_calls_count\":3}}";
        JSONObject out = engine(34).generate(params("t1", "cpu"));
        assertEquals("because", out.getString("thinking"));
        assertEquals(3, out.getInt("tool_calls_count"));
    }

    @Test public void aWorkerThatSendsNeitherFieldGivesEmptyValues() throws Exception {
        JSONObject out = engine(34).generate(params("t2", "cpu"));
        assertEquals("", out.getString("thinking"));
        assertEquals(0, out.getInt("tool_calls_count"));
    }

    @Test public void anEmptyOutputErrorFromTheWorkerKeepsItsCode() throws Exception {
        worker.reply = "{\"ok\":false,\"error_name\":\"EMPTY_OUTPUT\",\"phase\":\"generate\",\"error\":\"no answer text\"}";
        assertEquals(LitertErrorCode.EMPTY_OUTPUT, failureOf(engine(34), params("t3", "cpu")).code);
    }

    // ---- unload / restart from the main process

    @Test public void unloadWithTheWorkerNotStartedDoesNotStartIt() throws Exception {
        worker.connected = false;
        JSONObject out = engine(34).unload();
        assertEquals(0, worker.calls.size());
        assertFalse(out.getBoolean("unloaded"));
        assertEquals("not_started", out.getString("state"));
    }

    @Test public void unloadAsksTheWorkerAndReturnsItsData() throws Exception {
        worker.reply = "{\"ok\":true,\"data\":{\"unloaded\":true,\"via\":\"close\"}}";
        JSONObject out = engine(34).unload();
        assertEquals(1, worker.calls.size());
        assertEquals("unload", new JSONObject(worker.calls.get(0)).getString("op"));
        assertTrue(out.getBoolean("unloaded"));
        assertEquals("close", out.getString("via"));
    }

    @Test public void unloadBusyKeepsItsTypedCode() throws Exception {
        worker.reply = "{\"ok\":false,\"error_name\":\"BUSY\",\"phase\":\"unload\",\"error\":\"request x is still running\"}";
        try {
            engine(34).unload();
            fail("expected BUSY");
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.BUSY, f.code);
        }
    }

    @Test public void aGpuUnloadWhoseReplyIsLostToTheEndingProcessStillSucceeds() throws Exception {
        worker.failure = new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "the :litert process died", null);
        worker.diesAfterReply = true;
        JSONObject out = engine(34).unload();
        assertTrue(out.toString(), out.getBoolean("unloaded"));
        assertTrue(out.getBoolean("reply_lost"));
    }

    @Test public void restartWithTheWorkerNotStartedDoesNotStartIt() throws Exception {
        worker.connected = false;
        JSONObject out = engine(34).restart();
        assertEquals(0, worker.calls.size());
        assertFalse(out.getBoolean("restarted"));
        assertEquals("not_started", out.getString("state"));
    }

    @Test public void restartAsksTheWorker() throws Exception {
        worker.reply = "{\"ok\":true,\"data\":{\"restarting\":true}}";
        worker.diesAfterReply = true;
        JSONObject out = engine(34).restart();
        assertEquals("restart", new JSONObject(worker.calls.get(0)).getString("op"));
        assertTrue(out.getBoolean("restarted"));
        assertFalse(out.getBoolean("reply_lost"));
    }

    @Test public void aRestartWhoseReplyIsLostToTheDeathItCausedIsASuccess() throws Exception {
        worker.failure = new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "the :litert process died", null);
        worker.diesAfterReply = true;
        JSONObject out = engine(34).restart();
        assertTrue(out.getBoolean("restarted"));
        assertTrue(out.getBoolean("reply_lost"));
    }

    // ---- the bounded wait for an ending worker, and info right after a stop

    /** A worker whose process goes away after a number of connection polls; it answers (or fails) whatever ops it gets. */
    private static final class EndingWorker implements LitertWorker {
        final java.util.List<String> calls = new java.util.ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        final int pollsWhileConnected;
        String reply = "{\"ok\":true,\"data\":{\"unloaded\":true,\"via\":\"process_recycle\"}}";
        LitertFailure failure;
        EndingWorker(int pollsWhileConnected) { this.pollsWhileConnected = pollsWhileConnected; }
        @Override public String call(String requestJson, long timeoutMs) throws LitertFailure {
            calls.add(requestJson);
            if (failure != null) throw failure;
            return reply;
        }
        @Override public boolean isConnected() { return polls.getAndIncrement() < pollsWhileConnected; }
        @Override public void cancel(String requestId) { }
        boolean gone() { return polls.get() > pollsWhileConnected; }
    }

    /** Connected all along (a stale binding), every call dies: the shape of info right after a stop on the device. */
    private static final class StopWindowWorker implements LitertWorker {
        final java.util.List<String> calls = new java.util.ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        @Override public String call(String requestJson, long timeoutMs) throws LitertFailure {
            calls.add(requestJson);
            throw new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "the :litert process died", null);
        }
        @Override public boolean isConnected() { return polls.getAndIncrement() != 1; }
        @Override public void cancel(String requestId) { }
    }

    private LitertEngine engine(LitertWorker worker, int sdk, java.util.function.LongSupplier clock) {
        LitertGuards guards = new LitertGuards(sdk, Collections.singletonList("arm64-v8a"), name -> libs.test(name));
        return new LitertEngine(worker, new LitertModelCatalog(modelsDir), guards, sdk, "/native", "/cache", 1000, clock);
    }

    @Test public void unloadWaitsForTheEndingWorkerBeforeItAnswers() throws Exception {
        EndingWorker ending = new EndingWorker(2);
        JSONObject out = engine(ending, 34).unload();
        assertTrue("the worker is gone before the answer leaves", ending.gone());
        assertTrue(out.getBoolean("unloaded"));
        assertFalse(out.getBoolean("teardown_pending"));
    }

    @Test public void restartWaitsForTheEndingWorkerBeforeItAnswers() throws Exception {
        EndingWorker ending = new EndingWorker(2);
        ending.reply = "{\"ok\":true,\"data\":{\"restarting\":true}}";
        JSONObject out = engine(ending, 34).restart();
        assertTrue("the worker is gone before the answer leaves", ending.gone());
        assertTrue(out.getBoolean("restarted"));
        assertFalse(out.getBoolean("teardown_pending"));
    }

    @Test public void anUnloadWhoseWorkerNeverGoesAwayAnswersWithTeardownPending() throws Exception {
        EndingWorker stuck = new EndingWorker(Integer.MAX_VALUE);
        JSONObject out = engine(stuck, 34).unload();
        assertTrue(out.getBoolean("unloaded"));
        assertTrue("the timer expired without a disconnection", out.getBoolean("teardown_pending"));
    }

    @Test public void aCpuUnloadThatKeepsTheProcessAliveDoesNotWaitAndIsNotPending() throws Exception {
        EndingWorker alive = new EndingWorker(Integer.MAX_VALUE);
        alive.reply = "{\"ok\":true,\"data\":{\"unloaded\":true,\"via\":\"close\"}}";
        JSONObject out = engine(alive, 34).unload();
        assertTrue(out.getBoolean("unloaded"));
        assertEquals("close", out.getString("via"));
        assertFalse("a live process is not a pending teardown", out.getBoolean("teardown_pending"));
    }

    @Test public void infoSaysStoppingWhileARecentStopHasTheWorkerDying() throws Exception {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        StopWindowWorker zombie = new StopWindowWorker();
        LitertEngine engine = engine(zombie, 34, now::get);
        engine.unload();
        assertEquals("stopping", engine.workerStatus().getString("state"));
    }

    @Test public void infoGoesBackToTypedUnreachableOnceTheStopWindowHasPassed() throws Exception {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        StopWindowWorker zombie = new StopWindowWorker();
        LitertEngine engine = engine(zombie, 34, now::get);
        engine.unload();
        now.addAndGet(6_000L);
        try {
            engine.workerStatus();
            fail("expected MODEL_WORKER_DIED once the window is over");
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.MODEL_WORKER_DIED, f.code);
        }
    }

    @Test public void aRestartThatTimesOutIsAnError() throws Exception {
        worker.failure = new LitertFailure(LitertErrorCode.DEADLINE_EXCEEDED, null, "worker", null, "no reply in time", null);
        try {
            engine(34).restart();
            fail("expected DEADLINE_EXCEEDED");
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.DEADLINE_EXCEEDED, f.code);
        }
    }

    @Test public void onGpuTheDefaultPrecisionIsFp32AndOnlyAnExplicitAskChangesIt() throws Exception {
        engine(34).generate(LitertParams.fromArgs(new JSONObject().put("request_id", "x1").put("model", "m").put("backend", "gpu").put("prompt", "hi").put("activation", "fp32")));
        assertEquals("fp32", new JSONObject(worker.calls.get(0)).getString("activation"));
        engine(34).generate(LitertParams.fromArgs(new JSONObject().put("request_id", "x1b").put("model", "m").put("backend", "gpu").put("prompt", "hi").put("activation", "fp16")));
        assertEquals("fp16", new JSONObject(worker.calls.get(1)).getString("activation"));
        engine(34).generate(params("x2", "gpu"));
        assertEquals("left out on gpu, fp32 is what the engine asks for", "fp32", new JSONObject(worker.calls.get(2)).getString("activation"));
        engine(34).generate(params("x3", "cpu"));
        assertFalse("on cpu an unasked activation is not in the request at all", new JSONObject(worker.calls.get(3)).has("activation"));
    }

    @Test public void theActivationTheWorkerReportsReachesTheResult() throws Exception {
        worker.reply = "{\"ok\":true,\"data\":{\"text\":\"hi\",\"finish_reason\":\"stop\",\"activation_requested\":\"fp32\"}}";
        assertEquals("fp32", engine(34).generate(params("x3", "gpu")).getString("activation_requested"));
        worker.reply = "{\"ok\":true,\"data\":{\"text\":\"hi\",\"finish_reason\":\"stop\"}}";
        assertEquals("default", engine(34).generate(params("x4", "gpu")).getString("activation_requested"));
    }

    @Test public void theContextTheCallerAsksForReachesTheWorkerAndTheResult() throws Exception {
        LitertParams asked = LitertParams.fromArgs(new JSONObject()
            .put("request_id", "c1").put("model", "m").put("backend", "cpu").put("prompt", "hi").put("context_tokens", 16384));
        JSONObject out = engine(34).generate(asked);
        assertEquals("the engine is opened with the context that was asked for", 16384,
            new JSONObject(worker.calls.get(0)).getInt("context_tokens"));
        assertEquals("the result reports the context that ran", 16384,
            out.getJSONObject("params").getInt("context_tokens"));
        LitertParams defaults = LitertParams.fromArgs(new JSONObject()
            .put("request_id", "c2").put("model", "m").put("backend", "cpu").put("prompt", "hi"));
        engine(34).generate(defaults);
        assertEquals("the prudent default is unchanged", 4096,
            new JSONObject(worker.calls.get(1)).getInt("context_tokens"));
    }

    // ---- cancel from the main process

    @Test public void cancelWithTheWorkerNotStartedDoesNotStartIt() throws Exception {
        worker.connected = false;
        JSONObject out = engine(34).cancel("r1", false);
        assertEquals(0, worker.calls.size());
        assertFalse(out.getBoolean("cancelled"));
        assertEquals("not_started", out.getString("state"));
    }

    @Test public void cancelOneAsksTheWorkerForThatRequest() throws Exception {
        worker.reply = "{\"ok\":true,\"data\":{\"cancelled\":true}}";
        JSONObject out = engine(34).cancel("r1", false);
        JSONObject sent = new JSONObject(worker.calls.get(0));
        assertEquals("cancel", sent.getString("op"));
        assertEquals("r1", sent.getString("request_id"));
        assertFalse(sent.has("all"));
        assertTrue(out.getBoolean("cancelled"));
    }

    @Test public void cancelAllAsksTheWorkerForWhateverRuns() throws Exception {
        worker.reply = "{\"ok\":true,\"data\":{\"cancelled\":true,\"request_id\":\"orphan\"}}";
        JSONObject out = engine(34).cancel(null, true);
        JSONObject sent = new JSONObject(worker.calls.get(0));
        assertTrue(sent.getBoolean("all"));
        assertFalse(sent.has("request_id"));
        assertEquals("orphan", out.getString("request_id"));
    }

    @Test public void aBadRequestIdOrNeitherNorBothNeverReachTheWorker() throws Exception {
        for (Object[] bad : new Object[][] {{"a/b", false}, {"", false}, {null, false}, {"ok", true}}) {
            try {
                engine(34).cancel((String) bad[0], (Boolean) bad[1]);
                fail("expected a rejection of " + java.util.Arrays.toString(bad));
            } catch (LitertFailure f) {
                assertEquals(LitertErrorCode.INVALID_ARGUMENT, f.code);
            }
        }
        assertEquals(0, worker.calls.size());
    }

    @Test public void aDeadWorkerOnCancelIsTheTypedError() throws Exception {
        worker.failure = new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "died", null);
        try {
            engine(34).cancel("r1", false);
            fail("expected MODEL_WORKER_DIED");
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.MODEL_WORKER_DIED, f.code);
        }
    }
}
