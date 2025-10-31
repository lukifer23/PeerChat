package com.peerchat.app.engine

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.peerchat.app.data.OperationResult
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.util.Logger
import com.peerchat.app.rag.AnnIndexWorkManager
import com.peerchat.app.docs.OcrService
import com.peerchat.app.docs.AndroidNativePdfService
import com.peerchat.app.docs.AndroidNativeOcrService
import com.peerchat.data.db.Document
import com.peerchat.rag.RagService
import com.peerchat.engine.EngineRuntime
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.text.Charsets
import kotlin.time.Duration.Companion.seconds

/**
 * Service for managing document operations including import, indexing, and deletion.
 * Handles text extraction from various document formats with comprehensive error recovery.
 */
class DocumentService(
    private val context: Context,
    private val repository: PeerChatRepository
) {
    // Android native processing services
    private val androidPdfService = AndroidNativePdfService(context)
    private val androidOcrService = AndroidNativeOcrService(context)
    
    companion object {
        private const val EXTRACTION_TIMEOUT_SECONDS = 60L
        private const val MAX_EXTRACTION_RETRIES = 2
    }

    /**
     * Import a document from a URI and index it for RAG.
     */
    suspend fun importDocument(uri: Uri): OperationResult<Document> = withContext(Dispatchers.IO) {
        try {
            val engineStatus = EngineRuntime.status.value
            if (engineStatus !is EngineRuntime.EngineStatus.Loaded) {
                return@withContext OperationResult.Failure("Load a model before importing documents")
            }

            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = resolveDisplayName(resolver, uri) ?: "Document"
            val textResult = extractText(resolver, uri, mime)
            val text = when (textResult) {
                is OcrService.OcrResult.Success -> textResult.text
                is OcrService.OcrResult.Failure -> {
                    return@withContext OperationResult.Failure(
                        if (mime.startsWith("image/")) {
                            textResult.error
                        } else {
                            "Text extraction failed: ${textResult.error}"
                        }
                    )
                }
            }

            if (text.isBlank()) {
                return@withContext OperationResult.Failure("No text content found in document")
            }

            val metadata = JSONObject().apply {
                put("sourceMime", mime)
                if (mime.startsWith("image/")) {
                    put("extraction", "ocr")
                }
            }.toString()

            val document = Document(
                uri = uri.toString(),
                title = name,
                hash = sha256(text),
                mime = mime,
                textBytes = text.toByteArray(Charsets.UTF_8),
                createdAt = System.currentTimeMillis(),
                metaJson = metadata
            )

            val id = repository.upsertDocument(document)
            val indexedDocument = document.copy(id = id)

            // Index the document for RAG
            RagService.indexDocument(repository.database(), indexedDocument, text)
            AnnIndexWorkManager.scheduleRebuild(context)

            val message = if (mime.startsWith("image/")) {
                "Image processed and indexed"
            } else {
                "Document indexed successfully"
            }
            OperationResult.Success(indexedDocument, message)
        } catch (e: Exception) {
            OperationResult.Failure("Import error: ${e.message}")
        }
    }

    /**
     * Delete a document and its associated chunks.
     */
    suspend fun deleteDocument(documentId: Long): OperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            repository.deleteDocument(documentId)
            AnnIndexWorkManager.scheduleRebuild(context)
            OperationResult.Success(Unit, "Document deleted")
        } catch (e: Exception) {
            OperationResult.Failure("Delete error: ${e.message}")
        }
    }

    /**
     * Re-index a document with updated content.
     */
    suspend fun reindexDocument(document: Document): OperationResult<Document> = withContext(Dispatchers.IO) {
        try {
            val engineStatus = EngineRuntime.status.value
            if (engineStatus !is EngineRuntime.EngineStatus.Loaded) {
                return@withContext OperationResult.Failure("Load a model before re-indexing documents")
            }
            val text = String(document.textBytes)
            RagService.indexDocument(repository.database(), document, text)
            AnnIndexWorkManager.scheduleRebuild(context)
            OperationResult.Success(document, "Document re-indexed")
        } catch (e: Exception) {
            OperationResult.Failure("Re-index error: ${e.message}")
        }
    }

    /**
     * Extract text content from various document types.
     */
    private suspend fun extractText(resolver: ContentResolver, uri: Uri, mime: String): OcrService.OcrResult {
        return when {
            mime == "application/pdf" -> {
                extractPdfTextWithFallback(uri)
            }
            mime.startsWith("text/") -> {
                withTimeoutOrNull(30.seconds) {
                    try {
                        val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                        if (text.isBlank()) {
                            OcrService.OcrResult.Failure("Text file is empty or could not be read")
                        } else {
                            OcrService.OcrResult.Success(text)
                        }
                    } catch (e: Exception) {
                        Logger.w("DocumentService: text file extraction failed", mapOf(
                            "error" to e.message
                        ), e)
                        OcrService.OcrResult.Failure("Text extraction failed: ${e.message}")
                    }
                } ?: OcrService.OcrResult.Failure("Text file extraction timed out")
            }
            mime.startsWith("image/") -> {
                extractImageTextWithFallback(uri)
            }
            else -> OcrService.OcrResult.Failure("Unsupported file type: $mime")
        }
    }

    /**
     * Extract text from PDF documents with Android native fallback and timeout handling.
     */
    private suspend fun extractPdfTextWithFallback(uri: Uri): OcrService.OcrResult {
        var lastError: String? = null
        
        // Try PdfBox first (primary method) with timeout
        val pdfBoxText = withTimeoutOrNull(EXTRACTION_TIMEOUT_SECONDS.seconds) {
            try {
                extractPdfText(context.contentResolver, uri)
            } catch (e: Exception) {
                Logger.w("DocumentService: PdfBox extraction failed", mapOf(
                    "error" to e.message,
                    "uri" to uri.toString()
                ), e)
                lastError = "PdfBox failed: ${e.message}"
                null
            }
        }
        
        if (pdfBoxText != null && pdfBoxText.isNotBlank()) {
            Logger.i("DocumentService: PdfBox extraction succeeded", mapOf(
                "textLength" to pdfBoxText.length
            ))
            return OcrService.OcrResult.Success(pdfBoxText)
        }

        // Fallback to Android native PDF processing with timeout
        if (androidPdfService.canHandle(uri)) {
            val nativeResult = withTimeoutOrNull(EXTRACTION_TIMEOUT_SECONDS.seconds) {
                try {
                    androidPdfService.extractText(uri)
                } catch (e: Exception) {
                    Logger.w("DocumentService: Android native PDF extraction failed", mapOf(
                        "error" to e.message
                    ), e)
                    lastError = "Android native PDF failed: ${e.message}"
                    null
                }
            }
            
            if (nativeResult != null && nativeResult.text.isNotBlank()) {
                Logger.i("DocumentService: used Android native PDF extraction as fallback", mapOf(
                    "textLength" to nativeResult.text.length
                ))
                return OcrService.OcrResult.Success(nativeResult.text)
            }
        }

        // If all methods failed, try partial extraction (first page only)
        val partialResult = tryPartialPdfExtraction(uri)
        if (partialResult != null) {
            Logger.w("DocumentService: partial PDF extraction succeeded", mapOf(
                "textLength" to partialResult.length,
                "note" to "Only first page extracted"
            ))
            return OcrService.OcrResult.Success(partialResult)
        }

        return OcrService.OcrResult.Failure(
            "PDF extraction failed with all available methods. ${lastError ?: "Unknown error"}"
        )
    }
    
    /**
     * Try to extract text from just the first page of a PDF (partial extraction)
     */
    private suspend fun tryPartialPdfExtraction(uri: Uri): String? {
        return withTimeoutOrNull(30.seconds) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    PDDocument.load(input).use { doc ->
                        if (doc.numberOfPages > 0) {
                            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                            stripper.startPage = 1
                            stripper.endPage = 1
                            stripper.getText(doc)?.takeIf { it.isNotBlank() }
                        } else null
                    }
                }
            } catch (e: Exception) {
                Logger.w("DocumentService: partial PDF extraction failed", mapOf(
                    "error" to e.message
                ), e)
                null
            }
        }
    }

    /**
     * Extract text from PDF documents.
     */
    private fun extractPdfText(resolver: ContentResolver, uri: Uri): String {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                if (input != null) {
                    PDDocument.load(input).use { doc ->
                        val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                        stripper.getText(doc) ?: ""
                    }
                } else ""
            } ?: ""
        } catch (e: Exception) {
            Logger.w("DocumentService: PDF extraction error", mapOf(
                "error" to e.message,
                "uri" to uri.toString()
            ), e)
            throw e // Re-throw to be caught by caller
        }
    }

    /**
     * Extract text from images with Android native OCR fallback and timeout handling.
     */
    private suspend fun extractImageTextWithFallback(uri: Uri): OcrService.OcrResult {
        var lastError: String? = null
        
        // Try existing OCR service first with timeout
        val existingResult = withTimeoutOrNull(EXTRACTION_TIMEOUT_SECONDS.seconds) {
            try {
                OcrService.extractText(context, uri)
            } catch (e: Exception) {
                Logger.w("DocumentService: OCR service extraction failed", mapOf(
                    "error" to e.message
                ), e)
                lastError = "OCR service failed: ${e.message}"
                null
            }
        }
        
        if (existingResult is OcrService.OcrResult.Success) {
            Logger.i("DocumentService: OCR service extraction succeeded", mapOf(
                "textLength" to existingResult.text.length
            ))
            return existingResult
        }

        // Fallback to Android native OCR with timeout
        if (androidOcrService.canHandle(uri)) {
            val nativeResult = withTimeoutOrNull(EXTRACTION_TIMEOUT_SECONDS.seconds) {
                try {
                    androidOcrService.extractText(uri)
                } catch (e: Exception) {
                    Logger.w("DocumentService: Android native OCR failed", mapOf(
                        "error" to e.message
                    ), e)
                    lastError = "Android native OCR failed: ${e.message}"
                    null
                }
            }
            
            when (nativeResult) {
                is AndroidNativeOcrService.OcrResult.Success -> {
                    Logger.i("DocumentService: used Android native OCR as fallback", mapOf(
                        "textLength" to nativeResult.ocrResult.text.length
                    ))
                    return OcrService.OcrResult.Success(nativeResult.ocrResult.text)
                }
                is AndroidNativeOcrService.OcrResult.Failure -> {
                    if (lastError == null) {
                        lastError = nativeResult.error
                    }
                }
                null -> {
                    // Timeout occurred
                    lastError = "OCR extraction timed out after ${EXTRACTION_TIMEOUT_SECONDS}s"
                }
            }
        }

        return OcrService.OcrResult.Failure(
            "OCR extraction failed with all available methods. ${lastError ?: "Unknown error"}"
        )
    }

    /**
     * Resolve the display name for a URI.
     */
    private fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index)
                }
            }
        }
        return null
    }

    /**
     * Compute SHA-256 hash of text content.
     */
    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(value.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
