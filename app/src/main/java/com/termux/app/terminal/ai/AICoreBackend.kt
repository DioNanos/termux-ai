package com.termux.app.terminal.ai

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.aicore.GenerateContentResponse
import com.google.ai.edge.aicore.GenerationConfig
import com.google.ai.edge.aicore.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * AICore Tier 1 backend wrapper. Bridges the Kotlin / coroutine API of
 * `com.google.ai.edge.aicore` into Java-friendly blocking calls used by
 * [TermuxAiSocketServer].
 *
 * All callsites must runtime-guard with `Build.VERSION.SDK_INT >= 31` and
 * `BuildConfig.AICORE_ENABLED`; the AAR declares minSdk 31 but the host app
 * runs from minSdk 21, so older devices report unavailable cleanly.
 */
object AICoreBackend {

    private const val TAG = "AICoreBackend"

    private val initLock = Any()
    private val lastError = AtomicReference<String?>(null)

    @Volatile private var model: GenerativeModel? = null
    private val streamScope = CoroutineScope(Dispatchers.Default)
    private val inflight = ConcurrentHashMap<String, Job>()

    @JvmStatic
    fun isSdkSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @JvmStatic
    fun lastInitError(): String? = lastError.get()

    /**
     * Cheap class-load + zero-arg construction probe. Does not call inference.
     * Returns a short status string for log surface.
     */
    @JvmStatic
    fun probe(context: Context): String {
        if (!isSdkSupported()) return "skip:sdk<31"
        return try {
            val cfg = GenerationConfig.Builder().apply {
                temperature = 0.2f
                maxOutputTokens = 8
            }.build()
            val m = GenerativeModel(generationConfig = cfg)
            "ok:class=${m.javaClass.simpleName}"
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed", t)
            "err:${t.javaClass.simpleName}:${t.message ?: ""}"
        }
    }

    /**
     * Lazy single-model. We re-initialise if the cached model fails. Idempotent.
     */
    @JvmStatic
    fun isAvailable(): Boolean {
        if (!isSdkSupported()) {
            lastError.set("Android < 12 (API ${Build.VERSION.SDK_INT})")
            return false
        }
        return ensureModel() != null
    }

    private fun ensureModel(): GenerativeModel? {
        model?.let { return it }
        synchronized(initLock) {
            model?.let { return it }
            return try {
                val cfg = GenerationConfig.Builder().apply {
                    temperature = 0.2f
                    maxOutputTokens = 256
                }.build()
                val m = GenerativeModel(generationConfig = cfg)
                model = m
                lastError.set(null)
                m
            } catch (t: Throwable) {
                Log.w(TAG, "init failed", t)
                lastError.set("${t.javaClass.simpleName}: ${t.message ?: ""}")
                null
            }
        }
    }

    /**
     * Blocking single-shot generate. Returns the concatenated text.
     * Throws if AICore is not available.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun generate(prompt: String, maxTokens: Int, temperatureF: Float): String {
        if (!isSdkSupported()) throw IllegalStateException("Android < 12 not supported")
        val cfg = GenerationConfig.Builder().apply {
            temperature = temperatureF
            this.maxOutputTokens = maxTokens
        }.build()
        // Use a short-lived model instance keyed to this config — cheap and
        // avoids races on shared mutable config.
        val m = GenerativeModel(generationConfig = cfg)
        return runBlocking(Dispatchers.Default) {
            val resp: GenerateContentResponse = m.generateContent(prompt)
            resp.text ?: ""
        }
    }

    /**
     * Asynchronous streaming generate. Invokes [callback] on a background
     * thread for each delta and a final terminal event. Returns immediately
     * with the assigned request id.
     */
    @JvmStatic
    fun stream(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        temperatureF: Float,
        callback: AICoreStreamCallback
    ): String {
        if (!isSdkSupported()) {
            callback.onError(IllegalStateException("Android < 12 not supported"))
            return requestId
        }
        val cfg = GenerationConfig.Builder().apply {
            temperature = temperatureF
            this.maxOutputTokens = maxTokens
        }.build()
        val m = GenerativeModel(generationConfig = cfg)
        val job = streamScope.launch {
            try {
                var totalChars = 0
                m.generateContentStream(prompt).collect { chunk ->
                    val t = chunk.text
                    if (!t.isNullOrEmpty()) {
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
}
