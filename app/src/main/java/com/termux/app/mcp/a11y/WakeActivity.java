package com.termux.app.mcp.a11y;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * Transparent helper activity: turns the screen on and asks to dismiss a
 * non-secure keyguard, then finishes. Best-effort and no root — a secure lock
 * (PIN/pattern/biometric) still requires the user. Started by the
 * android-control {@code wake} tool.
 */
public final class WakeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                try { km.requestDismissKeyguard(this, null); } catch (Exception ignored) {}
            }
        }

        // Leave the screen on briefly, then get out of the way.
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1500);
    }
}
