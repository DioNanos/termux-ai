package com.termux.app.terminal.ai.litert;

// Signature stand-in for the Kotlin LiteRtLmRuntime (the LiteRT-LM SDK
// wrapper). It satisfies the same constructor and interface contract the
// Java sources see; never executed.
public final class LiteRtLmRuntime implements LitertRuntime {
    public LiteRtLmRuntime(String nativeLibraryDir, String cachePath) {}

    @Override
    public Loaded load(String modelPath, LitertBackend backend, int contextTokens, LitertActivation activation)
            throws LitertFailure {
        throw new UnsupportedOperationException("signature stub");
    }
}
