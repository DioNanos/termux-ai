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
    String evidence = "";
    volatile boolean cancelled;

    @Override public Loaded load(String modelPath, LitertBackend backend, int contextTokens) throws LitertFailure {
        log.add("load " + backend.wire + " " + modelPath + " " + contextTokens);
        if (loadFailure != null) throw loadFailure;
        if (loadCrash != null) throw loadCrash;
        return new Loaded() {
            @Override public Output generate(String prompt, int maxTokens, double temperature, int topK, double topP, int seed) throws LitertFailure {
                log.add("generate " + prompt + " " + maxTokens + " " + temperature + " " + topK + " " + topP + " " + seed);
                if (generating != null) generating.countDown();
                if (release != null) {
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                if (cancelled) throw new LitertFailure(LitertErrorCode.CANCELLED, backend.wire, "generate", null, "cancelled", null);
                if (generateFailure != null) throw generateFailure;
                if (generateCrash != null) throw generateCrash;
                return new Output("answer", "stop");
            }
            @Override public void cancel() { log.add("cancel"); cancelled = true; if (release != null) release.countDown(); }
            @Override public String evidence() { return evidence; }
            @Override public void close() { log.add("close"); }
        };
    }
}
