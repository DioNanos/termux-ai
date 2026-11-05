package com.termux.app.terminal.ai.litert;

/** What the runner may do to the process it lives in. Tests count the calls; the service really ends the process. */
public interface LitertProcessControl {
    /** Ends this process so the system starts a clean one on the next bind. Does not return in the real service. */
    void recycle();

    /** A control that does nothing, for a runner that is not tied to a process. */
    LitertProcessControl NONE = () -> { };
}
