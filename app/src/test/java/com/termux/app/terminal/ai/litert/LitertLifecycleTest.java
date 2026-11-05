package com.termux.app.terminal.ai.litert;

import com.termux.app.UncaughtExceptionRecorder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** unload, restart and the idle unload of the :litert runner. */
public class LitertLifecycleTest {
    // diagnostic for the test JVM: records dying threads, delegates, and restores the handler at the end of the class
    @ClassRule public static final UncaughtExceptionRecorder.Rule UNCAUGHT = new UncaughtExceptionRecorder.Rule();

    private static final long IDLE = 300_000L;

    private final FakeRuntime runtime = new FakeRuntime();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicInteger recycles = new AtomicInteger();
    private final LitertRunner runner = new LitertRunner(runtime, now::get, recycles::incrementAndGet, IDLE);

    private static String request(String id, String backend) throws Exception {
        return new JSONObject().put("op", "generate").put("request_id", id).put("model", "m")
            .put("model_path", "/models/m.litertlm").put("backend", backend).put("context_tokens", 4096)
            .put("prompt", "hi").put("max_tokens", 64).put("temperature", 0.5).put("top_k", 20).put("top_p", 1.0).put("seed", 0).toString();
    }

    private static JSONObject json(String s) throws Exception { return new JSONObject(s); }
    private JSONObject op(String name) throws Exception { return json(runner.handle("{\"op\":\"" + name + "\"}")); }
    private String state() throws Exception { return op("status").getJSONObject("data").getString("state"); }
    private long closes() { return runtime.log.stream().filter("close"::equals).count(); }

    // ---- threads: every thread a test starts is observed, and none outlives its test

    private final ExecutorService pool = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "lifecycle-test");
        thread.setDaemon(true);
        return thread;
    });

    @After public void releaseWhateverIsStillBlocked() throws Exception {
        // Whatever a test left blocked in the fake runtime is let go, whether its assertions held or not.
        for (CountDownLatch latch : new CountDownLatch[] {runtime.release, runtime.releaseLoad, runtime.releaseEvidence, runtime.releaseClose, runtime.stopHold}) {
            if (latch != null) latch.countDown();
        }
        pool.shutdownNow();
        assertTrue("a thread of the test is still running", pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    /** A generation on its own thread; its reply and any exception that ends the thread are taken by {@link #result}. */
    private Future<String> generateAsync(String id, String backend) {
        return pool.submit(() -> runner.handle(request(id, backend)));
    }

    /** The outcome of a test thread, with a limit. An exception that ended the thread fails the test with it as cause. */
    private static <T> T result(Future<T> thread) throws Exception {
        try {
            return thread.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("a thread of the test died with " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("a thread of the test did not finish within 5 s", e);
        }
    }

    // ---- unload

    @Test public void unloadAtRestOnCpuClosesTheEngine() throws Exception {
        runner.handle(request("a", "cpu"));
        JSONObject r = op("unload");
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertTrue(r.getJSONObject("data").getBoolean("unloaded"));
        assertEquals("close", runtime.log.get(runtime.log.size() - 1));
        assertEquals("idle", state());
        assertEquals("a CPU unload never recycles the process", 0, recycles.get());
    }

    @Test public void unloadWithNothingLoadedIsOkAndChangesNothing() throws Exception {
        JSONObject r = op("unload");
        assertTrue(r.getBoolean("ok"));
        assertFalse(r.getJSONObject("data").getBoolean("unloaded"));
        assertEquals(0, runtime.log.size());
        runner.runPendingRecycle();
        assertEquals(0, recycles.get());
    }

    @Test public void unloadWhileAGenerationRunsIsBusyAndClosesNothing() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        JSONObject r = op("unload");
        assertEquals("BUSY", r.getString("error_name"));
        assertEquals(0, closes());
        runtime.release.countDown();
        assertTrue(json(result(generation)).getBoolean("ok"));
        assertEquals("the engine is still there", "loaded", state());
    }

    @Test public void unloadWhileANativeStopRunsIsBusy() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        runtime.stopHold = new CountDownLatch(1);
        runtime.finishWaitMs = 200;
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        runner.handle("{\"op\":\"cancel\",\"request_id\":\"one\"}");
        assertEquals("CANCEL_TIMEOUT", json(result(generation)).getString("error_name"));
        assertEquals("stopping", state());
        assertEquals("BUSY", op("unload").getString("error_name"));
        runtime.stopHold.countDown();
    }

    @Test public void unloadOnGpuRecyclesTheProcessAfterTheReplyAndNeverClosesInPlace() throws Exception {
        runner.handle(request("a", "gpu"));
        JSONObject r = op("unload");
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertTrue(r.getJSONObject("data").getBoolean("unloaded"));
        assertEquals("process_recycle", r.getJSONObject("data").getString("via"));
        assertEquals("the process is ended after the reply is out, not before", 0, recycles.get());
        runner.runPendingRecycle();
        assertEquals(1, recycles.get());
        assertEquals("a GPU engine is never closed in place", 0, closes());
        runner.runPendingRecycle();
        assertEquals("a recycle is done once", 1, recycles.get());
    }

    @Test public void aGenerateBetweenTheGpuUnloadReplyAndTheRecycleIsBusyNotKilledMidFlight() throws Exception {
        runner.handle(request("a", "gpu"));
        op("unload");
        JSONObject r = json(runner.handle(request("b", "cpu")));
        assertEquals("BUSY", r.getString("error_name"));
        assertTrue(r.getString("error"), r.getString("error").contains("recycl"));
    }

    // ---- restart

    @Test public void restartRecyclesTheProcessWhateverTheBackend() throws Exception {
        runner.handle(request("a", "cpu"));
        JSONObject r = op("restart");
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertTrue(r.getJSONObject("data").getBoolean("restarting"));
        assertEquals(0, recycles.get());
        runner.runPendingRecycle();
        assertEquals(1, recycles.get());
        assertEquals("restart ends the process: nothing is closed in place", 0, closes());
    }

    @Test public void restartWorksEvenWhileAGenerationIsStuckBusy() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "gpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        assertEquals("BUSY", op("unload").getString("error_name"));
        assertTrue("restart is the way out of a busy worker", op("restart").getBoolean("ok"));
        runner.runPendingRecycle();
        assertEquals(1, recycles.get());
        assertEquals("nothing is closed under a generation that is still running", 0, closes());
        // The native call comes back after the restart: the request was invalidated, so it ends as a dead worker,
        // on the thread that ran it, with a reply and without an exception.
        runtime.release.countDown();
        JSONObject late = json(result(generation));
        assertFalse(late.toString(), late.getBoolean("ok"));
        assertEquals("MODEL_WORKER_DIED", late.getString("error_name"));
        assertEquals("the recycle is done once", 1, recycles.get());
        assertEquals(0, closes());
    }

    private long generates() { return runtime.log.stream().filter(l -> l.startsWith("generate")).count(); }
    private long loads() { return runtime.log.stream().filter(l -> l.startsWith("load")).count(); }

    @Test public void theRestartDoesNotWaitForTheNativeCallAndTheLateReplyIsTheWorkerDeath() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "gpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        // The restart answers while the native call is still blocked: nothing in it waits for that call.
        assertTrue(op("restart").getBoolean("ok"));
        assertFalse("the generation has not been released, so it has not answered", generation.isDone());
        runner.runPendingRecycle();
        assertEquals(1, recycles.get());
        assertEquals("nothing is closed under a generation that is still running", 0, closes());
        runtime.release.countDown();
        JSONObject late = json(result(generation));
        assertFalse(late.toString(), late.getBoolean("ok"));
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), late.getString("error_name"));
        assertEquals("worker", late.getString("phase"));
        assertEquals("the invalidated request reads nothing more from the engine", 0, runtime.evidenceCalls.get());
        assertEquals(0, closes());
    }

    @Test public void aRestartDuringTheLoadDropsTheRequestAndPublishesNoEngine() throws Exception {
        runtime.loading = new CountDownLatch(1);
        runtime.releaseLoad = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "gpu");
        assertTrue(runtime.loading.await(5, TimeUnit.SECONDS));
        assertTrue(op("restart").getBoolean("ok"));
        runner.runPendingRecycle();
        assertEquals(1, recycles.get());
        runtime.releaseLoad.countDown();
        JSONObject late = json(result(generation));
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), late.getString("error_name"));
        assertEquals("no inference was started on the engine that opened late", 0, generates());
        assertEquals("the engine that opened late is not closed in a worker that is being recycled", 0, closes());
        JSONObject status = op("status").getJSONObject("data");
        assertTrue("the engine that opened late is not published", status.isNull("loaded_model_path"));
        assertEquals("idle", status.getString("state"));
        assertTrue(status.getBoolean("recycle_pending"));
    }

    @Test public void aRestartWhileTheResponseIsBeingBuiltIsTheWorkerDeathToo() throws Exception {
        runtime.inEvidence = new CountDownLatch(1);
        runtime.releaseEvidence = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.inEvidence.await(5, TimeUnit.SECONDS));
        assertEquals("the generation itself is over", 1, generates());
        assertTrue(op("restart").getBoolean("ok"));
        runtime.releaseEvidence.countDown();
        JSONObject late = json(result(generation));
        assertFalse(late.toString(), late.getBoolean("ok"));
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), late.getString("error_name"));
        assertEquals(0, closes());
        assertTrue(op("status").getJSONObject("data").isNull("loaded_model_path"));
    }

    @Test public void aRequestThatEndedBeforeTheRestartKeepsItsSuccess() throws Exception {
        JSONObject done = json(runner.handle(request("a", "gpu")));
        assertTrue(done.toString(), done.getBoolean("ok"));
        assertTrue(op("restart").getBoolean("ok"));
        runner.runPendingRecycle();
        assertEquals(1, recycles.get());
        assertTrue("the answer that was given is not taken back", done.getBoolean("ok"));
        assertEquals("a restart closes nothing in place", 0, closes());
    }

    @Test public void theLateEndOfAnInvalidatedRequestChangesNeitherTheRecycleNorTheEngineState() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        assertTrue(op("restart").getBoolean("ok"));
        JSONObject during = op("status").getJSONObject("data");
        assertTrue(during.getBoolean("recycle_pending"));
        assertEquals("one", during.getString("active_request_id"));
        runtime.release.countDown();
        result(generation);
        JSONObject after = op("status").getJSONObject("data");
        assertTrue("the old request does not clear the recycle", after.getBoolean("recycle_pending"));
        assertTrue("the old request does not put the engine back", after.isNull("loaded_model_path"));
        assertTrue(after.isNull("active_request_id"));
        assertEquals("idle", after.getString("state"));
    }

    @Test public void aNewGenerateAfterTheRestartIsRefusedBusyAndNeverReachesTheRuntime() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        assertTrue(op("restart").getBoolean("ok"));
        // While the old request is still in the native call, and after it has returned: both are BUSY.
        assertEquals("BUSY", json(runner.handle(request("two", "cpu"))).getString("error_name"));
        runtime.release.countDown();
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), json(result(generation)).getString("error_name"));
        JSONObject three = json(runner.handle(request("three", "cpu")));
        assertEquals("BUSY", three.getString("error_name"));
        assertTrue(three.getString("error"), three.getString("error").contains("recycl"));
        assertEquals("only the first request ever reached the runtime", 1, generates());
        assertEquals(1, loads());
    }

    @Test public void restartingTwiceRecyclesOnceAndAnswersTheStuckRequestOnce() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "gpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        assertTrue(op("restart").getBoolean("ok"));
        assertTrue(op("restart").getBoolean("ok"));
        runner.runPendingRecycle();
        runner.runPendingRecycle();
        assertEquals(1, recycles.get());
        runtime.release.countDown();
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), json(result(generation)).getString("error_name"));
        runner.runPendingRecycle();
        assertEquals(1, recycles.get());
    }

    @Test public void theEndOfTheProcessNeverClosesAnEngineThatIsInUseAndRefusesNewGenerates() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        runner.shutdown();
        assertEquals("the engine is in use by the generation: it is not closed under it", 0, closes());
        runtime.release.countDown();
        JSONObject late = json(result(generation));
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), late.getString("error_name"));
        assertEquals("the engine abandoned by the shutdown is not read afterwards", 0, runtime.evidenceCalls.get());
        assertEquals(0, closes());
        // The runner has ended: nothing new is accepted, however late the old request returned.
        JSONObject next = json(runner.handle(request("two", "cpu")));
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), next.getString("error_name"));
        assertEquals("only the first request ever reached the runtime", 1, generates());
        assertEquals(1, loads());
        assertEquals(0, closes());
        assertTrue(op("status").getJSONObject("data").isNull("loaded_model_path"));
    }

    @Test public void aGenerateAfterTheShutdownIsRefusedAsDeadEvenWhileTheOldRequestIsStillRunning() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        runner.shutdown();
        // The old request has not returned: the runner is still busy, but it has ended, and that comes first.
        assertFalse(generation.isDone());
        JSONObject next = json(runner.handle(request("two", "cpu")));
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), next.getString("error_name"));
        assertEquals("worker", next.getString("phase"));
        assertEquals(1, generates());
        assertEquals(1, loads());
        assertEquals(0, closes());
        runtime.release.countDown();
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), json(result(generation)).getString("error_name"));
    }

    @Test public void aGenerateAfterTheShutdownIsRefusedAsDeadEvenWhileANativeStopIsStillRunning() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        runtime.stopHold = new CountDownLatch(1);
        runtime.cancelStopsGeneration = false;
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        assertTrue(json(runner.handle("{\"op\":\"cancel\",\"request_id\":\"one\"}")).getJSONObject("data").getBoolean("cancelled"));
        assertEquals("the native stop is running and held", "running", op("status").getJSONObject("data").getString("native_stop"));
        runner.shutdown();
        JSONObject next = json(runner.handle(request("two", "cpu")));
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), next.getString("error_name"));
        assertEquals("worker", next.getString("phase"));
        assertEquals(1, generates());
        assertEquals(1, loads());
        assertEquals(0, closes());
        runtime.stopHold.countDown();
        runtime.release.countDown();
        result(generation);
    }

    @Test public void theEndOfTheProcessClosesAnIdleEngineOnceAndRefusesNewGenerates() throws Exception {
        runner.handle(request("a", "cpu"));
        runner.shutdown();
        assertEquals("an idle engine is closed cleanly", 1, closes());
        runner.shutdown();
        assertEquals("a second shutdown closes nothing more", 1, closes());
        JSONObject next = json(runner.handle(request("b", "cpu")));
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), next.getString("error_name"));
        assertEquals(1, loads());
        assertEquals(1, generates());
        assertEquals("shutdown does not ask for a process recycle", 0, recycles.get());
    }

    @Test public void theEndOfTheProcessDuringTheLoadPublishesAndClosesNothing() throws Exception {
        runtime.loading = new CountDownLatch(1);
        runtime.releaseLoad = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.loading.await(5, TimeUnit.SECONDS));
        runner.shutdown();
        runtime.releaseLoad.countDown();
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), json(result(generation)).getString("error_name"));
        assertEquals(0, generates());
        assertEquals(0, closes());
        assertTrue(op("status").getJSONObject("data").isNull("loaded_model_path"));
    }

    @Test public void aRestartDuringTheCloseOfTheOldModelStartsNoNewLoad() throws Exception {
        runner.handle(request("a", "cpu"));
        assertEquals(1, loads());
        runtime.closing = new CountDownLatch(1);
        runtime.releaseClose = new CountDownLatch(1);
        Future<String> switching = pool.submit(() -> runner.handle(
            request("b", "cpu").replace("\"model\":\"m\"", "\"model\":\"other\"").replace("m.litertlm", "other.litertlm")));
        assertTrue(runtime.closing.await(5, TimeUnit.SECONDS));
        // The restart answers while the native close of the old model is still blocked.
        assertTrue(op("restart").getBoolean("ok"));
        runner.runPendingRecycle();
        assertEquals(1, recycles.get());
        runtime.releaseClose.countDown();
        JSONObject late = json(result(switching));
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), late.getString("error_name"));
        assertEquals("no second load is started in a worker that has been restarted", 1, loads());
        assertEquals(1, generates());
        assertTrue(op("status").getJSONObject("data").isNull("loaded_model_path"));
    }

    @Test public void theEndOfTheProcessDuringTheCloseOfTheOldModelStartsNoNewLoad() throws Exception {
        runner.handle(request("a", "cpu"));
        runtime.closing = new CountDownLatch(1);
        runtime.releaseClose = new CountDownLatch(1);
        Future<String> switching = pool.submit(() -> runner.handle(
            request("b", "cpu").replace("\"model\":\"m\"", "\"model\":\"other\"").replace("m.litertlm", "other.litertlm")));
        assertTrue(runtime.closing.await(5, TimeUnit.SECONDS));
        runner.shutdown();
        runtime.releaseClose.countDown();
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED.name(), json(result(switching)).getString("error_name"));
        assertEquals(1, loads());
    }

    @Test public void aRestartWithNothingRunningInvalidatesNothingAndDropsTheEngine() throws Exception {
        runner.handle(request("a", "cpu"));
        assertTrue(op("restart").getBoolean("ok"));
        JSONObject status = op("status").getJSONObject("data");
        assertTrue(status.isNull("loaded_model_path"));
        assertTrue(status.getBoolean("recycle_pending"));
        assertEquals(0, closes());
    }

    // ---- idle unload

    @Test public void anIdleCpuEngineIsClosedAtTheThresholdAndNotBefore() throws Exception {
        runner.handle(request("a", "cpu"));
        now.addAndGet(IDLE - 1);
        runner.idleTick();
        assertEquals("loaded", state());
        now.addAndGet(1);
        runner.idleTick();
        assertEquals("idle", state());
        assertEquals(1, closes());
        assertEquals(0, recycles.get());
    }

    @Test public void anIdleGpuOrNpuEngineRecyclesTheProcessInsteadOfClosing() throws Exception {
        for (String backend : new String[] {"gpu", "npu"}) {
            // A recycled process is gone: each backend gets a fresh runner, as the system would start a fresh process.
            runtime.log.clear();
            recycles.set(0);
            LitertRunner fresh = new LitertRunner(runtime, now::get, recycles::incrementAndGet, IDLE);
            fresh.handle(request("a-" + backend, backend));
            now.addAndGet(IDLE);
            fresh.idleTick();
            assertEquals(backend, 1, recycles.get());
            assertEquals(backend + " is not closed in place", 0, closes());
            assertEquals("idle", json(fresh.handle("{\"op\":\"status\"}")).getJSONObject("data").getString("state"));
        }
    }

    @Test public void aGenerationInFlightAtTheThresholdIsNeverUnloadedAndTheDeadlineRestartsAtItsEnd() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        now.addAndGet(IDLE * 2);
        runner.idleTick();
        assertEquals(0, closes());
        runtime.release.countDown();
        assertTrue(json(result(generation)).getBoolean("ok"));
        runner.idleTick();
        assertEquals("the clock starts again when the generation ends", 0, closes());
        now.addAndGet(IDLE);
        runner.idleTick();
        assertEquals(1, closes());
    }

    @Test public void statusAndCancelDoNotMoveTheDeadline() throws Exception {
        runner.handle(request("a", "cpu"));
        now.addAndGet(IDLE - 100_000);
        op("status");
        runner.handle("{\"op\":\"cancel\",\"request_id\":\"a\"}");
        now.addAndGet(100_000);
        runner.idleTick();
        assertEquals("monitoring must not defeat the unload", 1, closes());
    }

    @Test public void aNewGenerationMovesTheDeadline() throws Exception {
        runner.handle(request("a", "cpu"));
        now.addAndGet(IDLE - 1);
        runner.handle(request("b", "cpu"));
        now.addAndGet(IDLE - 1);
        runner.idleTick();
        assertEquals(0, closes());
        now.addAndGet(1);
        runner.idleTick();
        assertEquals(1, closes());
    }

    @Test public void aZeroTimeoutTurnsTheTimerOff() throws Exception {
        LitertRunner off = new LitertRunner(runtime, now::get, recycles::incrementAndGet, 0);
        off.handle(request("a", "cpu"));
        now.addAndGet(Long.MAX_VALUE / 4);
        off.idleTick();
        assertEquals(0, closes());
    }

    @Test public void theStatusSaysWhenTheEngineWillBeUnloaded() throws Exception {
        JSONObject empty = op("status").getJSONObject("data");
        assertEquals(IDLE, empty.getLong("idle_unload_ms"));
        assertTrue(empty.isNull("idle_unload_in_ms"));
        runner.handle(request("a", "cpu"));
        now.addAndGet(100_000);
        assertEquals(IDLE - 100_000, op("status").getJSONObject("data").getLong("idle_unload_in_ms"));
    }

    @Test public void theTimerAndAGenerationNeverCrossBetweenTheCheckAndTheClose() throws Exception {
        // Stress: a tick that is always due races a stream of generations. A close must never fall between the
        // load and the generate of one request.
        runtime.log.clear();
        Future<?> ticker = pool.submit(() -> {
            long end = System.currentTimeMillis() + 800;
            while (System.currentTimeMillis() < end) {
                now.addAndGet(IDLE);
                runner.idleTick();
            }
        });
        Future<?> worker = pool.submit(() -> {
            for (int i = 0; i < 300; i++) runner.handle(request("g" + i, "cpu"));
            return null;
        });
        result(worker);
        result(ticker);
        List<String> log = new ArrayList<>(runtime.log);
        for (int i = 0; i + 1 < log.size(); i++) {
            assertFalse("a close between the load and the generate of one request at " + i,
                log.get(i).startsWith("load") && log.get(i + 1).equals("close"));
        }
        assertNotNull(log);
    }

    // ---- voice 3: a close that fails is never swallowed, and memory is measured around the close

    @Test public void aCloseThatThrowsIsReportedByUnloadAndTheEngineIsStillForgotten() throws Exception {
        runtime.closeFailure = new IllegalStateException("kgsl busy");
        runner.handle(request("a", "cpu"));
        JSONObject data = op("unload").getJSONObject("data");
        assertTrue(data.getBoolean("unloaded"));
        assertFalse(data.getBoolean("closed_cleanly"));
        assertTrue(data.getString("close_error"), data.getString("close_error").contains("kgsl busy"));
        assertTrue(data.getString("close_error"), data.getString("close_error").contains("IllegalStateException"));
        assertEquals("idle", state());
    }

    @Test public void aCleanCloseSaysSoAndLeavesNoError() throws Exception {
        runner.handle(request("a", "cpu"));
        JSONObject data = op("unload").getJSONObject("data");
        assertTrue(data.getBoolean("closed_cleanly"));
        assertFalse(data.has("close_error"));
        assertTrue(op("status").getJSONObject("data").isNull("last_close_error"));
    }

    @Test public void theLastCloseErrorStaysInTheStatusUntilACleanCloseReplacesIt() throws Exception {
        runtime.closeFailure = new IllegalStateException("boom");
        runner.handle(request("a", "cpu"));
        op("unload");
        assertTrue(op("status").getJSONObject("data").getString("last_close_error").contains("boom"));
        runtime.closeFailure = null;
        runner.handle(request("b", "cpu"));
        op("unload");
        assertTrue("a clean close clears it", op("status").getJSONObject("data").isNull("last_close_error"));
    }

    @Test public void aCloseThatFailsOnAKeySwitchIsRecordedToo() throws Exception {
        runtime.closeFailure = new IllegalStateException("switch");
        runner.handle(request("a", "cpu"));
        JSONObject r = json(runner.handle(request("b", "cpu").replace("\"model\":\"m\"", "\"model\":\"other\"").replace("m.litertlm", "other.litertlm")));
        assertTrue("the new request still runs", r.getBoolean("ok"));
        assertTrue(op("status").getJSONObject("data").getString("last_close_error").contains("switch"));
    }

    @Test public void anIdleCloseThatFailsIsRecorded() throws Exception {
        runtime.closeFailure = new IllegalStateException("idle");
        runner.handle(request("a", "cpu"));
        now.addAndGet(IDLE);
        runner.idleTick();
        assertTrue(op("status").getJSONObject("data").getString("last_close_error").contains("idle"));
    }

    private LitertRunner withProbe(java.util.List<Long> rss) {
        java.util.Iterator<Long> values = rss.iterator();
        return new LitertRunner(runtime, now::get, recycles::incrementAndGet, IDLE).withMemoryProbe(() -> {
            try { return new JSONObject().put("rss_kb", values.next()); } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test public void unloadMeasuresMemoryBeforeAndAfterTheClose() throws Exception {
        LitertRunner measured = withProbe(java.util.Arrays.asList(900_000L, 120_000L));
        measured.handle(request("a", "cpu"));
        JSONObject data = json(measured.handle("{\"op\":\"unload\"}")).getJSONObject("data");
        assertEquals(900_000L, data.getJSONObject("memory_before").getLong("rss_kb"));
        assertEquals(120_000L, data.getJSONObject("memory_after").getLong("rss_kb"));
    }

    @Test public void aGpuUnloadMeasuresBeforeAndSaysThereIsNoAfterInThisProcess() throws Exception {
        LitertRunner measured = withProbe(java.util.Arrays.asList(900_000L, 1L));
        measured.handle(request("a", "gpu"));
        JSONObject data = json(measured.handle("{\"op\":\"unload\"}")).getJSONObject("data");
        assertEquals(900_000L, data.getJSONObject("memory_before").getLong("rss_kb"));
        assertTrue("the process ends: its memory after is the next process", data.isNull("memory_after"));
    }

    @Test public void theStatusCarriesTheCurrentMemory() throws Exception {
        LitertRunner measured = withProbe(java.util.Arrays.asList(777L));
        assertEquals(777L, json(measured.handle("{\"op\":\"status\"}")).getJSONObject("data").getJSONObject("memory").getLong("rss_kb"));
    }

    @Test public void withoutAProbeTheMemoryFieldsAreAbsent() throws Exception {
        runner.handle(request("a", "cpu"));
        assertFalse(op("unload").getJSONObject("data").has("memory_before"));
        assertFalse(op("status").getJSONObject("data").has("memory"));
    }

    // ---- voice 2: cancel one request or whatever runs, and the status says what runs

    @Test public void cancelAllCancelsWhateverRequestIsRunning() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        JSONObject r = json(runner.handle("{\"op\":\"cancel\",\"all\":true}"));
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertTrue(r.getJSONObject("data").getBoolean("cancelled"));
        assertEquals("one", r.getJSONObject("data").getString("request_id"));
        assertEquals("CANCELLED", json(result(generation)).getString("error_name"));
    }

    @Test public void cancelAllWithNothingRunningIsAFalseNotAnError() throws Exception {
        JSONObject r = json(runner.handle("{\"op\":\"cancel\",\"all\":true}"));
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertFalse(r.getJSONObject("data").getBoolean("cancelled"));
    }

    @Test public void aCancelNeedsExactlyOneOfRequestIdAndAll() throws Exception {
        assertEquals("INVALID_ARGUMENT", json(runner.handle("{\"op\":\"cancel\"}")).getString("error_name"));
        assertEquals("INVALID_ARGUMENT", json(runner.handle("{\"op\":\"cancel\",\"all\":true,\"request_id\":\"x\"}")).getString("error_name"));
    }

    @Test public void theStatusNamesTheRequestThatRuns() throws Exception {
        assertTrue(op("status").getJSONObject("data").isNull("active_request_id"));
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        Future<String> generation = generateAsync("one", "cpu");
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        assertEquals("one", op("status").getJSONObject("data").getString("active_request_id"));
        runtime.release.countDown();
        assertTrue(json(result(generation)).getBoolean("ok"));
        assertTrue(op("status").getJSONObject("data").isNull("active_request_id"));
    }
}
