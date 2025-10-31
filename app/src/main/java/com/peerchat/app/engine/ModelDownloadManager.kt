package com.peerchat.app.engine

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object ModelDownloadManager {
    private const val WORK_PREFIX = "model_download_"

    fun enqueue(context: Context, model: DefaultModel) {
        val workName = workName(model)
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_URL to model.downloadUrl,
                    ModelDownloadWorker.KEY_FILE_NAME to model.suggestedFileName,
                    ModelDownloadWorker.KEY_IS_DEFAULT to model.isDefault,
                    ModelDownloadWorker.KEY_SHA256 to (model.sha256 ?: ""),
                )
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
    }

    fun observe(context: Context, model: DefaultModel): Flow<List<WorkInfo>> {
        val workManager = WorkManager.getInstance(context)
        return workManager.getWorkInfosForUniqueWorkLiveData(workName(model)).asFlow()
    }

    fun workName(model: DefaultModel): String = WORK_PREFIX + model.id
}
