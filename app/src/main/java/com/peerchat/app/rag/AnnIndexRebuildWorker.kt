package com.peerchat.app.rag

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.peerchat.app.BuildConfig
import com.peerchat.app.util.Logger
import com.peerchat.data.db.PeerDatabaseProvider
import com.peerchat.rag.RagService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnnIndexRebuildWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = PeerDatabaseProvider.get(applicationContext, BuildConfig.DEBUG)
            val snapshot = RagService.rebuildAnnIndex(db)
            AnnIndexStorage.save(applicationContext, snapshot)
            Logger.i("AnnIndexRebuildWorker: rebuild completed")
            Result.success()
        } catch (t: Throwable) {
            Logger.e(
                "AnnIndexRebuildWorker: rebuild failed",
                mapOf("error" to t.message),
                t
            )
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (t.message ?: "unknown error")))
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        const val KEY_ERROR = "error"
    }
}
