package com.peerchat.app.rag

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

object AnnIndexWorkManager {
    private const val UNIQUE_WORK_NAME = "ann_rebuild_work"

    fun scheduleRebuild(context: Context, delay: Duration = Duration.ofSeconds(15)) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val request = OneTimeWorkRequestBuilder<AnnIndexRebuildWorker>()
            .setInitialDelay(delay)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
    }
}
