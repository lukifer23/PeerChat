package com.peerchat.app.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return@withContext Result.failure()
        val markDefault = inputData.getBoolean(KEY_IS_DEFAULT, false)

        val modelsDir = File(applicationContext.filesDir, "models").apply { if (!exists()) mkdirs() }
        val destination = File(modelsDir, fileName)
        val tempFile = File(applicationContext.cacheDir, "$fileName.part")

        try {
            downloadToFile(url, tempFile)
            if (isStopped) {
                tempFile.delete()
                return@withContext Result.failure()
            }
            if (destination.exists()) destination.delete()
            tempFile.copyTo(destination, overwrite = true)
            tempFile.delete()

            val service = ModelManifestService(applicationContext)
            service.ensureManifestFor(destination.absolutePath, sourceUrl = url, isDefault = markDefault)

            Result.success(workDataOf(KEY_OUTPUT_PATH to destination.absolutePath))
        } catch (e: Exception) {
            destination.delete()
            tempFile.delete()
            Result.retry()
        }
    }

    private fun downloadToFile(urlString: String, outFile: File) {
        val url = URL(urlString)
        var connection: HttpURLConnection? = null
        try {
            val resume = outFile.exists() && outFile.length() > 0
            val existing = if (resume) outFile.length() else 0L
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                if (resume) setRequestProperty("Range", "bytes=$existing-")
                requestMethod = "GET"
            }
            val code = connection.responseCode
            if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("HTTP $code")
            }

            val remainingLength = connection.getHeaderFieldLong("Content-Length", -1L).coerceAtLeast(0L)
            val totalBytes = if (remainingLength > 0) existing + remainingLength else -1L

            connection.inputStream.use { input ->
                FileOutputStream(outFile, resume).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = existing
                    var lastReport = System.nanoTime()
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (isStopped) throw InterruptedException("Download cancelled")
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.nanoTime()
                        // progress reporting removed for stability
                        if (totalBytes > 0 && now - lastReport > 200_000_000L) {
                            lastReport = now
                        }
                    }
                    output.fd.sync()
                    // final progress suppressed
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_IS_DEFAULT = "is_default"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_PROGRESS_DOWNLOADED = "downloaded"
        const val KEY_PROGRESS_TOTAL = "total"
    }
}
