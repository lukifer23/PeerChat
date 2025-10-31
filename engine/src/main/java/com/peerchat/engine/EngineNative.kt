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

    external fun countTokens(text: String): Int

    external fun metrics(): String

    external fun detectModel(modelPath: String): String

    external fun stateCapture(): ByteArray

    external fun stateRestore(state: ByteArray): Boolean

    external fun stateClear(clearData: Boolean)

    // Zero-copy state operations using direct ByteBuffer
    external fun stateSize(): Int

    external fun stateCaptureInto(buffer: java.nio.ByteBuffer): Int

    external fun stateRestoreFrom(buffer: java.nio.ByteBuffer, length: Int): Boolean

    /**
     * Request abort of current generation operation.
     * Thread-safe and can be called from any thread.
     */
    external fun abort()
}
