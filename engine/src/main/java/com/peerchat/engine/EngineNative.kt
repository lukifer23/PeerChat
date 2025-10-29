package com.peerchat.engine

object EngineNative {
    init {
        System.loadLibrary("engine")
    }

    external fun init()

    external fun loadModel(
        modelPath: String,
        nThreads: Int,
        nCtx: Int,
        nGpuLayers: Int,
        useVulkan: Boolean
    ): Boolean

    external fun unload()

    external fun generate(
        prompt: String,
        systemPrompt: String?,
        template: String?,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        stop: Array<String>
    ): String

    external fun generateStream(
        prompt: String,
        systemPrompt: String?,
        template: String?,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        stop: Array<String>,
        callback: TokenCallback
    )

    external fun embed(texts: Array<String>): Array<FloatArray>

    external fun metrics(): String

    external fun detectModel(modelPath: String): String

    external fun stateCapture(): ByteArray

    external fun stateRestore(state: ByteArray): Boolean

    external fun stateClear(clearData: Boolean)
}
