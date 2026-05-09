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
 * ML Kit aicore:0.0.1-exp02 requires an Android [Context] on every
 * [GenerationConfig.Builder] — failing with `IllegalStateException: Context is
 * required` otherwise. All public methods that build a config accept [Context]
 * and store the application context lazily on first call.
 *
 * All callsites must runtime-guard with `Build.VERSION.SDK_INT >= 31` and
 * `BuildConfig.AICORE_ENABLED`; the AAR declares minSdk 31 but the host app
 * runs from minSdk 21, so older devices report unavailable cleanly.
 */
object AICoreBackend {

    private const val TAG = "AICoreBackend"

    private val initLock = Any()
    private val lastError = AtomicReference<String?>(null)

    @Volatile private var appContext: Context? = null
    @Volatile private var model: GenerativeModel? = null
    private val streamScope = CoroutineScope(Dispatchers.Default)
    private val inflight = ConcurrentHashMap<String, Job>()

    private fun storeContext(ctx: Context) {
        if (appContext == null) {
            appContext = ctx.applicationContext
        }
    }

    private fun requireContext(): Context {
        return appContext ?: throw IllegalStateException(
            "AICoreBackend not initialised — call init(context) or pass context to any public method first"
        )
    }

    @JvmStatic
    fun isSdkSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @JvmStatic
    fun lastInitError(): String? = lastError.get()

    /**
     * Must be called once with an application context before any other method.
     * Safe to call multiple times; only the first context is retained.
     */
    @JvmStatic
    fun init(ctx: Context) {
        storeContext(ctx)
    }

    /**
     * Cheap class-load probe. Does not call inference.
     * Returns a short status string for log surface.
     */
    @JvmStatic
    fun probe(context: Context): String {
        if (!isSdkSupported()) return "skip:sdk<31"
        storeContext(context)
        return try {
            val cfg = buildConfig(context, 0.2f, 8)
            val m = GenerativeModel(generationConfig = cfg)
            "ok:class=${m.javaClass.simpleName}"
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed", t)
            "err:${t.javaClass.simpleName}:${t.message ?: ""}"
        }
    }

    /**
     * Lazy single-model availability check. Idempotent.
     */
    @JvmStatic
    fun isAvailable(context: Context): Boolean {
        if (!isSdkSupported()) {
            lastError.set("Android < 12 (API ${Build.VERSION.SDK_INT})")
            return false
        }
        storeContext(context)
        return ensureModel() != null
    }

    private fun ensureModel(): GenerativeModel? {
        model?.let { return it }
        synchronized(initLock) {
            model?.let { return it }
            val ctx = try { requireContext() } catch (e: Exception) {
                lastError.set(e.message)
                return null
            }
            return try {
                val cfg = buildConfig(ctx, 0.2f, 256)
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
    fun generate(context: Context, prompt: String, maxTokens: Int, temperatureF: Float): String {
        if (!isSdkSupported()) throw IllegalStateException("Android < 12 not supported")
        storeContext(context)
        val cfg = buildConfig(context, temperatureF, maxTokens)
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
        context: Context,
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
        storeContext(context)
        val cfg = buildConfig(context, temperatureF, maxTokens)
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

    private fun buildConfig(ctx: Context, temperature: Float, maxTokens: Int): GenerationConfig {
        return GenerationConfig.Builder().apply {
            context = ctx
            this.temperature = temperature
            this.maxOutputTokens = maxTokens
        }.build()
    }
}
