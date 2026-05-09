package com.termux.app.terminal.ai

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

object AICoreBackend {

    private const val TAG = "AICoreBackend"

    private val lastError = AtomicReference<String?>(null)
    private val streamScope = CoroutineScope(Dispatchers.Default)
    private val inflight = ConcurrentHashMap<String, Job>()

    @Volatile private var appContext: Context? = null
    @Volatile private var defaultModel: GenerativeModel? = null

    @JvmStatic
    fun isSdkSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @JvmStatic
    fun lastInitError(): String? = lastError.get()

    private fun storeContext(ctx: Context) {
        if (appContext == null) {
            appContext = ctx.applicationContext
        }
    }

    private fun getDefaultModel(): GenerativeModel {
        defaultModel?.let { return it }
        val model = Generation.getClient()
        defaultModel = model
        return model
    }

    private data class StatusResult(val available: Boolean, val status: Int, val error: String?)

    private fun checkStatus(): StatusResult {
        return try {
            defaultModel = null  // reset cache for fresh AICore status on every check
            val model = getDefaultModel()
            val status = runBlocking(Dispatchers.IO) { model.checkStatus() }
            when (status) {
                2 -> StatusResult(true, status, null)
                1 -> StatusResult(false, status, "model downloadable but not downloaded")
                3 -> StatusResult(false, status, "model downloading")
                else -> StatusResult(false, status, "unavailable (status=$status)")
            }
        } catch (t: Throwable) {
            StatusResult(false, -1, "${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    @JvmStatic
    fun isAvailable(context: Context, stage: Int, preference: Int): Boolean {
        // ModelConfig-based selection not supported; use default model
        return isAvailable(context)
    }

    @JvmStatic
    fun isAvailable(context: Context): Boolean {
        if (!isSdkSupported()) {
            lastError.set("Android < 12 (API ${Build.VERSION.SDK_INT})")
            return false
        }
        storeContext(context)
        val result = checkStatus()
        if (!result.available) {
            lastError.set(result.error)
        } else {
            lastError.set(null)
        }
        return result.available
    }

    @JvmStatic
    fun modelsInfo(context: Context): JSONArray {
        storeContext(context)
        val result = checkStatus()
        val obj = JSONObject()
            .put("model", "default")
            .put("status", statusName(result.status))
            .put("available", result.available)
        if (result.error != null) obj.put("error", result.error)
        return JSONArray().put(obj)
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
    ): JSONObject = generate(context, prompt, maxTokens, temperatureF)

    @JvmStatic
    @Throws(Exception::class)
    fun generate(
        context: Context,
        prompt: String,
        maxTokens: Int,
        temperatureF: Float
    ): JSONObject {
        if (!isSdkSupported()) throw IllegalStateException("Android < 12 not supported")
        storeContext(context)

        val model = getDefaultModel()
        val statusResult = checkStatus()
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

        val model = getDefaultModel()
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
    fun download(context: Context): JSONObject {
        if (!isSdkSupported()) throw IllegalStateException("Android < 12 not supported")
        storeContext(context)
        val model = getDefaultModel()
        return try {
            runBlocking(Dispatchers.IO) {
                model.download().collect { status ->
                    Log.i(TAG, "download: $status")
                }
            }
            val result = checkStatus()
            JSONObject()
                .put("ok", true)
                .put("status", statusName(result.status))
                .put("available", result.available)
        } catch (t: Throwable) {
            JSONObject()
                .put("ok", false)
                .put("error", "${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
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
        2 -> "AVAILABLE"
        1 -> "DOWNLOADABLE"
        3 -> "DOWNLOADING"
        else -> "UNAVAILABLE"
    }
}
