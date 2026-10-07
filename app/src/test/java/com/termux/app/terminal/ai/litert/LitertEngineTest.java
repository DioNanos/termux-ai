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

    @Test public void aWorkerThatDiesIsReportedOnceAndNeverReplayed() throws Exception {
        worker.failure = new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "the :litert process died", null);
        LitertEngine engine = engine(34);
        LitertFailure f = failureOf(engine, params("r1", "npu"));
        assertEquals(LitertErrorCode.MODEL_WORKER_DIED, f.code);
        assertEquals("npu", f.backendRequested);
        assertEquals("m", f.model);
        assertEquals(1, worker.calls.size());
        // The request is over: the next one is accepted (and goes out as its own request).
        worker.failure = null;
        engine.generate(params("r2", "npu"));
        assertEquals(2, worker.calls.size());
        assertEquals("r2", new JSONObject(worker.calls.get(1)).getString("request_id"));
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
        assertEquals(16, LitertErrorCode.values().length);
        java.util.Set<Integer> numbers = new java.util.HashSet<>();
        for (LitertErrorCode c : LitertErrorCode.values()) assertTrue(c.name(), numbers.add(c.number));
    }
}
