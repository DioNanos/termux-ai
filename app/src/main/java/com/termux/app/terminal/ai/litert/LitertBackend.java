package com.termux.app.terminal.ai.litert;

/** The accelerator a request asks for. It is always explicit: nothing is inferred from the file name. */
public enum LitertBackend {
    CPU("cpu"), GPU("gpu"), NPU("npu");

    public final String wire;

    LitertBackend(String wire) { this.wire = wire; }

    /** Strict: a missing or unknown value is an error, never a default. */
    public static LitertBackend parse(String value) throws LitertFailure {
        if (value == null) throw LitertFailure.invalid("backend is required (cpu, gpu or npu)");
        for (LitertBackend backend : values()) if (backend.wire.equals(value)) return backend;
        throw LitertFailure.invalid("backend must be cpu, gpu or npu (got: " + value + ")");
    }
}
