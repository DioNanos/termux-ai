package com.termux.app.terminal.ai

import android.os.Build

/** The app-wide AICore engine, built on ML Kit GenAI Prompt. */
object AICoreBackend {

    @Volatile private var instance: AiCoreEngine? = null

    @JvmStatic
    fun engine(): AiCoreEngine =
        instance ?: synchronized(this) {
            instance ?: AiCoreEngine(
                MlKitGenAiPort(),
                { Build.VERSION.SDK_INT },
                { System.currentTimeMillis() }
            ).also { instance = it }
        }
}
