package com.termux.app.terminal.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * ML Kit GenAI Prompt behind {@link GenAiPort}. One client per model
 * selection: status, download and generation of a selection share it, and the
 * SDK constants (never numbers written by hand) pick the model.
 */
class MlKitGenAiPort : GenAiPort {

    /** One client per selection, built once even when several requests arrive together. */
    private val clients = ModelClientCache<ModelSelection, GenerativeModel>(
        { selection -> Generation.getClient(configFor(selection)) },
        { model -> model.close() }
    )

    /**
     * Runs [block] on the client of [selection]. After a failure that client may be stale: it is dropped (and closed)
     * so the next call builds a new one, unless another request has already replaced it.
     */
    private inline fun <T> guarded(selection: ModelSelection, block: (GenerativeModel) -> T): T {
        var model: GenerativeModel? = null
        try {
            model = clients.get(selection)
            return block(model)
        } catch (t: Throwable) {
            if (model != null) clients.evict(selection, model)
            throw GenAiErrors.map(t)
        }
    }

    override fun checkStatus(selection: ModelSelection): Int = guarded(selection) { model ->
        runBlocking(Dispatchers.IO) { model.checkStatus() }
    }

    override fun baseModelName(selection: ModelSelection): String = guarded(selection) { model ->
        runBlocking(Dispatchers.IO) { model.getBaseModelName() }
    }

    override fun capabilities(selection: ModelSelection): GenAiPort.Capabilities = guarded(selection) { model ->
        runBlocking(Dispatchers.IO) {
            GenAiPort.Capabilities(
                runCatching { model.getTokenLimit() }.getOrNull(),
                runCatching { model.isSystemPromptAvailable() }.getOrNull(),
                runCatching { model.isStructuredOutputFeatureAvailable() }.getOrNull(),
                runCatching { model.isThinkingModeAvailable() }.getOrNull(),
                runCatching { model.isCachingFeatureAvailable() }.getOrNull()
            )
        }
    }

    override fun download(selection: ModelSelection, listener: GenAiPort.ProgressListener) {
        guarded(selection) { model ->
            runBlocking(Dispatchers.IO) {
                model.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadProgress -> listener.onBytes(status.totalBytesDownloaded)
                        is DownloadStatus.DownloadFailed -> throw status.e
                        else -> {}
                    }
                }
            }
        }
    }

    override fun generate(selection: ModelSelection, params: GenParams): GenResult = guarded(selection) { model ->
        val request = requestFor(params)
        val response: GenerateContentResponse = runBlocking(Dispatchers.IO) { model.generateContent(request) }
        resultOf(response)
    }

    override fun generateStream(
        selection: ModelSelection,
        params: GenParams,
        listener: GenAiPort.ChunkListener
    ): GenResult = guarded(selection) { model ->
        val request = requestFor(params)
        val text = StringBuilder()
        var finish = "other"
        runBlocking(Dispatchers.IO) {
            model.generateContentStream(request).collect { chunk ->
                val candidate = chunk.candidates.firstOrNull()
                val piece = candidate?.text
                if (piece != null && piece.isNotEmpty()) {
                    text.append(piece)
                    listener.onChunk(piece)
                }
                if (candidate?.finishReason != null) finish = finishName(candidate.finishReason)
            }
        }
        GenResult(text.toString(), finish)
    }

    companion object {
        /** The SDK's own constants pick the model: never numbers written by hand. */
        @JvmStatic
        fun releaseStageOf(stage: ModelSelection.Stage): Int = when (stage) {
            ModelSelection.Stage.STABLE -> ModelReleaseStage.STABLE
            ModelSelection.Stage.PREVIEW -> ModelReleaseStage.PREVIEW
        }

        @JvmStatic
        fun preferenceOf(preference: ModelSelection.Preference): Int = when (preference) {
            ModelSelection.Preference.FULL -> ModelPreference.FULL
            ModelSelection.Preference.FAST -> ModelPreference.FAST
        }

        @JvmStatic
        fun configFor(selection: ModelSelection): GenerationConfig {
            val modelConfig = ModelConfig.Builder().apply {
                releaseStage = releaseStageOf(selection.stage)
                preference = preferenceOf(selection.preference)
            }.build()
            return GenerationConfig.Builder().apply {
                this.modelConfig = modelConfig
            }.build()
        }

        @JvmStatic
        fun requestFor(params: GenParams): GenerateContentRequest =
            GenerateContentRequest.Builder(TextPart(params.prompt)).apply {
                temperature = params.temperature
                maxOutputTokens = params.maxTokens
                if (params.topK != null) topK = params.topK
            }.build()

        @JvmStatic
        fun finishName(reason: Int?): String = when (reason) {
            Candidate.FinishReason.STOP -> "stop"
            Candidate.FinishReason.MAX_TOKENS -> "max_tokens"
            else -> "other"
        }

        @JvmStatic
        fun resultOf(response: GenerateContentResponse): GenResult {
            val candidate = response.candidates.firstOrNull()
            return GenResult(candidate?.text ?: "", finishName(candidate?.finishReason))
        }
    }
}
