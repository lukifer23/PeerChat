package com.peerchat.app.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ModelMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val manifestService = ModelManifestService(applicationContext)
            val manifests = manifestService.list()
            var refreshed = 0
            var removed = 0

            manifests.forEach { manifest ->
                val modelFile = File(manifest.filePath)
                if (!modelFile.exists()) {
                    manifestService.deleteManifest(manifest, removeFile = false)
                    removed += 1
                } else {
                    manifestService.ensureManifestFor(
                        path = manifest.filePath,
                        modelMetaJson = manifest.metadataJson,
                        sourceUrl = manifest.sourceUrl,
                        isDefault = manifest.isDefault
                    )
                    refreshed += 1
                }
            }

            ModelFileUtils.cleanupPartialDownloads(applicationContext)

            Result.success(
                workDataOf(
                    KEY_REFRESHED to refreshed,
                    KEY_REMOVED to removed
                )
            )
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_REFRESHED = "refreshed"
        const val KEY_REMOVED = "removed"
    }
}
