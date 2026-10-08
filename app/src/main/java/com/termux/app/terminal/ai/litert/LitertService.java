package com.termux.app.terminal.ai.litert;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The LiteRT-LM engine, in its own process ({@code :litert}, not exported). A native crash here ends this process
 * and nothing else. Requests arrive as Messenger messages carrying the JSON of {@link LitertRunner}; a generation
 * runs on its own thread so a cancel or a status can still be answered while it works.
 */
public final class LitertService extends Service {
    static final int MSG_CALL = 1;
    static final int MSG_REPLY = 2;
    static final String KEY_JSON = "json";

    private LitertRunner runner;
    private LitertIpcDispatcher dispatcher;
    private HandlerThread thread;
    private Messenger messenger;
    private ScheduledExecutorService idleTimer;

    @Override public void onCreate() {
        super.onCreate();
        File cache = new File(getCacheDir(), "litert");
        // The SDK stops with "Cache directory does not exist" if nobody made it; a failure here is typed, not a crash.
        LitertRuntime runtime;
        try {
            LitertCacheDir.ensure(cache);
            // Only created here: a service that is never bound never touches the SDK.
            runtime = new LiteRtLmRuntime(getApplicationInfo().nativeLibraryDir, cache.getPath());
        } catch (LitertFailure f) {
            runtime = LitertCacheDir.refusing(f);
        }
        // recycle: the process ends and the system starts a clean one on the next bind (unload on GPU, restart, idle).
        LitertMemory memory = LitertMemory.ofThisProcess(Process.myPid());
        runner = new LitertRunner(runtime, System::currentTimeMillis, () -> Process.killProcess(Process.myPid()),
            LitertApp.config().load().idleUnloadMs).withMemoryProbe(() -> {
                try {
                    return memory.snapshot();
                } catch (org.json.JSONException e) {
                    return null;
                }
            });
        idleTimer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread timer = new Thread(task, "litert-idle");
            timer.setDaemon(true);
            return timer;
        });
        idleTimer.scheduleWithFixedDelay(() -> {
            try {
                runner.idleTick();
            } catch (RuntimeException ignored) {
                // A failed tick must not stop the timer: the next one decides again.
            }
        }, LitertParams.IDLE_TICK_MS, LitertParams.IDLE_TICK_MS, TimeUnit.MILLISECONDS);
        thread = new HandlerThread("litert-ipc");
        thread.start();
        dispatcher = new LitertIpcDispatcher(runner);
        messenger = new Messenger(new Handler(thread.getLooper(), this::onMessage));
    }

    private boolean onMessage(Message message) {
        if (message.what != MSG_CALL || message.replyTo == null) return false;
        Bundle data = message.getData();
        String json = data == null ? null : data.getString(KEY_JSON);
        if (json == null) return true;
        Messenger replyTo = message.replyTo;
        int correlation = message.arg1;
        // The caller is watched while a generation runs: if its process is gone the generation is cancelled.
        dispatcher.dispatch(json, answer -> reply(replyTo, correlation, answer), () -> replyTo.getBinder().pingBinder());
        return true;
    }

    private static void reply(Messenger replyTo, int correlation, String json) {
        Message message = Message.obtain(null, MSG_REPLY, correlation, 0);
        Bundle data = new Bundle();
        data.putString(KEY_JSON, json);
        message.setData(data);
        try {
            replyTo.send(message);
        } catch (RemoteException e) {
            // The caller is gone: nobody is waiting for this answer.
        }
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override public void onDestroy() {
        idleTimer.shutdownNow();
        runner.shutdown();
        thread.quit();
        super.onDestroy();
    }
}
