package com.termux.app.terminal.ai.litert;

import org.json.JSONObject;

import java.util.function.Consumer;

/**
 * What the {@code :litert} service does with a request once it has been read from the Messenger: a generation runs
 * on its own thread, everything else is answered at once. There is deliberately no queue: the runner is the only
 * judge of "busy", so a request that arrives while an inference is still running (even one the broker has given up
 * on after its deadline) is answered BUSY immediately and never waits behind it.
 */
public final class LitertIpcDispatcher {
    private final LitertRunner runner;

    public LitertIpcDispatcher(LitertRunner runner) { this.runner = runner; }

    /** @param reply receives the JSON answer, once, from whichever thread produced it */
    public void dispatch(String requestJson, Consumer<String> reply) {
        if (isGenerate(requestJson)) {
            Thread thread = new Thread(() -> reply.accept(runner.handle(requestJson)), "litert-generate");
            thread.setDaemon(true);
            thread.start();
        } else {
            reply.accept(runner.handle(requestJson));
        }
    }

    static boolean isGenerate(String json) {
        try {
            return "generate".equals(new JSONObject(json).optString("op"));
        } catch (Exception e) {
            return false;
        }
    }
}
