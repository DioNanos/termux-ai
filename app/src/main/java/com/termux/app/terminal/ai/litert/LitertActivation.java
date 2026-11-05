package com.termux.app.terminal.ai.litert;

/**
 * The precision of the activations an engine computes in. {@code DEFAULT} passes nothing to the SDK, so the SDK
 * picks (fp16 on GPU, fp32 on CPU) and a request that does not ask is exactly what it was before. A request that
 * asks is passed as is: it is never changed, and a combination the model or backend cannot do fails in the SDK.
 */
public enum LitertActivation {
    DEFAULT("default"), FP16("fp16"), FP32("fp32");

    public final String wire;

    LitertActivation(String wire) { this.wire = wire; }

    /** Strict: only fp16 and fp32 can be asked for; absence is {@link #DEFAULT}. */
    public static LitertActivation parse(String value) throws LitertFailure {
        if (value == null) return DEFAULT;
        for (LitertActivation activation : values()) {
            if (activation != DEFAULT && activation.wire.equals(value)) return activation;
        }
        throw LitertFailure.invalid("activation must be fp16 or fp32 (got: " + value + ")");
    }
}
