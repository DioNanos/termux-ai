package android.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;

// Overlay stub for the MCP harness: the PUBLIC surface of the platform class
// (same signatures as the signature stub) over a real JDK unix-domain channel,
// so the production shim can talk to a real socket on the JVM.
public class LocalSocket {
    private SocketChannel channel;
    public LocalSocket() {}
    public void connect(LocalSocketAddress address) throws IOException {
        channel = SocketChannel.open(UnixDomainSocketAddress.of(address.getName()));
    }
    public void setSoTimeout(int ms) throws IOException {}
    public OutputStream getOutputStream() throws IOException { return mcpharness.ChannelStreams.out(channel); }
    public InputStream getInputStream() throws IOException { return mcpharness.ChannelStreams.in(channel); }
    public void shutdownOutput() throws IOException { channel.shutdownOutput(); }
    public void close() throws IOException { if (channel != null) channel.close(); }
}
