package com.termux.shared.net.socket.local;

// Overlay stub for the MCP harness: keeps what the production code passes in so
// the stand-in manager can serve it. The production code only calls the constructor.
public final class LocalSocketRunConfig {
    public final String title;
    public final String socketPath;
    public final LocalSocketManagerClientBase client;

    public LocalSocketRunConfig(String title, String socketPath, LocalSocketManagerClientBase client) {
        this.title = title;
        this.socketPath = socketPath;
        this.client = client;
    }
}
