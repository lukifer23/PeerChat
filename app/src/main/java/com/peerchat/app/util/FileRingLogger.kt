package com.peerchat.app.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream

class FileRingLogger(
    context: Context,
    name: String = "dev.log",
    private val maxBytes: Long = 1_048_576L,
) {
    private val file: File = File(context.filesDir, name).apply { parentFile?.mkdirs() }

    @Synchronized
    fun append(line: String) {
        val data = (line + "\n").toByteArray()
        FileOutputStream(file, true).use { it.write(data) }
        if (file.length() > maxBytes) truncate()
    }

    @Synchronized
    private fun truncate() {
        val bytes = file.readBytes()
        val keep = (maxBytes / 2).toInt().coerceAtMost(bytes.size)
        val start = bytes.size - keep
        val slice = bytes.copyOfRange(start, bytes.size)
        file.writeBytes(slice)
    }
}


