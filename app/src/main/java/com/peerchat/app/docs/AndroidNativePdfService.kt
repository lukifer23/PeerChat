package com.peerchat.app.docs

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.peerchat.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Native Android PDF processing service using PdfRenderer as fallback to PdfBox
 * Provides better performance and reliability for Android-specific PDF handling.
 */
class AndroidNativePdfService(private val context: Context) {

    data class PdfTextResult(
        val text: String,
        val pageCount: Int,
        val extractedPages: Int,
        val method: String
    )

    data class PdfPageInfo(
        val pageNumber: Int,
        val text: String,
        val hasText: Boolean
    )

    /**
     * Extract text from PDF using Android's native PdfRenderer
     */
    suspend fun extractText(uri: Uri): PdfTextResult = withContext(Dispatchers.IO) {
        var parcelFileDescriptor: ParcelFileDescriptor? = null
        var pdfRenderer: PdfRenderer? = null

        try {
            // Open PDF using Android's native APIs
            parcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Could not open PDF file")

            pdfRenderer = PdfRenderer(parcelFileDescriptor)
            val pageCount = pdfRenderer.pageCount

            if (pageCount == 0) {
                return@withContext PdfTextResult("", 0, 0, "native_empty")
            }

            val extractedPages = mutableListOf<PdfPageInfo>()
            val stringBuilder = StringBuilder()

            // Extract text from each page
            for (pageIndex in 0 until minOf(pageCount, 50)) { // Limit to first 50 pages for performance
                try {
                    val page = pdfRenderer.openPage(pageIndex)
                    val pageText = extractTextFromPage(page, pageIndex + 1)
                    page.close()

                    if (pageText.hasText) {
                        extractedPages.add(pageText)
                        stringBuilder.append(pageText.text)
                        stringBuilder.append("\n\n")
                    }

                } catch (e: Exception) {
                    Logger.w("AndroidNativePdfService: failed to extract page ${pageIndex + 1}",
                        mapOf("error" to e.message), e)
                }
            }

            val extractedText = stringBuilder.toString().trim()

            Logger.i("AndroidNativePdfService: extraction completed", mapOf(
                "totalPages" to pageCount,
                "extractedPages" to extractedPages.size,
                "textLength" to extractedText.length,
                "method" to "native"
            ))

            PdfTextResult(extractedText, pageCount, extractedPages.size, "native")

        } catch (e: Exception) {
            Logger.w("AndroidNativePdfService: native PDF extraction failed",
                mapOf("error" to e.message), e)

            // Return empty result to allow fallback to other methods
            PdfTextResult("", 0, 0, "native_error")

        } finally {
            try {
                pdfRenderer?.close()
                parcelFileDescriptor?.close()
            } catch (e: Exception) {
                Logger.w("AndroidNativePdfService: cleanup error", mapOf("error" to e.message), e)
            }
        }
    }

    /**
     * Extract text from a single PDF page
     * Note: Android's PdfRenderer doesn't natively extract text - this is a placeholder
     * for future OCR-based text extraction from rendered pages
     */
    private fun extractTextFromPage(page: PdfRenderer.Page, pageNumber: Int): PdfPageInfo {
        return try {
            // For now, we can't extract text directly from PdfRenderer
            // This would need OCR on the rendered bitmap
            // Return placeholder indicating page exists but no text extracted
            PdfPageInfo(
                pageNumber = pageNumber,
                text = "",
                hasText = false
            )
        } catch (e: Exception) {
            Logger.w("AndroidNativePdfService: page extraction error",
                mapOf("page" to pageNumber, "error" to e.message), e)
            PdfPageInfo(pageNumber, "", false)
        }
    }

    /**
     * Get basic PDF information without full extraction
     */
    suspend fun getPdfInfo(uri: Uri): PdfInfo? = withContext(Dispatchers.IO) {
        var parcelFileDescriptor: ParcelFileDescriptor? = null
        var pdfRenderer: PdfRenderer? = null

        return@withContext try {
            parcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return@withContext null

            pdfRenderer = PdfRenderer(parcelFileDescriptor)
            val pageCount = pdfRenderer.pageCount

            PdfInfo(pageCount, "native")

        } catch (e: Exception) {
            Logger.w("AndroidNativePdfService: failed to get PDF info",
                mapOf("error" to e.message), e)
            null
        } finally {
            try {
                pdfRenderer?.close()
                parcelFileDescriptor?.close()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Check if this service can handle the given URI
     */
    fun canHandle(uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            mimeType == "application/pdf"
        } catch (e: Exception) {
            false
        }
    }

    data class PdfInfo(
        val pageCount: Int,
        val method: String
    )

    companion object {
        private const val TAG = "AndroidNativePdfService"
    }
}
