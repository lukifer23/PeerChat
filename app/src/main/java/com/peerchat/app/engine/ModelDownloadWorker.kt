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
        val tempFile = File.createTempFile("dl_", "tmp", applicationContext.cacheDir)

        try {
            downloadToFile(url, tempFile)
            if (isStopped) {
                tempFile.delete()
                return@withContext Result.failure()
            }
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
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("HTTP ${connection.responseCode}")
        }
        connection.inputStream.use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    if (isStopped) throw InterruptedException("Download cancelled")
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        connection.disconnect()
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_IS_DEFAULT = "is_default"
        const val KEY_OUTPUT_PATH = "output_path"
    }
}
