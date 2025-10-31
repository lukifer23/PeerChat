package com.peerchat.app.engine

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object ModelDownloadManager {
    private const val WORK_PREFIX = "model_download_"
    private const val MAINTENANCE_WORK_NAME = "model_manifest_maintenance"

    fun enqueue(context: Context, model: DefaultModel) {
        val workName = workName(model)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false) // Allow downloads even on low battery
            .setRequiresStorageNotLow(true) // Require sufficient storage
            .build()
            
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_URL to model.downloadUrl,
                    ModelDownloadWorker.KEY_FILE_NAME to model.suggestedFileName,
                    ModelDownloadWorker.KEY_IS_DEFAULT to model.isDefault,
                    ModelDownloadWorker.KEY_SHA256 to (model.sha256 ?: ""),
                )
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                10000L, // 10 seconds minimum backoff
                TimeUnit.MILLISECONDS
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

    fun scheduleMaintenance(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ModelMaintenanceWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                10000L, // 10 seconds minimum backoff
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                MAINTENANCE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
}
