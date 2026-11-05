package android.net;

// Signature stub: the PUBLIC surface of the platform class only —
// no test-only hooks. The transport loop runs against the seam interface
// (LocalSocketCompatTransport.CompatLocalSocket + AndroidLocalSocket), so no
// test needs a symbol beyond the real API surface.
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class LocalSocket {
    public LocalSocket() {}
    public void connect(LocalSocketAddress address) throws IOException {}
    public void setSoTimeout(int ms) throws IOException {}
    public OutputStream getOutputStream() throws IOException { return null; }
    public InputStream getInputStream() throws IOException { return null; }
    public void shutdownOutput() throws IOException {}
    public void close() throws IOException {}
}
