package com.termux.shared.net.socket.local;

// Signature stub: the two overridable hooks the app's client
// overrides; anything else the real base demands is out of this check's
// scope because the app code would not compile otherwise.
public abstract class LocalSocketManagerClientBase {
    protected abstract String getLogTag();
    public abstract void onClientAccepted(LocalSocketManager manager, LocalClientSocket clientSocket);
}
