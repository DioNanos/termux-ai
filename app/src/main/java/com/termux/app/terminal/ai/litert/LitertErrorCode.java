package com.termux.app.terminal.ai.litert;

/** The stable error codes of the {@code litert.*} commands. Names and numbers never change. */
public enum LitertErrorCode {
    INVALID_ARGUMENT(1001),
    MODEL_NOT_FOUND(1002),
    MODEL_INVALID(1003),
    RUNTIME_TOO_OLD(1004),
    ABI_UNSUPPORTED(1005),
    MODEL_BACKEND_UNSUPPORTED(1006),
    NPU_TARGET_UNAVAILABLE(1007),
    NATIVE_LIBRARY_UNAVAILABLE(1008),
    BACKEND_INIT_FAILED(1009),
    BACKEND_UNVERIFIABLE(1010),
    BUSY(1011),
    CONTEXT_EXCEEDED(1012),
    CANCELLED(1013),
    DEADLINE_EXCEEDED(1014),
    MODEL_WORKER_DIED(1015),
    GENERATION_FAILED(1016);

    public final int number;

    LitertErrorCode(int number) { this.number = number; }

    /** The code for a wire name, or {@code GENERATION_FAILED} for a name this build does not know. */
    public static LitertErrorCode fromName(String name) {
        for (LitertErrorCode code : values()) if (code.name().equals(name)) return code;
        return GENERATION_FAILED;
    }
}
