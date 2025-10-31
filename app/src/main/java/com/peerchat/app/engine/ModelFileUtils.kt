package com.peerchat.app.engine

import android.content.Context
import java.io.File

internal object ModelFileUtils {
    private const val DEFAULT_PART_EXTENSION = ".part"

    fun cleanupPartialDownloads(context: Context, maxAgeMs: Long = 3_600_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        context.cacheDir
            ?.listFiles { file -> file.name.endsWith(DEFAULT_PART_EXTENSION) }
            ?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    runCatching { file.delete() }
                }
            }
    }
}
