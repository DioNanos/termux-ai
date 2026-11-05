package com.termux.app.terminal.ai.litert

import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import java.io.File

/**
 * The LiteRT-LM SDK behind [LitertRuntime]. It lives in the `:litert` process only. One engine is opened on
 * exactly the backend that was asked for; nothing here retries on another one.
 */
class LiteRtLmRuntime(
    private val nativeLibraryDir: String,
    private val cacheDir: String,
) : LitertRuntime {

    override fun load(modelPath: String, backend: LitertBackend, contextTokens: Int, activation: LitertActivation): LitertRuntime.Loaded {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = sdkBackend(backend),
            maxNumTokens = contextTokens,
            cacheDir = cacheDir,
            // null leaves the choice to the SDK (fp16 on GPU): only an explicit request changes the precision.
            activationDataType = when (activation) {
                LitertActivation.DEFAULT -> null
                LitertActivation.FP16 -> ActivationDataType.FLOAT16
                LitertActivation.FP32 -> ActivationDataType.FLOAT32
            },
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

        override fun generate(
            chat: LitertChat, maxTokens: Int, temperature: Double, topK: Int, topP: Double, seed: Int,
            token: LitertCancelToken,
        ): LitertRuntime.Output {
            // The cancel belongs to the request's token, not to this engine: nothing is remembered here, so a cancel
            // can never reach the next request that runs on the same engine.
            if (token.isCancelled) throw cancelledFailure()
            val conversation = try {
                // The roles are kept: the system instruction and the history are the conversation's own, and only
                // the last message is sent. Nothing is flattened into one user turn.
                val history = chat.history.map { sdkMessage(it) }
                // The tools are only declared: the model may ask for one, but the app never runs it (automatic tool
                // calling is off). The caller runs the tool and sends the result back as a tool message.
                val tools = chat.tools.map { tool(DeclaredTool(it)) }
                val sampler = SamplerConfig(topK, topP, temperature, seed)
                engine.createConversation(
                    if (chat.system != null) {
                        ConversationConfig(
                            systemInstruction = Contents.of(chat.system),
                            initialMessages = history,
                            tools = tools,
                            samplerConfig = sampler,
                            automaticToolCalling = false,
                            maxOutputToken = maxTokens,
                        )
                    } else {
                        ConversationConfig(
                            initialMessages = history,
                            tools = tools,
                            samplerConfig = sampler,
                            automaticToolCalling = false,
                            maxOutputToken = maxTokens,
                        )
                    }
                )
            } catch (e: Exception) {
                throw failure(LitertErrorCode.GENERATION_FAILED, backend, "generate", e)
            }
            // Attached under the token's lock: a cancel that arrived meanwhile stops the conversation right away.
            token.onCancel { runCatching { conversation.cancelProcess() } }
            var output: LitertRuntime.Output? = null
            var problem: LitertFailure? = null
            try {
                if (token.isCancelled) throw cancelledFailure()
                val reply = conversation.sendMessage(sdkMessage(chat.last))
                if (token.isCancelled) throw cancelledFailure()
                val calls = reply.toolCalls.map { LitertChat.Call("", it.name, LitertJson.fromMap(it.arguments)) }
                output = LitertRuntime.Output(text(reply), if (calls.isEmpty()) "other" else "tool_calls", thinking(reply), calls)
            } catch (e: LitertFailure) {
                problem = e
            } catch (e: Exception) {
                problem = if (token.isCancelled) cancelledFailure() else failure(classify(e), backend, "generate", e)
            }
            // The end of the generation: no new stop can start, and a stop that is already running is waited for
            // (up to a declared limit) before the conversation is closed. If it never returns the conversation is
            // left open on purpose and CANCEL_TIMEOUT replaces the result.
            try {
                token.finish { runCatching { conversation.close() } }
            } catch (stuck: LitertFailure) {
                throw LitertFailure(stuck.code, backend.wire, stuck.phase, null, stuck.message, stuck)
            }
            if (problem != null) throw problem
            return output!!
        }

        override fun evidence(): String = mappedExecutorLibraries()

        // A close that fails is thrown, not swallowed: the runner records it, so a leak is something we can see.
        override fun close() {
            engine.close()
        }

        private fun cancelledFailure() =
            LitertFailure(LitertErrorCode.CANCELLED, backend.wire, "generate", null, "the generation was cancelled", null)
    }

    /** A tool the model may call. Never executed here: the caller runs it, so [execute] is not reachable. */
    private class DeclaredTool(private val description: String) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = description

        override fun execute(paramsJsonString: String): String =
            throw IllegalStateException("tools are run by the caller, not by the engine")
    }

    private companion object {
        const val MAX_CPU_THREADS = 4

        /** One message of the chat as the SDK's own: user, model (with the calls it made) or a tool result. */
        fun sdkMessage(m: LitertChat.Msg): Message = when (m.role) {
            LitertChat.Role.USER -> Message.user(m.text)
            LitertChat.Role.MODEL -> Message.model(
                if (m.text.isEmpty()) Contents.of(emptyList<Content>()) else Contents.of(m.text),
                m.calls.map { ToolCall(it.name, LitertJson.toMap(it.arguments)) },
            )
            LitertChat.Role.TOOL -> Message.tool(Contents.of(Content.ToolResponse(m.toolName, m.text)))
        }

        /** The reply text: every text part, in order. Thinking channels are not part of the answer. */
        fun text(message: Message): String =
            message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

        /** What the model wrote on its channels (its thinking), one channel per line; empty when there is none. */
        fun thinking(message: Message): String =
            message.channels.values.filter { it.isNotEmpty() }.joinToString("\n")

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
