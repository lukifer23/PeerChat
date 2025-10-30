package com.peerchat.engine

import java.nio.ByteBuffer

object EngineBridge {
    fun captureStateDirect(): ByteBuffer? {
        EngineRuntime.ensureInitialized()
        val size = EngineNative.stateSize()
        if (size <= 0) return null
        val buffer = ByteBuffer.allocateDirect(size)
        val written = EngineNative.stateCaptureInto(buffer)
        if (written <= 0) return null
        buffer.limit(written)
        buffer.position(0)
        return buffer
    }

    fun restoreStateDirect(buffer: ByteBuffer): Boolean {
        if (!buffer.isDirect) return false
        EngineRuntime.ensureInitialized()
        val length = if (buffer.hasArray()) buffer.remaining() else buffer.limit()
        return EngineNative.stateRestoreFrom(buffer, length)
    }
}


