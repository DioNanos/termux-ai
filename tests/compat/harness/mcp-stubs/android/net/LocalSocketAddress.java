package android.net;

// Overlay stub for the MCP harness: carries the path, like the platform class.
public class LocalSocketAddress {
    private final String name;
    public LocalSocketAddress(String name, Namespace ns) { this.name = name; }
    public String getName() { return name; }
    public enum Namespace { FILESYSTEM }
}
