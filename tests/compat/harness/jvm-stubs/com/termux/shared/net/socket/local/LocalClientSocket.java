package com.termux.shared.net.socket.local;

// Signature stub.
public final class LocalClientSocket {
    public static final class MutableInt {
        public int value;
        public MutableInt(int value) { this.value = value; }
        public MutableInt() {}
    }
    public com.termux.shared.errors.Error read(byte[] buffer, MutableInt count) { return null; }
    public com.termux.shared.errors.Error sendDataToOutputStream(String data, boolean async) { return null; }
    public void closeClientSocket(boolean async) {}
}
