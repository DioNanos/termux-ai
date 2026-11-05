package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LitertIpcDispatcherTest {
    private final FakeRuntime runtime = new FakeRuntime();
    private final LitertRunner runner = new LitertRunner(runtime, System::currentTimeMillis);
    private final LitertIpcDispatcher dispatcher = new LitertIpcDispatcher(runner);
    private final ConcurrentHashMap<String, String> replies = new ConcurrentHashMap<>();

    private static String generate(String id) throws Exception {
        return new JSONObject().put("op", "generate").put("request_id", id).put("model", "m")
            .put("model_path", "/models/m.litertlm").put("backend", "cpu").put("context_tokens", 4096)
            .put("prompt", id).put("max_tokens", 64).put("temperature", 0.5).put("top_k", 20).put("top_p", 1.0).put("seed", 0).toString();
    }

    private void send(String id, String json) { dispatcher.dispatch(json, answer -> replies.put(id, answer)); }

    private String await(String id, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            String reply = replies.get(id);
            if (reply != null) return reply;
            Thread.sleep(5);
        }
        return null;
    }

    @Test public void aSecondGenerationWhilePreviousInferenceRunsIsBusyAtOnceNotQueued() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        runtime.cancelStopsGeneration = false;
        send("one", generate("one"));
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        // The broker gave up on "one" at its deadline and asked for a cancel; the inference is still running.
        send("cancel-one", "{\"op\":\"cancel\",\"request_id\":\"one\"}");
        send("two", generate("two"));
        String second = await("two", 2000);
        assertNotNull("the second request must be answered while the first inference runs, not wait behind it", second);
        assertEquals("BUSY", new JSONObject(second).getString("error_name"));
        assertEquals("only one inference was ever started", 1, runtime.log.stream().filter(l -> l.startsWith("generate")).count());
        runtime.release.countDown();
        // The slow cancel found the flag set when the inference finally returned.
        assertEquals("CANCELLED", new JSONObject(await("one", 5000)).getString("error_name"));
        // Once the inference has really ended the worker accepts work again.
        runtime.release = null;
        send("three", generate("three"));
        assertTrue(new JSONObject(await("three", 5000)).getBoolean("ok"));
    }

    @Test public void aCancelAndAStatusAreAnsweredWhileAGenerationRuns() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        send("one", generate("one"));
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        send("status", "{\"op\":\"status\"}");
        assertEquals("busy", new JSONObject(await("status", 2000)).getJSONObject("data").getString("state"));
        send("cancel", "{\"op\":\"cancel\",\"request_id\":\"one\"}");
        assertTrue(new JSONObject(await("cancel", 2000)).getJSONObject("data").getBoolean("cancelled"));
        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end && !runtime.log.contains("cancel")) Thread.sleep(5);
        assertTrue("the runtime was asked to stop", runtime.log.contains("cancel"));
        assertEquals("CANCELLED", new JSONObject(await("one", 5000)).getString("error_name"));
    }

    @Test public void aMalformedRequestIsAnsweredNotDropped() throws Exception {
        send("bad", "not json");
        assertEquals("INVALID_ARGUMENT", new JSONObject(await("bad", 2000)).getString("error_name"));
    }

    @Test public void aRecycleHappensAfterTheReplyHasBeenSent() throws Exception {
        java.util.List<String> order = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        LitertRunner gpuRunner = new LitertRunner(runtime, System::currentTimeMillis, () -> order.add("recycle"), 0);
        LitertIpcDispatcher gpuDispatcher = new LitertIpcDispatcher(gpuRunner);
        gpuRunner.handle(generate("a").replace("\"cpu\"", "\"gpu\""));
        gpuDispatcher.dispatch("{\"op\":\"unload\"}", answer -> order.add("reply"));
        assertEquals(java.util.Arrays.asList("reply", "recycle"), order);
    }

    @Test public void aRestartIsRepliedThenTheProcessEnds() throws Exception {
        java.util.List<String> order = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        LitertRunner r = new LitertRunner(runtime, System::currentTimeMillis, () -> order.add("recycle"), 0);
        new LitertIpcDispatcher(r).dispatch("{\"op\":\"restart\"}", answer -> order.add("reply"));
        assertEquals(java.util.Arrays.asList("reply", "recycle"), order);
    }

    @Test public void aStatusNeverEndsTheProcess() throws Exception {
        java.util.List<String> order = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        LitertRunner r = new LitertRunner(runtime, System::currentTimeMillis, () -> order.add("recycle"), 0);
        new LitertIpcDispatcher(r).dispatch("{\"op\":\"status\"}", answer -> order.add("reply"));
        assertEquals(java.util.Arrays.asList("reply"), order);
    }

    // ---- the caller watchdog: a generation whose caller is gone is cancelled

    private LitertIpcDispatcher quick() { return new LitertIpcDispatcher(runner, 20); }

    @Test public void aCallerThatDiesGetsItsGenerationCancelledAndTheWorkerIsFreeAgain() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        quick().dispatch(generate("w"), answer -> replies.put("w", answer), alive::get);
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        alive.set(false);
        String reply = await("w", 3000);
        assertNotNull("the orphan generation must end by itself", reply);
        assertEquals("CANCELLED", new JSONObject(reply).getString("error_name"));
        assertEquals(1, runtime.stopsTriggered.get());
        runtime.release = null;
        runtime.generating = null;
        send("next", generate("next"));
        assertTrue("the worker accepts a new request at once", new JSONObject(await("next", 3000)).getBoolean("ok"));
    }

    @Test public void aCallerThatIsAliveIsLeftAlone() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        quick().dispatch(generate("w"), answer -> replies.put("w", answer), () -> true);
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);
        assertEquals(0, runtime.stopsTriggered.get());
        runtime.release.countDown();
        assertTrue(new JSONObject(await("w", 3000)).getBoolean("ok"));
    }

    @Test public void theWatchdogStopsWhenTheGenerationEnds() throws Exception {
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        quick().dispatch(generate("w"), answer -> replies.put("w", answer), () -> { checks.incrementAndGet(); return true; });
        assertNotNull(await("w", 3000));
        Thread.sleep(100);
        int after = checks.get();
        Thread.sleep(300);
        assertEquals("no more checks once the generation is over", after, checks.get());
    }

    @Test public void aCheckThatThrowsIsNotADeath() throws Exception {
        runtime.generating = new CountDownLatch(1);
        runtime.release = new CountDownLatch(1);
        quick().dispatch(generate("w"), answer -> replies.put("w", answer), () -> { throw new IllegalStateException("probe broke"); });
        assertTrue(runtime.generating.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);
        assertEquals("a probe that fails is not a reason to cancel", 0, runtime.stopsTriggered.get());
        runtime.release.countDown();
        assertNotNull(await("w", 3000));
    }

    @Test public void onlyAGenerationIsWatched() throws Exception {
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        quick().dispatch("{\"op\":\"status\"}", answer -> replies.put("s", answer), () -> { checks.incrementAndGet(); return true; });
        assertNotNull(await("s", 2000));
        Thread.sleep(200);
        assertEquals(0, checks.get());
    }
}
