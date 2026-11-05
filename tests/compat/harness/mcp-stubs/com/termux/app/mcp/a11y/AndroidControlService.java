package com.termux.app.mcp.a11y;

// Overlay stub for the audit harness: only the surface McpSocketServer calls.
// Tests subclass it to return synthetic payloads (UI text sentinels, errors).
public class AndroidControlService {
    public static volatile AndroidControlService INSTANCE;

    public static String statusJson() { return "{\"enabled\":" + (INSTANCE != null) + "}"; }
    public String snapshotJson() { return "{\"snapshot_id\":\"snap-0\",\"nodes\":[]}"; }
    public String tapJson(String nodeRef) { return "{\"ok\":true}"; }
    public String typeJson(String nodeRef, String text) { return "{\"ok\":true}"; }
    public String launchAppJson(String pkg) { return "{\"ok\":true}"; }
    public String wakeJson() { return "{\"ok\":true}"; }
}
