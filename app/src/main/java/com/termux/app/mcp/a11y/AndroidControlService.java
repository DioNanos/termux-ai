package com.termux.app.mcp.a11y;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * The {@code android-control} AccessibilityService: reads the active-window
 * accessibility tree, hands out TTL-bound node refs, and performs tap / set-text
 * on those refs. No root, no adb. Backs the MCP {@code android-control} tools.
 *
 * <p>All public methods return a JSON string (the MCP tool payload). Errors use
 * the typed taxonomy: SERVICE_OFF, STALE_REF, NODE_NOT_FOUND, ACTION_UNSUPPORTED,
 * GESTURE_FAILED, NO_ACTIVE_WINDOW.
 */
public final class AndroidControlService extends AccessibilityService {

    public static volatile AndroidControlService INSTANCE;

    private static final long SNAPSHOT_TTL_MS = 8000L;
    private static final int MAX_NODES = 500;

    private final Map<String, AccessibilityNodeInfo> refMap = new HashMap<>();
    private int snapshotSeq = 0;
    private long snapshotExpiresAt = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (INSTANCE == this) INSTANCE = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* PoC: snapshot is pull-based */ }

    @Override
    public void onInterrupt() { }

    // ---- public API used by the MCP server (returns JSON strings) ----

    public static String statusJson() {
        JSONObject o = new JSONObject();
        try { o.put("enabled", INSTANCE != null); } catch (Exception ignored) {}
        return o.toString();
    }

    public synchronized String snapshotJson() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errJson("NO_ACTIVE_WINDOW");
        clearRefs();
        snapshotSeq++;
        String snapshotId = UiSnapshot.newSnapshotId(snapshotSeq);
        snapshotExpiresAt = System.currentTimeMillis() + SNAPSHOT_TTL_MS;

        List<UiSnapshot.UiNode> nodes = new ArrayList<>();
        Queue<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int i = 0;
        while (!q.isEmpty() && i < MAX_NODES) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            boolean interactable = n.isClickable() || n.isEditable() || n.isScrollable()
                || (n.getText() != null && n.getText().length() > 0);
            if (interactable) {
                String ref = "n" + i;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                nodes.add(new UiSnapshot.UiNode(
                    ref,
                    str(n.getClassName()),
                    str(n.getText()),
                    n.getViewIdResourceName(),
                    str(n.getContentDescription()),
                    "[" + b.left + "," + b.top + "][" + b.right + "," + b.bottom + "]",
                    n.isClickable(), n.isEditable(), n.isScrollable()));
                refMap.put(ref, n);
                i++;
            }
            for (int c = 0; c < n.getChildCount(); c++) {
                AccessibilityNodeInfo child = n.getChild(c);
                if (child != null) q.add(child);
            }
        }
        return new UiSnapshot(snapshotId, SNAPSHOT_TTL_MS, nodes).toJson();
    }

    public synchronized String tapJson(String ref) {
        AccessibilityNodeInfo node = resolve(ref);
        if (node == null) return refError(ref);
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return okJson();
        }
        // fallback: gesture tap at node centre (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            Path p = new Path();
            p.moveTo(b.centerX(), b.centerY());
            GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 50);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            boolean dispatched = dispatchGesture(gesture, null, null);
            return dispatched ? okJson() : errJson("GESTURE_FAILED");
        }
        return errJson("ACTION_UNSUPPORTED");
    }

    public synchronized String typeJson(String ref, String text) {
        AccessibilityNodeInfo node = resolve(ref);
        if (node == null) return refError(ref);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text == null ? "" : text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        return ok ? okJson() : errJson("ACTION_UNSUPPORTED");
    }

    public synchronized String launchAppJson(String pkg) {
        if (pkg == null || pkg.isEmpty()) return errJson("PACKAGE_NOT_FOUND");
        try {
            android.content.Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return errJson("PACKAGE_NOT_FOUND");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return okJson();
        } catch (Exception e) {
            return errJson("LAUNCH_FAILED");
        }
    }

    public synchronized String wakeJson() {
        try {
            android.content.Intent i = new android.content.Intent(this, WakeActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return okJson();
        } catch (Exception e) {
            return errJson("WAKE_FAILED");
        }
    }

    // ---- helpers ----

    private AccessibilityNodeInfo resolve(String ref) {
        if (System.currentTimeMillis() > snapshotExpiresAt) return null; // stale snapshot
        return refMap.get(ref);
    }

    private String refError(String ref) {
        if (System.currentTimeMillis() > snapshotExpiresAt) return errJson("STALE_REF");
        return errJson("NODE_NOT_FOUND");
    }

    private void clearRefs() {
        for (AccessibilityNodeInfo n : refMap.values()) {
            try { n.recycle(); } catch (Exception ignored) {}
        }
        refMap.clear();
    }

    private static String str(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }

    private static String okJson() {
        try { return new JSONObject().put("ok", true).toString(); }
        catch (Exception e) { return "{\"ok\":true}"; }
    }

    private static String errJson(String code) {
        try { return new JSONObject().put("ok", false).put("error", code).toString(); }
        catch (Exception e) { return "{\"ok\":false,\"error\":\"" + code + "\"}"; }
    }
}
