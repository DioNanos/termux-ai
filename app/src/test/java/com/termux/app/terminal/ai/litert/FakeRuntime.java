package com.termux.app.terminal.ai.litert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** A runtime that records what it is asked, in order, and can be told to fail or to block. */
final class FakeRuntime implements LitertRuntime {
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    LitertFailure loadFailure;
    RuntimeException loadCrash;
    LitertFailure generateFailure;
    RuntimeException generateCrash;
    CountDownLatch generating;       // counted down when generate starts
    CountDownLatch release;          // generate waits for it when set
    CountDownLatch loading;          // counted down when load starts
    CountDownLatch releaseLoad;      // load waits for it when set
    CountDownLatch inEvidence;       // counted down when evidence() is asked
    CountDownLatch releaseEvidence;  // evidence() waits for it when set
    String evidence = "";
    /** True while the native stop of a generation has been triggered and that generation is still running. */
    volatile boolean cancelled;
    /** False models a slow cancellation: the native inference keeps running after the stop was triggered. */
    boolean cancelStopsGeneration = true;
    /** How many generations ever saw their own cancel, and how many started: a cancel of one must not touch another. */
    final java.util.concurrent.atomic.AtomicInteger stopsTriggered = new java.util.concurrent.atomic.AtomicInteger();

    @Override public Loaded load(String modelPath, LitertBackend backend, int contextTokens) throws LitertFailure {
        log.add("load " + backend.wire + " " + modelPath + " " + contextTokens);
        if (loading != null) loading.countDown();
        if (releaseLoad != null) {
            try { releaseLoad.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (loadFailure != null) throw loadFailure;
        if (loadCrash != null) throw loadCrash;
        return new Loaded() {
            @Override public Output generate(String prompt, int maxTokens, double temperature, int topK, double topP, int seed,
                                             LitertCancelToken token) throws LitertFailure {
                log.add("generate " + prompt + " " + maxTokens + " " + temperature + " " + topK + " " + topP + " " + seed);
                // Like the real engine: nothing is kept between generations; the stop hangs on this request's token.
                cancelled = false;
                if (token.isCancelled()) throw cancelledFailure(backend);
                token.onCancel(() -> {
                    log.add("cancel");
                    stopsTriggered.incrementAndGet();
                    cancelled = true;
                    if (cancelStopsGeneration && release != null) release.countDown();
                });
                try {
                    if (generating != null) generating.countDown();
                    if (release != null) {
                        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    if (token.isCancelled()) throw cancelledFailure(backend);
                    if (generateFailure != null) throw generateFailure;
                    if (generateCrash != null) throw generateCrash;
                    return new Output("answer", "stop");
                } finally {
                    token.detach();
                }
            }

            @Override public String evidence() {
                if (inEvidence != null) inEvidence.countDown();
                if (releaseEvidence != null) {
                    try { releaseEvidence.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return evidence;
            }

            @Override public void close() { log.add("close"); }
        };
    }

    private static LitertFailure cancelledFailure(LitertBackend backend) {
        return new LitertFailure(LitertErrorCode.CANCELLED, backend.wire, "generate", null, "cancelled", null);
    }
}
