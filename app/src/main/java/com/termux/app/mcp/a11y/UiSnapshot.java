package com.termux.app.mcp.a11y;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure data model for an accessibility-tree snapshot exposed to the MCP client.
 *
 * <p>A snapshot groups a set of interactable {@link UiNode}s under a
 * {@code snapshotId} with a time-to-live. Node references ({@code node_ref}) are
 * only valid until the snapshot expires or the UI changes; the
 * AccessibilityService maps each ref back to a live node and fails stale refs
 * with {@code STALE_REF}.
 */
public final class UiSnapshot {

    /** A single interactable UI element. */
    public static final class UiNode {
        public final String ref;
        public final String role;
        public final String text;
        public final String id;
        public final String desc;
        public final String bounds;
        public final boolean clickable;
        public final boolean editable;
        public final boolean scrollable;

        public UiNode(String ref, String role, String text, String id, String desc,
                      String bounds, boolean clickable, boolean editable, boolean scrollable) {
            this.ref = ref;
            this.role = role;
            this.text = text;
            this.id = id;
            this.desc = desc;
            this.bounds = bounds;
            this.clickable = clickable;
            this.editable = editable;
            this.scrollable = scrollable;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("node_ref", ref);
                o.put("role", role == null ? "" : role);
                o.put("text", text == null ? JSONObject.NULL : text);
                o.put("id", id == null ? JSONObject.NULL : id);
                o.put("desc", desc == null ? JSONObject.NULL : desc);
                o.put("bounds", bounds == null ? "" : bounds);
                o.put("clickable", clickable);
                o.put("editable", editable);
                o.put("scrollable", scrollable);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    public final String snapshotId;
    public final long ttlMs;
    public final List<UiNode> nodes;

    public UiSnapshot(String snapshotId, long ttlMs, List<UiNode> nodes) {
        this.snapshotId = snapshotId;
        this.ttlMs = ttlMs;
        this.nodes = nodes == null ? new ArrayList<UiNode>() : nodes;
    }

    public static String newSnapshotId(int seed) {
        return "snap-" + seed;
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("snapshot_id", snapshotId);
            o.put("ttl_ms", ttlMs);
            JSONArray arr = new JSONArray();
            for (UiNode n : nodes) arr.put(n.toJson());
            o.put("nodes", arr);
        } catch (Exception ignored) {
        }
        return o.toString();
    }
}
