package com.termux.app.terminal.ai.litert;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * What the {@code :litert} service does with a request once it has been read from the Messenger: a generation runs
 * on its own thread, everything else is answered at once. There is deliberately no queue: the runner is the only
 * judge of "busy", so a request that arrives while an inference is still running (even one the broker has given up
 * on after its deadline) is answered BUSY immediately and never waits behind it.
 */
public final class LitertIpcDispatcher {
    private final LitertRunner runner;
    private final long callerPingMs;

    public LitertIpcDispatcher(LitertRunner runner) { this(runner, LitertParams.CALLER_PING_MS); }

    public LitertIpcDispatcher(LitertRunner runner, long callerPingMs) {
        this.runner = runner;
        this.callerPingMs = callerPingMs;
    }

    /** @param reply receives the JSON answer, once, from whichever thread produced it */
    public void dispatch(String requestJson, Consumer<String> reply) { dispatch(requestJson, reply, null); }

    /**
     * @param callerAlive whether whoever sent the request is still there; asked every {@code callerPingMs} while a
     *                    generation runs. When it says no, that generation is cancelled: nobody is waiting for it, and
     *                    left alone it would keep the worker busy until it finished. A check that throws is not a
     *                    death. Null turns the watch off.
     */
    public void dispatch(String requestJson, Consumer<String> reply, BooleanSupplier callerAlive) {
        if (isGenerate(requestJson)) {
            AtomicBoolean over = new AtomicBoolean(false);
            Thread thread = new Thread(() -> {
                try {
                    reply.accept(runner.handle(requestJson));
                } finally {
                    over.set(true);
                }
            }, "litert-generate");
            thread.setDaemon(true);
            thread.start();
            String requestId = requestIdOf(requestJson);
            if (callerAlive != null && requestId != null) watch(requestId, callerAlive, over);
        } else {
            reply.accept(runner.handle(requestJson));
            // An unload on GPU or a restart ends this process: only after the answer has been sent.
            runner.runPendingRecycle();
        }
    }

    /** Checks the caller while the generation runs; asks for the cancel again until the runner has taken it. */
    private void watch(String requestId, BooleanSupplier callerAlive, AtomicBoolean over) {
        Thread watchdog = new Thread(() -> {
            String cancel;
            try {
                cancel = new JSONObject().put("op", "cancel").put("request_id", requestId).toString();
            } catch (Exception e) {
                return;
            }
            while (!over.get()) {
                try {
                    Thread.sleep(callerPingMs);
                } catch (InterruptedException e) {
                    return;
                }
                if (over.get()) return;
                boolean alive;
                try {
                    alive = callerAlive.getAsBoolean();
                } catch (RuntimeException e) {
                    alive = true;
                }
                if (!alive && cancelled(runner.handle(cancel))) return;
            }
        }, "litert-caller-watch");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static boolean cancelled(String reply) {
        try {
            return new JSONObject(reply).getJSONObject("data").getBoolean("cancelled");
        } catch (Exception e) {
            return false;
        }
    }

    private static String requestIdOf(String json) {
        try {
            return new JSONObject(json).optString("request_id", null);
        } catch (Exception e) {
            return null;
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
