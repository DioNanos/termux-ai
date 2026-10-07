package com.termux.app.terminal.ai

import com.google.mlkit.genai.common.GenAiException

/** Turns the SDK's {@link GenAiException} into an {@link AiCoreFailure} with name, code and retry delay. */
object GenAiErrors {

    @JvmStatic
    fun name(code: Int): String = when (code) {
        GenAiException.ErrorCode.UNKNOWN -> "UNKNOWN"
        GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR -> "REQUEST_PROCESSING_ERROR"
        GenAiException.ErrorCode.CANCELLED -> "CANCELLED"
        GenAiException.ErrorCode.NOT_AVAILABLE -> "NOT_AVAILABLE"
        GenAiException.ErrorCode.BUSY -> "BUSY"
        GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR -> "RESPONSE_PROCESSING_ERROR"
        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> "REQUEST_TOO_LARGE"
        GenAiException.ErrorCode.REQUEST_TOO_SMALL -> "REQUEST_TOO_SMALL"
        GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR -> "RESPONSE_GENERATION_ERROR"
        GenAiException.ErrorCode.NOT_SUPPORTED -> "NOT_SUPPORTED"
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "PER_APP_BATTERY_USE_QUOTA_EXCEEDED"
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "BACKGROUND_USE_BLOCKED"
        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "NOT_ENOUGH_DISK_SPACE"
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "NEEDS_SYSTEM_UPDATE"
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> "AICORE_INCOMPATIBLE"
        GenAiException.ErrorCode.INVALID_INPUT_IMAGE -> "INVALID_INPUT_IMAGE"
        GenAiException.ErrorCode.CACHE_PROCESSING_ERROR -> "CACHE_PROCESSING_ERROR"
        GenAiException.ErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR -> "STRUCTURED_OUTPUT_REQUEST_ERROR"
        GenAiException.ErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR -> "STRUCTURED_OUTPUT_RESPONSE_ERROR"
        GenAiException.ErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR -> "STRUCTURED_OUTPUT_MAX_TOKENS_ERROR"
        GenAiException.ErrorCode.AUDIO_BUFFER_OVERFLOW -> "AUDIO_BUFFER_OVERFLOW"
        else -> "ERROR_$code"
    }

    /** A GenAiException becomes an AiCoreFailure; anything else is returned as it is. */
    @JvmStatic
    fun map(t: Throwable): Throwable {
        if (t !is GenAiException) return t
        val reported = try { t.retryDelay?.toMillis() ?: -1L } catch (e: Throwable) { -1L }
        // The SDK reports a zero delay when it has none to give.
        val delay = if (reported > 0L) reported else -1L
        return AiCoreFailure(name(t.errorCode), t.errorCode, delay, t.message ?: name(t.errorCode), t)
    }
}
