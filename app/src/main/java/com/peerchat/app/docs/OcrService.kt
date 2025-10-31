package com.peerchat.app.docs

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrService {
    sealed class OcrResult {
        data class Success(val text: String) : OcrResult()
        data class Failure(val error: String) : OcrResult()
    }

    suspend fun extractText(context: Context, uri: Uri): OcrResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            resolver.openInputStream(uri)?.use { stream ->
                val imageBytes = stream.readBytes()
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: return@withContext OcrResult.Failure("Failed to decode image from URI")
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                
                suspendCancellableCoroutine<OcrResult> { cont ->
                    recognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            val text = visionText.text ?: ""
                            cont.resume(OcrResult.Success(text))
                        }
                        .addOnFailureListener { e ->
                            cont.resume(OcrResult.Failure("OCR failed: ${e.message}"))
                        }
                }
            } ?: OcrResult.Failure("Failed to open image file")
        } catch (e: Exception) {
            OcrResult.Failure("OCR error: ${e.message}")
        } finally {
            recognizer.close()
        }
    }
}

