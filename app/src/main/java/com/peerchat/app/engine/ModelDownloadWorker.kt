package com.peerchat.app.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.ForegroundInfo
import com.peerchat.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import android.os.Build
import android.content.pm.ServiceInfo

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val notificationId: Int by lazy {
        val base = inputData.getString(KEY_FILE_NAME)?.hashCode() ?: 0
        base xor id.hashCode()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure(workDataOf("error" to "Missing download URL"))
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return@withContext Result.failure(workDataOf("error" to "Missing file name"))
        val markDefault = inputData.getBoolean(KEY_IS_DEFAULT, false)
        val expectedSha256 = inputData.getString(KEY_SHA256)?.takeIf { it.isNotBlank() }

        // Enhanced input validation for security
        if (!isValidHttpsUrl(url)) {
            return@withContext Result.failure(workDataOf("error" to "Invalid or insecure URL - only HTTPS URLs from trusted domains are allowed"))
        }
        if (!isValidFilename(fileName)) {
            return@withContext Result.failure(workDataOf("error" to "Invalid filename - contains unsafe characters or path traversal attempts"))
        }
        if (expectedSha256 != null && !isValidSha256(expectedSha256)) {
            return@withContext Result.failure(workDataOf("error" to "Invalid SHA-256 hash format"))
        }

        val modelsDir = File(applicationContext.filesDir, "models").apply { if (!exists()) mkdirs() }
        val destination = File(modelsDir, fileName)
        val tempFile = File(applicationContext.cacheDir, "$fileName.part")

        updateForeground(fileName, downloaded = 0L, total = -1L)

        ModelFileUtils.cleanupPartialDownloads(applicationContext)

        try {
            // Download (with resume support)
            downloadToFile(url, tempFile) { downloaded, total ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_DOWNLOADED to downloaded,
                        KEY_PROGRESS_TOTAL to total
                    )
                )
                updateForeground(fileName, downloaded, total)
            }
            if (isStopped) {
                tempFile.delete()
                return@withContext Result.failure()
            }
            
            // Verify SHA-256 if provided
            if (expectedSha256 != null) {
                val computedSha256 = computeSha256(tempFile)
                if (computedSha256.lowercase() != expectedSha256.lowercase()) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        workDataOf("error" to "SHA-256 verification failed: expected $expectedSha256, got $computedSha256")
                    )
                }
            }
            
            // Move to final destination
            if (destination.exists()) destination.delete()
            tempFile.copyTo(destination, overwrite = true)
            tempFile.delete()

            val service = ModelManifestService(applicationContext)
            service.ensureManifestFor(destination.absolutePath, sourceUrl = url, isDefault = markDefault)

            android.util.Log.i("ModelDownloadWorker", "Successfully downloaded $fileName (${destination.length()} bytes)")
            Result.success(workDataOf(KEY_OUTPUT_PATH to destination.absolutePath))
        } catch (e: Exception) {
            destination.delete()
            tempFile.delete()
            // Log the error for debugging
            android.util.Log.e("ModelDownloadWorker", "Download failed for $fileName: ${e.message}", e)
            // Retry on network/transient errors, fail permanently on verification errors
            when {
                e is IllegalStateException && e.message?.contains("SHA-256") == true ->
                    Result.failure(workDataOf("error" to "Verification failed: ${e.message}"))
                e is SecurityException || e is IllegalArgumentException ->
                    Result.failure(workDataOf("error" to "Invalid download: ${e.message}"))
                runAttemptCount >= 3 ->
                    Result.failure(workDataOf("error" to "Download failed after ${runAttemptCount} attempts: ${e.message}"))
                else ->
                    Result.retry()
            }
        }
    }

    private suspend fun updateForeground(fileName: String, downloaded: Long, total: Long) {
        val info = ModelDownloadNotifications.buildForegroundInfo(
            context = applicationContext,
            notificationId = notificationId,
            fileName = fileName,
            downloaded = downloaded,
            total = total
        )
        setForeground(info)
    }

    private suspend fun downloadToFile(
        urlString: String,
        outFile: File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ) {
        val url = URL(urlString)
        var connection: HttpURLConnection? = null
        try {
            val resume = outFile.exists() && outFile.length() > 0
            val existing = if (resume) outFile.length() else 0L
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000  // Increased for slow connections
                readTimeout = 300_000   // Increased for large downloads (5 minutes)
                if (resume) setRequestProperty("Range", "bytes=$existing-")
                requestMethod = "GET"

                // Security headers
                setRequestProperty("User-Agent", "PeerChat-Android/1.0")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity") // Disable compression for integrity checks
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("X-Requested-With", "PeerChat")

                // Enable TLS 1.2+ only (disable older protocols)
                if (this is javax.net.ssl.HttpsURLConnection) {
                    sslSocketFactory = javax.net.ssl.SSLContext.getInstance("TLSv1.2").apply {
                        init(null, null, java.security.SecureRandom())
                    }.socketFactory
                }
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
                    onProgress(downloaded, totalBytes)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (isStopped) throw InterruptedException("Download cancelled")
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.nanoTime()
                        val shouldReport = totalBytes <= 0 || now - lastReport > 200_000_000L
                        if (shouldReport) {
                            lastReport = now
                            onProgress(downloaded, totalBytes)
                        }
                    }
                    output.fd.sync()
                    onProgress(downloaded, totalBytes)
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
        const val KEY_SHA256 = "sha256"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_PROGRESS_DOWNLOADED = "downloaded"
        const val KEY_PROGRESS_TOTAL = "total"
        private const val DEFAULT_BUFFER_SIZE = 8192

        @JvmStatic
        @VisibleForTesting
        internal fun computeSha256(file: File): String {
            val buffer = ByteArray(8192)
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        @JvmStatic
        @VisibleForTesting
        internal fun isValidHttpsUrl(url: String): Boolean {
            return try {
                val uri = java.net.URI(url)
                uri.scheme == "https" &&
                    uri.host?.isNotBlank() == true &&
                    // Only allow known trusted domains for model downloads
                    uri.host in listOf("huggingface.co", "cdn-lfs.huggingface.co", "github.com", "raw.githubusercontent.com") &&
                    uri.path?.let { path ->
                        // Prevent directory traversal in URL path
                        !path.contains("..") && !path.contains("\\")
                    } ?: false
            } catch (e: Exception) {
                false
            }
        }

        @JvmStatic
        @VisibleForTesting
        internal fun isValidFilename(filename: String): Boolean {
            return filename.isNotBlank() &&
                filename.length <= 255 &&
                filename.matches(Regex("^[a-zA-Z0-9._-]+$")) &&
                !filename.startsWith(".") &&
                !filename.contains("..") &&
                filename.lowercase().endsWith(".gguf")
        }

        @JvmStatic
        @VisibleForTesting
        internal fun isValidSha256(hash: String): Boolean {
            return hash.matches(Regex("^[a-fA-F0-9]{64}$"))
        }
    }
}

private object ModelDownloadNotifications {
    private const val CHANNEL_ID = "model_downloads"

    fun buildForegroundInfo(
        context: Context,
        notificationId: Int,
        fileName: String,
        downloaded: Long,
        total: Long
    ): ForegroundInfo {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_model_download_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

        val isIndeterminate = total <= 0L || downloaded < 0L
        val percent = if (!isIndeterminate && total > 0L) {
            ((downloaded * 100L) / total).coerceIn(0, 100).toInt()
        } else {
            0
        }

        val contentText = if (isIndeterminate) {
            context.getString(R.string.notification_model_download_preparing, fileName)
        } else {
            context.getString(R.string.notification_model_download_progress, fileName, percent)
        }

        builder.setContentText(contentText)
        builder.setProgress(100, percent, isIndeterminate)

        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_model_download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_model_download_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
