package com.termux.app.terminal.ai.litert;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.TransactionTooLargeException;

import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The main process's end of the border to {@code :litert}. It binds the service on the first request, matches each
 * reply to its request, and turns a dead process into MODEL_WORKER_DIED for every request in flight. After a death
 * the next request binds again; the engine retries a generate once (declared as {@code spawn_retried}), everything
 * else is not sent a second time.
 */
public final class LitertWorkerClient implements LitertWorker {
    private static final long START_TIMEOUT_MS = 15_000L;

    private final Context context;
    private final Handler handler;
    private final Messenger replies;
    private final AtomicInteger ids = new AtomicInteger();
    private final ConcurrentHashMap<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Object bindLock = new Object();

    private volatile Messenger service;
    private volatile boolean bound;
    private volatile CountDownLatch connected = new CountDownLatch(1);

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String reply;
        volatile LitertFailure failure;
    }

    public LitertWorkerClient(Context context) {
        this.context = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("litert-client");
        thread.start();
        this.handler = new Handler(thread.getLooper(), this::onReply);
        this.replies = new Messenger(handler);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            connected.countDown();
        }

        @Override public void onServiceDisconnected(ComponentName name) { died("the :litert process died"); }

        @Override public void onBindingDied(ComponentName name) { died("the :litert process died while binding"); }

        @Override public void onNullBinding(ComponentName name) { died("the :litert service returned no binder"); }
    };

    private boolean onReply(Message message) {
        if (message.what != LitertService.MSG_REPLY) return false;
        Pending waiting = pending.remove(message.arg1);
        Bundle data = message.getData();
        if (waiting != null) {
            waiting.reply = data == null ? null : data.getString(LitertService.KEY_JSON);
            waiting.done.countDown();
        }
        return true;
    }

    private void died(String reason) {
        service = null;
        LitertFailure failure = new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, reason, null);
        for (Integer id : pending.keySet()) {
            Pending waiting = pending.remove(id);
            if (waiting != null) {
                waiting.failure = failure;
                waiting.done.countDown();
            }
        }
        synchronized (bindLock) {
            if (bound) {
                bound = false;
                try { context.unbindService(connection); } catch (RuntimeException ignored) { /* already unbound */ }
            }
            connected = new CountDownLatch(1);
        }
    }

    private Messenger ensureBound() throws LitertFailure {
        CountDownLatch latch;
        synchronized (bindLock) {
            if (!bound) {
                Intent intent = new Intent(context, LitertService.class);
                if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    throw new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "the :litert service could not be bound", null);
                }
                bound = true;
            }
            latch = connected;
        }
        try {
            if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "the :litert process did not start in " + START_TIMEOUT_MS + " ms", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LitertFailure(LitertErrorCode.CANCELLED, null, "worker", null, "interrupted while starting the :litert process", e);
        }
        Messenger current = service;
        if (current == null) {
            throw new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "the :litert process died while starting", null);
        }
        return current;
    }

    @Override public String call(String requestJson, long timeoutMs) throws LitertFailure {
        Messenger target = ensureBound();
        int id = ids.incrementAndGet();
        Pending waiting = new Pending();
        pending.put(id, waiting);
        Message message = Message.obtain(null, LitertService.MSG_CALL, id, 0);
        Bundle data = new Bundle();
        data.putString(LitertService.KEY_JSON, requestJson);
        message.setData(data);
        message.replyTo = replies;
        try {
            target.send(message);
        } catch (TransactionTooLargeException e) {
            pending.remove(id);
            throw LitertFailure.invalid("the request is too large for the :litert process");
        } catch (RemoteException e) {
            pending.remove(id);
            throw new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "the :litert process is gone: " + e.getMessage(), e);
        }
        try {
            if (!waiting.done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pending.remove(id);
                throw new LitertFailure(LitertErrorCode.DEADLINE_EXCEEDED, null, "worker", null, "no reply from the :litert process in " + timeoutMs + " ms", null);
            }
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new LitertFailure(LitertErrorCode.CANCELLED, null, "worker", null, "interrupted while waiting for the :litert process", e);
        }
        if (waiting.failure != null) throw waiting.failure;
        if (waiting.reply == null) {
            throw new LitertFailure(LitertErrorCode.GENERATION_FAILED, null, "worker", null, "the :litert process sent an empty reply", null);
        }
        return waiting.reply;
    }

    @Override public boolean isConnected() { return bound && service != null; }

    @Override public void cancel(String requestId) {
        Messenger target = service;
        if (target == null) return;
        try {
            Message message = Message.obtain(null, LitertService.MSG_CALL, ids.incrementAndGet(), 0);
            Bundle data = new Bundle();
            data.putString(LitertService.KEY_JSON, new JSONObject().put("op", "cancel").put("request_id", requestId).toString());
            message.setData(data);
            message.replyTo = replies;
            target.send(message);
        } catch (Exception ignored) {
            // Best effort: the deadline already ended the wait.
        }
    }
}
