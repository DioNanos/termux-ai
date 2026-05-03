package com.termux.app.terminal.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

public final class TermuxAiCommandReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.termux.AI_COMMAND";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            setResultCode(1);
            setResultData("{\"ok\":false,\"error\":\"Invalid action\"}");
            return;
        }

        final PendingResult pending = goAsync();
        final Context appContext = context.getApplicationContext();
        final String cmd = intent.getStringExtra("cmd");
        final Bundle extras = intent.getExtras();

        new Thread(() -> {
            try {
                JSONObject args = extrasToJson(extras);
                String response = TermuxAiSocketServer.processRequest(appContext,
                    new JSONObject().put("cmd", cmd == null ? "ping" : cmd).put("args", args).toString());
                pending.setResultCode(new JSONObject(response).optBoolean("ok", false) ? 0 : 1);
                pending.setResultData(response);
            } catch (Exception e) {
                pending.setResultCode(1);
                try {
                    pending.setResultData(new JSONObject().put("ok", false).put("error", e.getMessage()).toString());
                } catch (Exception ignored) {
                    pending.setResultData("{\"ok\":false,\"error\":\"Unknown bridge error\"}");
                }
            } finally {
                pending.finish();
            }
        }, "termux-ai-cmd").start();
    }

    private JSONObject extrasToJson(Bundle extras) throws Exception {
        JSONObject args = new JSONObject();
        if (extras == null) return args;
        for (String key : extras.keySet()) {
            if ("cmd".equals(key)) continue;
            Object value = extras.get(key);
            if (value == null) continue;
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                args.put(key, value);
            } else if (value instanceof String[]) {
                JSONArray array = new JSONArray();
                for (String item : (String[]) value) array.put(item);
                args.put(key, array);
            } else if (value instanceof int[]) {
                JSONArray array = new JSONArray();
                for (int item : (int[]) value) array.put(item);
                args.put(key, array);
            } else if (value instanceof long[]) {
                JSONArray array = new JSONArray();
                for (long item : (long[]) value) array.put(item);
                args.put(key, array);
            } else if (value instanceof float[]) {
                JSONArray array = new JSONArray();
                for (float item : (float[]) value) array.put(item);
                args.put(key, array);
            }
        }
        return args;
    }
}
