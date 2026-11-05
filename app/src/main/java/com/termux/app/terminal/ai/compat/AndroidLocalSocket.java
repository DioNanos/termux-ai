package com.termux.app.terminal.ai.compat;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Normal Android adapter of the {@link LocalSocketCompatTransport.CompatLocalSocket}
 * seam: a 1:1 delegation to the platform class. It is the only
 * production user of android.net.LocalSocket in the compat client; keeping it
 * separate lets the transport loop be driven on the JVM through controlled
 * seam implementations without any test-only symbol on a platform class.
 */
final class AndroidLocalSocket implements LocalSocketCompatTransport.CompatLocalSocket {

    private final LocalSocket socket = new LocalSocket();

    @Override
    public void connect(String path) throws IOException {
        socket.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    @Override
    public void shutdownOutput() throws IOException {
        socket.shutdownOutput();
    }

    @Override
    public void setSoTimeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
