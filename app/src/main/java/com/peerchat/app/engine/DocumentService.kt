package com.peerchat.app.engine

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.data.db.Document
import com.peerchat.rag.RagService
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Service for managing document operations including import, indexing, and deletion.
 */
class DocumentService(
    private val context: Context,
    private val repository: PeerChatRepository
) {
    data class ImportResult(
        val success: Boolean,
        val message: String,
        val document: Document? = null
    )

    data class DeleteResult(
        val success: Boolean,
        val message: String
    )

    /**
     * Import a document from a URI and index it for RAG.
     */
    suspend fun importDocument(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = resolveDisplayName(resolver, uri) ?: "Document"
            val text = extractText(resolver, uri, mime)

            if (text.isBlank()) {
                return@withContext ImportResult(success = false, message = "No text content found in document")
            }

            val document = Document(
                uri = uri.toString(),
                title = name,
                hash = sha256(text),
                mime = mime,
                textBytes = text.toByteArray(),
                createdAt = System.currentTimeMillis(),
                metaJson = "{}"
            )

            val id = repository.upsertDocument(document)
            val indexedDocument = document.copy(id = id)

            // Index the document for RAG
            RagService.indexDocument(repository.database(), indexedDocument, text)

            ImportResult(
                success = true,
                message = "Document indexed successfully",
                document = indexedDocument
            )
        } catch (e: Exception) {
            ImportResult(success = false, message = "Import error: ${e.message}")
        }
    }

    /**
     * Delete a document and its associated chunks.
     */
    suspend fun deleteDocument(documentId: Long): DeleteResult = withContext(Dispatchers.IO) {
        try {
            repository.deleteDocument(documentId)
            DeleteResult(success = true, message = "Document deleted")
        } catch (e: Exception) {
            DeleteResult(success = false, message = "Delete error: ${e.message}")
        }
    }

    /**
     * Re-index a document with updated content.
     */
    suspend fun reindexDocument(document: Document): ImportResult = withContext(Dispatchers.IO) {
        try {
            val text = String(document.textBytes)
            RagService.indexDocument(repository.database(), document, text)
            ImportResult(success = true, message = "Document re-indexed", document = document)
        } catch (e: Exception) {
            ImportResult(success = false, message = "Re-index error: ${e.message}")
        }
    }

    /**
     * Extract text content from various document types.
     */
    private fun extractText(resolver: ContentResolver, uri: Uri, mime: String): String {
        return when {
            mime == "application/pdf" -> extractPdfText(resolver, uri)
            mime.startsWith("text/") -> resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            else -> ""
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
