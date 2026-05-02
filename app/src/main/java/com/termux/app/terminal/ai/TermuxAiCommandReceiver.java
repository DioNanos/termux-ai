package com.termux.app.terminal.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

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

        try {
            String cmd = intent.getStringExtra("cmd");
            JSONObject args = new JSONObject();
            if (intent.hasExtra("text")) args.put("text", intent.getStringExtra("text"));
            if (intent.hasExtra("title")) args.put("title", intent.getStringExtra("title"));
            if (intent.hasExtra("body")) args.put("body", intent.getStringExtra("body"));
            if (intent.hasExtra("path")) args.put("path", intent.getStringExtra("path"));
            if (intent.hasExtra("type")) args.put("type", intent.getStringExtra("type"));
            if (intent.hasExtra("duration_ms")) args.put("duration_ms", intent.getIntExtra("duration_ms", 80));

            String response = TermuxAiSocketServer.processRequest(context,
                new JSONObject().put("cmd", cmd == null ? "ping" : cmd).put("args", args).toString());
            setResultCode(new JSONObject(response).optBoolean("ok", false) ? 0 : 1);
            setResultData(response);
        } catch (Exception e) {
            setResultCode(1);
            try {
                setResultData(new JSONObject().put("ok", false).put("error", e.getMessage()).toString());
            } catch (Exception ignored) {
                setResultData("{\"ok\":false,\"error\":\"Unknown bridge error\"}");
            }
        }
    }
}
