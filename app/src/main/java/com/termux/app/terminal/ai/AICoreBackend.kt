package com.termux.app.terminal.ai

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

object AICoreBackend {

    private const val TAG = "AICoreBackend"
    private const val STATUS_UNAVAILABLE = 0
    private const val STATUS_DOWNLOADABLE = 1
    private const val STATUS_AVAILABLE = 2
    private const val STATUS_DOWNLOADING = 3

    private val lastError = AtomicReference<String?>(null)
    private val modelCache = ConcurrentHashMap<String, GenerativeModel>()
    private val streamScope = CoroutineScope(Dispatchers.Default)
    private val inflight = ConcurrentHashMap<String, Job>()

    @Volatile private var appContext: Context? = null

    @JvmStatic
    fun isSdkSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @JvmStatic
    fun lastInitError(): String? = lastError.get()

    private fun storeContext(ctx: Context) {
        if (appContext == null) {
            appContext = ctx.applicationContext
        }
    }

    private fun modelKey(stage: Int, preference: Int): String = "${stage}:${preference}"

    private fun getModel(stage: Int, pref: Int): GenerativeModel {
        val key = modelKey(stage, pref)
        modelCache[key]?.let { return it }
        val mc = ModelConfig.Builder().apply {
            releaseStage = stage
            preference = pref
        }.build()
        val config = GenerationConfig.Builder().apply {
            modelConfig = mc
        }.build()
        val model = Generation.getClient(config)
        modelCache[key] = model
        return model
    }

    private data class StatusResult(val available: Boolean, val status: Int, val error: String?)

    private fun checkModelStatus(stage: Int, preference: Int): StatusResult {
        return try {
            val model = getModel(stage, preference)
            val status = runBlocking(Dispatchers.IO) { model.checkStatus() }
            when (status) {
                STATUS_AVAILABLE -> StatusResult(true, status, null)
                STATUS_DOWNLOADABLE -> StatusResult(false, status, "model downloadable but not downloaded")
                STATUS_DOWNLOADING -> StatusResult(false, status, "model downloading")
                else -> StatusResult(false, status, "unavailable (status=$status)")
            }
        } catch (t: Throwable) {
            StatusResult(false, -1, "${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    @JvmStatic
    fun isAvailable(context: Context, stage: Int, preference: Int): Boolean {
        if (!isSdkSupported()) {
            lastError.set("Android < 12 (API ${Build.VERSION.SDK_INT})")
            return false
        }
        storeContext(context)
        val result = checkModelStatus(stage, preference)
        if (!result.available) {
            lastError.set(result.error)
        } else {
            lastError.set(null)
        }
        return result.available
    }

    @JvmStatic
    fun isAvailable(context: Context): Boolean =
        isAvailable(context, ModelReleaseStage.STABLE, ModelPreference.FULL)

    @JvmStatic
    fun modelsInfo(context: Context): JSONArray {
        storeContext(context)
        val variants = arrayOf(
            Triple(ModelReleaseStage.STABLE, ModelPreference.FULL, "stable" to "full"),
            Triple(ModelReleaseStage.PREVIEW, ModelPreference.FULL, "preview" to "full"),
            Triple(ModelReleaseStage.PREVIEW, ModelPreference.FAST, "preview" to "fast"),
        )
        val arr = JSONArray()
        for ((stage, pref, names) in variants) {
            val result = checkModelStatus(stage, pref)
            val obj = JSONObject()
                .put("stage", names.first)
                .put("preference", names.second)
                .put("status", statusName(result.status))
                .put("available", result.available)
            if (result.error != null) obj.put("error", result.error)
            arr.put(obj)
        }
        return arr
    }

    @JvmStatic
    @Throws(Exception::class)
    fun generate(
        context: Context,
        prompt: String,
        maxTokens: Int,
        temperatureF: Float,
        stage: Int,
        preference: Int
    ): JSONObject {
        if (!isSdkSupported()) throw IllegalStateException("Android < 12 not supported")
        storeContext(context)

        val model = getModel(stage, preference)
        val statusResult = checkModelStatus(stage, preference)
        if (!statusResult.available) {
            throw IllegalStateException("Model not available: ${statusName(statusResult.status)}")
        }

        val start = System.currentTimeMillis()
        val resp: GenerateContentResponse = runBlocking(Dispatchers.IO) {
            model.generateContent(prompt)
        }
        val latencyMs = System.currentTimeMillis() - start
        val text = resp.candidates.firstOrNull()?.text ?: ""

        return JSONObject()
            .put("text", text)
            .put("finish_reason", "stop")
            .put("usage", JSONObject()
                .put("input_tokens_approx", prompt.length / 4)
                .put("output_tokens_approx", text.length / 4))
            .put("latency_ms", latencyMs)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun generate(
        context: Context,
        prompt: String,
        maxTokens: Int,
        temperatureF: Float
    ): String {
        val result = generate(context, prompt, maxTokens, temperatureF,
            ModelReleaseStage.STABLE, ModelPreference.FULL)
        return result.optString("text", "")
    }

    @JvmStatic
    fun stream(
        context: Context,
        requestId: String,
        prompt: String,
        maxTokens: Int,
        temperatureF: Float,
        stage: Int,
        preference: Int,
        callback: AICoreStreamCallback
    ): String {
        if (!isSdkSupported()) {
            callback.onError(IllegalStateException("Android < 12 not supported"))
            return requestId
        }
        storeContext(context)

        val model = getModel(stage, preference)
        val job = streamScope.launch {
            try {
                var totalChars = 0
                model.generateContentStream(prompt).collect { chunk ->
                    val t = chunk.candidates.firstOrNull()?.text
                    if (t != null && t.isNotEmpty()) {
                        callback.onChunk(t)
                        totalChars += t.length
                    }
                }
                callback.onComplete("stop", totalChars)
            } catch (t: Throwable) {
                Log.w(TAG, "stream failed", t)
                callback.onError(t)
            } finally {
                inflight.remove(requestId)
            }
        }
        inflight[requestId] = job
        return requestId
    }

    @JvmStatic
    fun cancel(requestId: String): Boolean {
        val job = inflight.remove(requestId) ?: return false
        return try {
            job.cancel()
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun statusName(status: Int): String = when (status) {
        STATUS_AVAILABLE -> "AVAILABLE"
        STATUS_DOWNLOADABLE -> "DOWNLOADABLE"
        STATUS_DOWNLOADING -> "DOWNLOADING"
        else -> "UNAVAILABLE"
    }
}
