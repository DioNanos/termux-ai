package com.termux.app.terminal.ai;

/**
 * What the app needs from the on-device model service, one client per
 * {@link ModelSelection}: the same client answers status, download and
 * generation for a given selection, and no selection ever resets another.
 * The real implementation wraps ML Kit GenAI Prompt; tests use a fake that
 * records what it is asked.
 */
public interface GenAiPort {

    /** FeatureStatus: UNAVAILABLE 0, DOWNLOADABLE 1, DOWNLOADING 2, AVAILABLE 3. */
    int checkStatus(ModelSelection selection) throws Exception;

    String baseModelName(ModelSelection selection) throws Exception;

    Capabilities capabilities(ModelSelection selection) throws Exception;

    /** Blocks until the download ends; throws on failure. */
    void download(ModelSelection selection, ProgressListener listener) throws Exception;

    GenResult generate(ModelSelection selection, GenParams params) throws Exception;

    GenResult generateStream(ModelSelection selection, GenParams params, ChunkListener listener) throws Exception;

    interface ProgressListener { void onBytes(long totalBytesDownloaded); }

    interface ChunkListener { void onChunk(String text); }

    /** Capabilities declared by the SDK for a client; null means "not reported". */
    final class Capabilities {
        public final Integer tokenLimit;
        public final Boolean systemPrompt;
        public final Boolean structuredOutput;
        public final Boolean thinking;
        public final Boolean caching;

        public Capabilities(Integer tokenLimit, Boolean systemPrompt, Boolean structuredOutput,
                            Boolean thinking, Boolean caching) {
            this.tokenLimit = tokenLimit;
            this.systemPrompt = systemPrompt;
            this.structuredOutput = structuredOutput;
            this.thinking = thinking;
            this.caching = caching;
        }
    }
}
