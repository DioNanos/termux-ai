package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** unload, restart and the idle unload of the :litert runner. */
public class LitertLifecycleTest {
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
        Thread t = new Thread(() -> { try { runner.handle(request("one", "cpu")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        JSONObject r = op("unload");
        assertEquals("BUSY", r.getString("error_name"));
        assertEquals(0, closes());
        runtime.release.countDown();
        t.join(5000);
        assertEquals("the engine is still there", "loaded", state());
    }

    @Test public void unloadWhileANativeStopRunsIsBusy() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        runtime.stopHold = new CountDownLatch(1);
        runtime.finishWaitMs = 200;
        Thread t = new Thread(() -> { try { runner.handle(request("one", "cpu")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        runner.handle("{\"op\":\"cancel\",\"request_id\":\"one\"}");
        t.join(5000);
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
        Thread t = new Thread(() -> { try { runner.handle(request("one", "gpu")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        assertEquals("BUSY", op("unload").getString("error_name"));
        assertTrue("restart is the way out of a busy worker", op("restart").getBoolean("ok"));
        runner.runPendingRecycle();
        assertEquals(1, recycles.get());
        runtime.release.countDown();
        t.join(5000);
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
        Thread t = new Thread(() -> { try { runner.handle(request("one", "cpu")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        now.addAndGet(IDLE * 2);
        runner.idleTick();
        assertEquals(0, closes());
        runtime.release.countDown();
        t.join(5000);
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
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Thread ticker = new Thread(() -> {
            long end = System.currentTimeMillis() + 800;
            while (System.currentTimeMillis() < end) {
                now.addAndGet(IDLE);
                runner.idleTick();
            }
        });
        Thread worker = new Thread(() -> {
            try {
                for (int i = 0; i < 300; i++) runner.handle(request("g" + i, "cpu"));
            } catch (Throwable e) { errors.add(e); }
        });
        ticker.start(); worker.start();
        worker.join(20000); ticker.join(20000);
        assertTrue(errors.toString(), errors.isEmpty());
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
        String[] reply = new String[1];
        Thread t = new Thread(() -> { try { reply[0] = runner.handle(request("one", "cpu")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        JSONObject r = json(runner.handle("{\"op\":\"cancel\",\"all\":true}"));
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertTrue(r.getJSONObject("data").getBoolean("cancelled"));
        assertEquals("one", r.getJSONObject("data").getString("request_id"));
        t.join(5000);
        assertEquals("CANCELLED", json(reply[0]).getString("error_name"));
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
        Thread t = new Thread(() -> { try { runner.handle(request("one", "cpu")); } catch (Exception e) { throw new RuntimeException(e); } });
        t.start();
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        assertEquals("one", op("status").getJSONObject("data").getString("active_request_id"));
        runtime.release.countDown();
        t.join(5000);
        assertTrue(op("status").getJSONObject("data").isNull("active_request_id"));
    }
}
