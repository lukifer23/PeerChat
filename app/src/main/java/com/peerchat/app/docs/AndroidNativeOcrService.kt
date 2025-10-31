package com.peerchat.app.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.peerchat.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import com.google.android.gms.tasks.Tasks

/**
 * Native Android OCR service with multiple fallback strategies
 * Uses ML Kit as primary, with potential for Tesseract or other OCR engines as fallback
 */
class AndroidNativeOcrService(private val context: Context) {

    data class OcrTextResult(
        val text: String,
        val confidence: Float,
        val method: String,
        val language: String = "en"
    )

    sealed class OcrResult {
        data class Success(val ocrResult: AndroidNativeOcrService.OcrTextResult) : OcrResult()
        data class Failure(val error: String) : OcrResult()
    }

    // ML Kit text recognizer (primary)
    private val mlKitRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Extract text from image using Android native OCR capabilities
     */
    suspend fun extractText(uri: Uri): OcrResult = withContext(Dispatchers.IO) {
        try {
            // Try ML Kit first (most accurate)
            val mlKitResult = tryExtractWithMlKit(uri)
            if (mlKitResult != null) {
                return@withContext OcrResult.Success(mlKitResult)
            }

            // Fallback to basic image processing (placeholder for Tesseract/other)
            val fallbackResult = tryExtractWithFallback(uri)
            if (fallbackResult != null) {
                return@withContext OcrResult.Success(fallbackResult)
            }

            OcrResult.Failure("All OCR methods failed")

        } catch (e: Exception) {
            Logger.e("AndroidNativeOcrService: OCR extraction failed",
                mapOf("error" to e.message), e)
            OcrResult.Failure("OCR failed: ${e.message}")
        }
    }

    /**
     * Extract text using Google ML Kit (primary method)
     */
    private suspend fun tryExtractWithMlKit(uri: Uri): OcrTextResult? {
        return try {
            val bitmap = loadBitmapFromUri(uri) ?: return null
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            val visionText = withContext(Dispatchers.IO) {
                Tasks.await(mlKitRecognizer.process(inputImage))
            }

            // ML Kit doesn't provide confidence scores for text blocks, use a default high confidence
            val averageConfidence = 0.9f

            val extractedText = visionText.text

            Logger.i("AndroidNativeOcrService: ML Kit extraction successful", mapOf(
                "textLength" to extractedText.length,
                "confidence" to averageConfidence,
                "blocks" to visionText.textBlocks.size
            ))

            OcrTextResult(
                text = extractedText,
                confidence = averageConfidence,
                method = "mlkit",
                language = "en" // ML Kit default is English
            )

        } catch (e: Exception) {
            Logger.w("AndroidNativeOcrService: ML Kit extraction failed",
                mapOf("error" to e.message), e)
            null
        }
    }

    /**
     * Fallback OCR method (placeholder for Tesseract or other OCR engines)
     * Currently returns null to indicate no fallback available
     */
    private suspend fun tryExtractWithFallback(uri: Uri): OcrTextResult? {
        // TODO: Implement Tesseract OCR as fallback
        // For now, we don't have a secondary OCR engine available
        // This could be extended to use Tesseract OCR library

        Logger.i("AndroidNativeOcrService: fallback OCR not implemented yet")
        return null
    }

    /**
     * Load bitmap from URI with memory-efficient decoding
     */
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return null

            // Decode with options to reduce memory usage
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // Calculate sample size to reduce memory usage
            val maxDimension = 2048 // Max 2048px on any dimension
            val sampleSize = calculateInSampleSize(options, maxDimension, maxDimension)

            val decodeStream = context.contentResolver.openInputStream(uri) ?: return null
            val finalOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            BitmapFactory.decodeStream(decodeStream, null, finalOptions).also {
                decodeStream.close()
            }

        } catch (e: Exception) {
            Logger.w("AndroidNativeOcrService: failed to load bitmap",
                mapOf("error" to e.message), e)
            null
        }
    }

    /**
     * Calculate inSampleSize for BitmapFactory to reduce memory usage
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * Check if this service can handle the given URI
     */
    fun canHandle(uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            mimeType?.startsWith("image/") == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get supported languages for OCR
     */
    fun getSupportedLanguages(): List<String> {
        return listOf("en") // ML Kit default supports English
    }

    /**
     * Clean up resources
     */
    fun close() {
        try {
            mlKitRecognizer.close()
        } catch (e: Exception) {
            Logger.w("AndroidNativeOcrService: error closing ML Kit recognizer",
                mapOf("error" to e.message), e)
        }
    }

    companion object {
        private const val TAG = "AndroidNativeOcrService"
    }
}
