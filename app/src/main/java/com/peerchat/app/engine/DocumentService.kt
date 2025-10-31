package com.peerchat.app.engine

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.peerchat.app.data.OperationResult
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.docs.OcrService
import com.peerchat.data.db.Document
import com.peerchat.rag.RagService
import com.peerchat.engine.EngineRuntime
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.text.Charsets

/**
 * Service for managing document operations including import, indexing, and deletion.
 * Handles text extraction from various document formats.
 */
class DocumentService(
    private val context: Context,
    private val repository: PeerChatRepository
) {
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
                val text = extractPdfText(resolver, uri)
                if (text.isBlank()) {
                    OcrService.OcrResult.Failure("PDF extraction returned empty text")
                } else {
                    OcrService.OcrResult.Success(text)
                }
            }
            mime.startsWith("text/") -> {
                val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isBlank()) {
                    OcrService.OcrResult.Failure("Text file is empty or could not be read")
                } else {
                    OcrService.OcrResult.Success(text)
                }
            }
            mime.startsWith("image/") -> OcrService.extractText(context, uri)
            else -> OcrService.OcrResult.Failure("Unsupported file type: $mime")
        }
    }

    /**
     * Extract text from PDF documents.
     */
    private fun extractPdfText(resolver: ContentResolver, uri: Uri): String {
        return resolver.openInputStream(uri).use { input ->
            if (input != null) {
                PDDocument.load(input).use { doc ->
                    val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                    stripper.getText(doc) ?: ""
                }
            } else ""
        }
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
