package com.termux.app.terminal.ai.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The LiteRT-LM SDK behind [LitertRuntime]. It lives in the `:litert` process only. One engine is opened on
 * exactly the backend that was asked for; nothing here retries on another one.
 */
class LiteRtLmRuntime(
    private val nativeLibraryDir: String,
    private val cacheDir: String,
) : LitertRuntime {

    override fun load(modelPath: String, backend: LitertBackend, contextTokens: Int): LitertRuntime.Loaded {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = sdkBackend(backend),
            maxNumTokens = contextTokens,
            cacheDir = cacheDir,
        )
        val engine = Engine(config)
        try {
            engine.initialize()
        } catch (e: UnsatisfiedLinkError) {
            runCatching { engine.close() }
            throw failure(LitertErrorCode.NATIVE_LIBRARY_UNAVAILABLE, backend, "init", e)
        } catch (e: Exception) {
            runCatching { engine.close() }
            throw failure(LitertErrorCode.BACKEND_INIT_FAILED, backend, "init", e)
        }
        return Loaded(engine, backend)
    }

    private fun sdkBackend(backend: LitertBackend): Backend = when (backend) {
        LitertBackend.CPU -> Backend.CPU(threadCount = cpuThreads())
        LitertBackend.GPU -> Backend.GPU()
        LitertBackend.NPU -> Backend.NPU(nativeLibraryDir)
    }

    /** A fixed, small thread count: the model must not take every core of the phone. */
    private fun cpuThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_CPU_THREADS)

    private class Loaded(private val engine: Engine, private val backend: LitertBackend) : LitertRuntime.Loaded {
        private val active = AtomicReference<Conversation?>(null)
        @Volatile private var cancelled = false

        override fun generate(
            prompt: String, maxTokens: Int, temperature: Double, topK: Int, topP: Double, seed: Int,
        ): LitertRuntime.Output {
            cancelled = false
            val conversation = try {
                engine.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK, topP, temperature, seed),
                        automaticToolCalling = false,
                        maxOutputToken = maxTokens,
                    )
                )
            } catch (e: Exception) {
                throw failure(LitertErrorCode.GENERATION_FAILED, backend, "generate", e)
            }
            active.set(conversation)
            try {
                val reply = conversation.sendMessage(prompt)
                if (cancelled) throw cancelledFailure()
                return LitertRuntime.Output(text(reply), "other")
            } catch (e: LitertFailure) {
                throw e
            } catch (e: Exception) {
                if (cancelled) throw cancelledFailure()
                throw failure(classify(e), backend, "generate", e)
            } finally {
                active.set(null)
                // The conversation is closed before the engine can be: close() runs only when nothing is generating.
                runCatching { conversation.close() }
            }
        }

        override fun cancel() {
            cancelled = true
            active.get()?.let { runCatching { it.cancelProcess() } }
        }

        override fun evidence(): String = mappedExecutorLibraries()

        override fun close() {
            runCatching { engine.close() }
        }

        private fun cancelledFailure() =
            LitertFailure(LitertErrorCode.CANCELLED, backend.wire, "generate", null, "the generation was cancelled", null)
    }

    private companion object {
        const val MAX_CPU_THREADS = 4

        /** The reply text: every text part, in order. Thinking channels are not part of the answer. */
        fun text(message: Message): String =
            message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

        /** The SDK reports no limit error of its own: the wording is the only signal, so it is matched narrowly. */
        fun classify(e: Exception): LitertErrorCode {
            val text = e.message.orEmpty().lowercase()
            return if ("context" in text && ("exceed" in text || "too long" in text || "overflow" in text)) {
                LitertErrorCode.CONTEXT_EXCEEDED
            } else {
                LitertErrorCode.GENERATION_FAILED
            }
        }

        fun failure(code: LitertErrorCode, backend: LitertBackend, phase: String, e: Throwable) =
            LitertFailure(code, backend.wire, phase, null, "${e.javaClass.simpleName}: ${e.message}", e)

        /**
         * The accelerator libraries that are mapped into this process. Mapped is not executing: this is evidence
         * for the person reading the answer, and `backend_verified` stays false for gpu and npu.
         */
        fun mappedExecutorLibraries(): String {
            val names = runCatching {
                File("/proc/self/maps").useLines { lines ->
                    lines.mapNotNull { it.substringAfterLast('/', "").takeIf(String::isNotEmpty) }
                        .filter { "Dispatch" in it || "edgetpu" in it || "gpu" in it.lowercase() || "opencl" in it.lowercase() }
                        .toSortedSet()
                }
            }.getOrElse { return "the mapped libraries could not be read: ${it.message}" }
            return if (names.isEmpty()) "no accelerator library is mapped into the :litert process"
            else "mapped in the :litert process: ${names.joinToString(", ")}"
        }
    }
}
