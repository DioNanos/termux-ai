package com.termux.app.terminal.ai;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.WindowManager;

/**
 * The visible AI run surface: the engine answers only while it is up. It keeps
 * the screen on and a wake lock held, and it closes itself once the queue has
 * been empty for a while (a safety timeout, not a delayed finish right after
 * the launch: the surface must be up for as long as a caller needs it).
 */
public class AiRunActivity extends Activity {

    /** How long the surface stays up with an empty queue before it closes itself. */
    static final long IDLE_FINISH_MS = 10 * 60_000L;
    private static final long IDLE_POLL_MS = 1_000L;

    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBusyAt;
    private boolean finishing;

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            if (finishing) return;
            if (AiCoreEngine.activeJobs() > 0) {
                lastBusyAt = now();
            } else if (now() - lastBusyAt >= IDLE_FINISH_MS) {
                finishing = true;
                finish();
                return;
            }
            handler.postDelayed(this, IDLE_POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The screen stays on while the surface is up: a job is never interrupted by screen-off.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lastBusyAt = now();
    }

    @Override protected void onResume() {
        super.onResume();
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termux-ai:AiRun");
        wakeLock.acquire();
        AiForegroundGate.activityResumed();
        handler.postDelayed(idleCheck, IDLE_POLL_MS);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(idleCheck);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    private long now() { return System.currentTimeMillis(); }
}
