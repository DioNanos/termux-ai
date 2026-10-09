package com.termux.app.terminal.ai.litert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** A worker that records its calls; it can answer, die or time out. */
final class FakeWorker implements LitertWorker {
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final List<String> cancels = Collections.synchronizedList(new ArrayList<>());
    String reply = "{\"ok\":true,\"data\":{\"text\":\"hello\",\"finish_reason\":\"stop\",\"backend_requested\":\"cpu\"," +
        "\"backend_effective\":\"cpu\",\"backend_verified\":true,\"backend_evidence\":\"cpu only\",\"engine_reused\":false,\"load_ms\":12,\"generate_ms\":34}}";
    LitertFailure failure;
    boolean connected = true;
    /** Set by tests whose stop ends the process: the connection goes away once the reply has left. */
    boolean diesAfterReply;
    CountDownLatch entered;
    CountDownLatch release;

    @Override public String call(String requestJson, long timeoutMs) throws LitertFailure {
        calls.add(requestJson);
        if (entered != null) entered.countDown();
        if (release != null) {
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (failure != null) throw failure;
        if (diesAfterReply) connected = false;
        return reply;
    }

    @Override public boolean isConnected() { return connected; }

    @Override public void cancel(String requestId) { cancels.add(requestId); }
}
