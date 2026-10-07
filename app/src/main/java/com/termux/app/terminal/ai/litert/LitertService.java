package com.termux.app.terminal.ai.litert;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.io.File;

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

    @Override public void onCreate() {
        super.onCreate();
        File cache = new File(getCacheDir(), "litert");
        // Only created here: a service that is never bound never touches the SDK.
        runner = new LitertRunner(new LiteRtLmRuntime(getApplicationInfo().nativeLibraryDir, cache.getPath()), System::currentTimeMillis);
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
        dispatcher.dispatch(json, answer -> reply(replyTo, correlation, answer));
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
        runner.shutdown();
        thread.quit();
        super.onDestroy();
    }
}
