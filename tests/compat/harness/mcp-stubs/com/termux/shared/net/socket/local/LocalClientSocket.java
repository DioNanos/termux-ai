package com.termux.shared.net.socket.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

// Overlay stub for the harness: a client socket over plain streams, with an optional
// close action so a real transport can be shut when the production code closes it.
public final class LocalClientSocket {
    private final InputStream in;
    private final OutputStream out;
    private final Runnable onClose;
    public LocalClientSocket(InputStream in, OutputStream out) { this(in, out, null); }
    public LocalClientSocket(InputStream in, OutputStream out, Runnable onClose) {
        this.in = in;
        this.out = out;
        this.onClose = onClose;
    }
    public InputStream getInputStream() { return in; }
    public InputStreamReader getInputStreamReader() { return new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8); }
    public OutputStream getOutputStream() { return out; }
    public com.termux.shared.errors.Error closeClientSocket(boolean async) {
        if (onClose != null) onClose.run();
        return null;
    }
}
