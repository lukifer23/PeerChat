package com.peerchat.app.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

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

        // Clean up any stale partial downloads older than 1 hour
        try {
            applicationContext.cacheDir.listFiles { file ->
                file.name.endsWith(".part") && file.lastModified() < System.currentTimeMillis() - 3600000
            }?.forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.w("ModelDownloadWorker", "Failed to clean up partial downloads", e)
        }

        try {
            // Download (with resume support)
            downloadToFile(url, tempFile)
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

    private suspend fun downloadToFile(urlString: String, outFile: File) {
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
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (isStopped) throw InterruptedException("Download cancelled")
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.nanoTime()
                        if (totalBytes > 0 && now - lastReport > 200_000_000L) {
                            lastReport = now
                            setProgress(workDataOf(
                                KEY_PROGRESS_DOWNLOADED to downloaded,
                                KEY_PROGRESS_TOTAL to totalBytes
                            ))
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

    private fun computeSha256(file: File): String {
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

    // Security validation functions
    private fun isValidHttpsUrl(url: String): Boolean {
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

    private fun isValidFilename(filename: String): Boolean {
        return filename.isNotBlank() &&
               filename.length <= 255 && // Prevent extremely long filenames
               filename.matches(Regex("^[a-zA-Z0-9._-]+$")) && // Only safe characters
               !filename.startsWith(".") && // No hidden files
               !filename.contains("..") && // No directory traversal
               filename.lowercase().endsWith(".gguf") // Only GGUF model files
    }

    private fun isValidSha256(hash: String): Boolean {
        return hash.matches(Regex("^[a-fA-F0-9]{64}$"))
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
    }
}
