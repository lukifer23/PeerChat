package com.peerchat.app.util

import com.peerchat.data.db.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

// Helper function to convert FloatArray to ByteArray for testing
private fun FloatArray.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (value in this) {
        buffer.putFloat(value)
    }
    return buffer.array()
}

class DataValidationTest {

    @Test
    fun `validateFolder - valid folder passes`() {
        val folder = Folder(
            id = 1,
            name = "Test Folder",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val result = DataValidation.validateFolder(folder)
        assertTrue("Valid folder should pass validation", result.isValid())
        assertTrue("Valid folder should have no errors", result.getValidationErrors().isEmpty())
    }

    @Test
    fun `validateFolder - blank name fails`() {
        val folder = Folder(
            id = 1,
            name = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val result = DataValidation.validateFolder(folder)
        assertFalse("Blank name should fail", result.isValid())
        assertTrue("Should contain name error", result.getValidationErrors().contains("Folder name cannot be blank"))
    }

    @Test
    fun `validateFolder - name too long fails`() {
        val longName = "a".repeat(101)
        val folder = Folder(
            id = 1,
            name = longName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val result = DataValidation.validateFolder(folder)
        assertFalse("Long name should fail", result.isValid())
        assertTrue("Should contain length error", result.getValidationErrors().any { it.contains("too long") })
    }

    @Test
    fun `validateFolder - invalid timestamps fail`() {
        val folder = Folder(
            id = 1,
            name = "Test",
            createdAt = 0,
            updatedAt = System.currentTimeMillis()
        )

        val result = DataValidation.validateFolder(folder)
        assertFalse("Invalid timestamp should fail", result.isValid())
        assertTrue("Should contain timestamp error", result.getValidationErrors().contains("Invalid creation timestamp"))
    }

    @Test
    fun `validateChat - valid chat passes`() {
        val chat = Chat(
            id = 1,
            folderId = 1,
            title = "Test Chat",
            systemPrompt = "You are a helpful assistant",
            modelId = "llama-7b",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            settingsJson = "{\"temperature\": 0.7}"
        )

        val result = DataValidation.validateChat(chat)
        assertTrue("Valid chat should pass", result.isValid())
    }

    @Test
    fun `validateChat - blank title fails`() {
        val chat = Chat(
            id = 1,
            folderId = 1,
            title = "",
            systemPrompt = "Test",
            modelId = "llama-7b",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            settingsJson = "{}"
        )

        val result = DataValidation.validateChat(chat)
        assertFalse("Blank title should fail", result.isValid())
        assertTrue("Should contain title error", result.getValidationErrors().contains("Chat title cannot be blank"))
    }

    @Test
    fun `validateChat - invalid JSON fails`() {
        val chat = Chat(
            id = 1,
            folderId = 1,
            title = "Test",
            systemPrompt = "Test",
            modelId = "llama-7b",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            settingsJson = "{invalid json"
        )

        val result = DataValidation.validateChat(chat)
        assertFalse("Invalid JSON should fail", result.isValid())
        assertTrue("Should contain JSON error", result.getValidationErrors().contains("Invalid settings JSON"))
    }

    @Test
    fun `validateMessage - valid message passes`() {
        val message = Message(
            id = 1,
            chatId = 1,
            role = "user",
            contentMarkdown = "Hello world",
            tokens = 2,
            ttfsMs = 150,
            tps = 13.3f,
            contextUsedPct = 0.1f,
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        )

        val result = DataValidation.validateMessage(message)
        assertTrue("Valid message should pass", result.isValid())
    }

    @Test
    fun `validateMessage - invalid role fails`() {
        val message = Message(
            id = 1,
            chatId = 1,
            role = "invalid",
            contentMarkdown = "Hello",
            tokens = 1,
            ttfsMs = 100,
            tps = 10f,
            contextUsedPct = 0.1f,
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        )

        val result = DataValidation.validateMessage(message)
        assertFalse("Invalid role should fail", result.isValid())
        assertTrue("Should contain role error", result.getValidationErrors().contains("Invalid message role: invalid"))
    }

    @Test
    fun `validateMessage - negative values fail`() {
        val message = Message(
            id = 1,
            chatId = 1,
            role = "user",
            contentMarkdown = "Hello",
            tokens = -1,
            ttfsMs = 100,
            tps = 10f,
            contextUsedPct = 0.1f,
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        )

        val result = DataValidation.validateMessage(message)
        assertFalse("Negative tokens should fail", result.isValid())
        assertTrue("Should contain token error", result.getValidationErrors().contains("Token count cannot be negative"))
    }

    @Test
    fun `validateDocument - valid document passes`() {
        val document = Document(
            id = 1,
            uri = "content://com.example/document/1",
            title = "Test Document",
            hash = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
            mime = "text/plain",
            textBytes = "Hello world".toByteArray(),
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        )

        val result = DataValidation.validateDocument(document)
        assertTrue("Valid document should pass", result.isValid())
    }

    @Test
    fun `validateDocument - invalid hash fails`() {
        val document = Document(
            id = 1,
            uri = "content://test",
            title = "Test",
            hash = "invalid",
            mime = "text/plain",
            textBytes = "test".toByteArray(),
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        )

        val result = DataValidation.validateDocument(document)
        assertFalse("Invalid hash should fail", result.isValid())
        assertTrue("Should contain hash error", result.getValidationErrors().contains("Invalid document hash format"))
    }

    @Test
    fun `validateEmbedding - valid embedding passes`() {
        val embedding = Embedding(
            id = 1,
            docId = 1,
            chatId = null,
            textHash = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
            vector = FloatArray(768) { 0.1f }.toByteArray(),
            dim = 768,
            norm = 1.0f,
            createdAt = System.currentTimeMillis()
        )

        val result = DataValidation.validateEmbedding(embedding)
        assertTrue("Valid embedding should pass", result.isValid())
    }

    @Test
    fun `validateEmbedding - empty vector fails`() {
        val embedding = Embedding(
            id = 1,
            docId = 1,
            chatId = null,
            textHash = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
            vector = FloatArray(0).toByteArray(),
            dim = 768,
            norm = 1.0f,
            createdAt = System.currentTimeMillis()
        )

        val result = DataValidation.validateEmbedding(embedding)
        assertFalse("Empty vector should fail", result.isValid())
        assertTrue("Should contain vector error", result.getValidationErrors().contains("Embedding vector cannot be empty"))
    }

    @Test
    fun `validateRagChunk - valid chunk passes`() {
        val chunk = RagChunk(
            id = 1,
            docId = 1,
            start = 0,
            end = 10,
            text = "Hello world",
            tokenCount = 2,
            embeddingId = 1
        )

        val result = DataValidation.validateRagChunk(chunk)
        assertTrue("Valid chunk should pass", result.isValid())
    }

    @Test
    fun `validateRagChunk - invalid positions fail`() {
        val chunk = RagChunk(
            id = 1,
            docId = 1,
            start = 10,
            end = 5,
            text = "Hello",
            tokenCount = 1,
            embeddingId = 1
        )

        val result = DataValidation.validateRagChunk(chunk)
        assertFalse("Invalid positions should fail", result.isValid())
        assertTrue("Should contain position error", result.getValidationErrors().contains("End position must be greater than start position"))
    }

    // Note: InputSanitizer tests moved to InputSanitizerTest.kt

    @Test
    fun `InputSanitizer - sanitizes dangerous content`() {
        val dangerousInput = "<script>alert('xss')</script>Hello<img src=x onerror=alert(1)>"
        val sanitized = InputSanitizer.sanitizeChatInput(dangerousInput)

        assertFalse("Should remove script tags", sanitized.contains("<script>"))
        assertFalse("Should remove img tags", sanitized.contains("<img"))
        assertTrue("Should contain safe text", sanitized.contains("Hello"))
        assertTrue("Should contain removal marker", sanitized.contains("[REMOVED]"))
    }


    @Test
    fun `InputSanitizer - handles length limits`() {
        val longInput = "a".repeat(50001)

        try {
            InputSanitizer.sanitizeChatInput(longInput)
            fail("Should throw SecurityException for too long input")
        } catch (e: SecurityException) {
            assertTrue("Should mention length limit", e.message?.contains("too long") == true)
        }
    }
}
