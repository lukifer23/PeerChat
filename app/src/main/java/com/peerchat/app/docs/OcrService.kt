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
    suspend fun extractText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            resolver.openInputStream(uri)?.use { stream ->
                val imageBytes = stream.readBytes()
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: return@withContext ""
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                
                suspendCancellableCoroutine<String> { cont ->
                    recognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            val text = visionText.text ?: ""
                            cont.resume(text)
                        }
                        .addOnFailureListener { e ->
                            cont.resumeWithException(e)
                        }
                }
            } ?: ""
        } catch (e: Exception) {
            ""
        } finally {
            recognizer.close()
        }
    }
}

