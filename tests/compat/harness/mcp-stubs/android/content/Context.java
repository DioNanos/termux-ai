package android.content;

// Overlay stub for the MCP harness: only what the installer and the MCP server
// use, with a working asset manager.
public class Context {
    public Context getApplicationContext() { return this; }
    public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); }
}
