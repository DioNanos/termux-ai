package com.termux.app.terminal.ai;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Brings the AI run surface up before an aicore job: the engine answers only
 * while the app is visibly in the foreground, so the command first brings
 * {@link AiRunActivity} up and waits until it is resumed. Starting an activity
 * from the background is the system's business: it works only with the
 * "Display over other apps" permission, so without it nothing is attempted and
 * the answer is the typed FOREGROUND_REQUIRED failure.
 */
public final class AiForegroundGate {

    /** How long the gate waits for the run surface to be up before it gives up. */
    public static final long RESUME_TIMEOUT_MS = 10_000L;

    private static final AtomicReference<CountDownLatch> RESUMED = new AtomicReference<>(null);

    private AiForegroundGate() {}

    /** Only {@link AiRunActivity} calls this: it opens the latch the gate waits on. */
    static void activityResumed() {
        CountDownLatch latch = RESUMED.get();
        if (latch != null) latch.countDown();
    }

    /** Whether a command may start the run surface while the app is in the background. */
    public static boolean canDrawOverlays(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /** The settings screen for the one-time permission, to be offered to the person. */
    public static Intent permissionIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.getPackageName()));
    }

    /** Brings the run surface up and waits for it, or fails with FOREGROUND_REQUIRED. */
    public static void ensureResumed(Context context) throws AiCoreFailure {
        if (!canDrawOverlays(context)) {
            throw foregroundRequired(
                "AICore answers only with the app in the foreground, and this command cannot bring the run surface "
                    + "up from the background without the Display over other apps permission: "
                    + AiForegroundPolicy.REMEDY_FOREGROUND);
        }
        CountDownLatch latch = new CountDownLatch(1);
        RESUMED.set(latch);
        try {
            Intent intent = new Intent(context, AiRunActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
            } catch (RuntimeException e) {
                throw foregroundRequired("the AI run surface could not be brought up: " + e.getMessage()
                    + "; " + AiForegroundPolicy.REMEDY_FOREGROUND);
            }
            try {
                if (!latch.await(RESUME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw foregroundRequired("the AI run surface did not come up in time; "
                        + AiForegroundPolicy.REMEDY_FOREGROUND);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw foregroundRequired("interrupted while the AI run surface was coming up; "
                    + AiForegroundPolicy.REMEDY_FOREGROUND);
            }
        } finally {
            RESUMED.compareAndSet(latch, null);
        }
    }

    private static AiCoreFailure foregroundRequired(String message) {
        return new AiCoreFailure(AiForegroundPolicy.FOREGROUND_REQUIRED,
            AiForegroundPolicy.FOREGROUND_REQUIRED_CODE, -1L, message, null);
    }
}
