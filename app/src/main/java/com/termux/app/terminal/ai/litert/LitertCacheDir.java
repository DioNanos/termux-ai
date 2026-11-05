package com.termux.app.terminal.ai.litert;

import java.io.File;

/** The SDK's compile cache directory: made here, because the SDK refuses to start without it. */
final class LitertCacheDir {
    private LitertCacheDir() {}

    /**
     * Makes the directory (and its parents) if it is missing.
     *
     * @throws LitertFailure {@code CACHE_DIRECTORY_UNAVAILABLE} naming the path, when it cannot be a writable directory
     */
    static File ensure(File dir) throws LitertFailure {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw unavailable(dir, dir.exists() ? "it exists and is not a directory" : "it could not be created");
        }
        if (!dir.canWrite()) throw unavailable(dir, "it is not writable");
        return dir;
    }

    /** A runtime that answers every load with this failure: the service stays up and says why nothing can start. */
    static LitertRuntime refusing(LitertFailure failure) {
        return (modelPath, backend, contextTokens, activation) -> { throw failure; };
    }

    private static LitertFailure unavailable(File dir, String why) {
        return new LitertFailure(LitertErrorCode.CACHE_DIRECTORY_UNAVAILABLE, null, "init", null,
            "the LiteRT cache directory " + dir.getPath() + " is unusable: " + why, null);
    }
}
