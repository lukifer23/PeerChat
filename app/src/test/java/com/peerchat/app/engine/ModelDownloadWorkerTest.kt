package com.peerchat.app.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.peerchat.app.engine.ModelDownloadWorker
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ModelDownloadWorkerTest {

    private lateinit var context: Context
    private lateinit var worker: ModelDownloadWorker

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `isValidHttpsUrl - accepts valid URLs from trusted domains`() {
        val validUrls = listOf(
            "https://huggingface.co/user/model.gguf",
            "https://cdn-lfs.huggingface.co/user/model.gguf",
            "https://github.com/user/repo/raw/main/model.gguf",
            "https://raw.githubusercontent.com/user/repo/main/model.gguf"
        )

        validUrls.forEach { url ->
            assertTrue("Should accept $url", ModelDownloadWorker.isValidHttpsUrl(url))
        }
    }

    @Test
    fun `isValidHttpsUrl - rejects invalid URLs`() {
        val invalidUrls = listOf(
            "http://example.com/model.gguf", // HTTP not HTTPS
            "ftp://example.com/model.gguf", // Wrong scheme
            "https://malicious.com/model.gguf", // Untrusted domain
            "https://huggingface.co/../../../etc/passwd", // Path traversal
            "https://github.com/user/repo/../../../etc/passwd", // Path traversal
            "not-a-url",
            ""
        )

        invalidUrls.forEach { url ->
            assertFalse("Should reject $url", ModelDownloadWorker.isValidHttpsUrl(url))
        }
    }

    @Test
    fun `isValidFilename - accepts GGUF files only`() {
        val validNames = listOf(
            "model.gguf",
            "llama-7b-q4_0.gguf",
            "test_123.gguf",
            "my-model.gguf"
        )

        validNames.forEach { name ->
            assertTrue("Should accept $name", ModelDownloadWorker.isValidFilename(name))
        }
    }

    @Test
    fun `isValidFilename - rejects invalid filenames`() {
        val invalidNames = listOf(
            "model.txt", // Wrong extension
            "model", // No extension
            "../model.gguf", // Path traversal
            "model.gguf/extra", // Path separator
            "model.gguf\\extra", // Backslash
            ".hidden.gguf", // Hidden file
            "", // Empty
            "a".repeat(256) + ".gguf" // Too long
        )

        invalidNames.forEach { name ->
            assertFalse("Should reject $name", ModelDownloadWorker.isValidFilename(name))
        }
    }

    @Test
    fun `isValidSha256 - validates hash format correctly`() {
        val validHash = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3"

        assertTrue("Should accept valid SHA256", ModelDownloadWorker.isValidSha256(validHash))
        assertFalse("Should reject short hash", ModelDownloadWorker.isValidSha256("short"))
        assertFalse("Should reject non-hex", ModelDownloadWorker.isValidSha256("zzzzzzzz20422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3"))
        assertFalse("Should reject empty", ModelDownloadWorker.isValidSha256(""))
    }

    @Test
    fun `computeSha256 - computes correct hash`() {
        val testData = "Hello, World!"
        val tempFile = File.createTempFile("test", ".txt").apply {
            writeText(testData)
            deleteOnExit()
        }

        val hash = ModelDownloadWorker.computeSha256(tempFile)
        val expectedHash = "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"

        assertEquals("SHA256 should match", expectedHash, hash)
    }

    @Test
    fun `computeSha256 - handles empty file`() {
        val tempFile = File.createTempFile("empty", ".txt").apply {
            deleteOnExit()
        }

        val hash = ModelDownloadWorker.computeSha256(tempFile)
        val expectedHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        assertEquals("Empty file SHA256 should match", expectedHash, hash)
    }

    @Test
    fun `computeSha256 - handles large files`() {
        val largeData = "x".repeat(100000) // 100KB of data
        val tempFile = File.createTempFile("large", ".txt").apply {
            writeText(largeData)
            deleteOnExit()
        }

        val hash = ModelDownloadWorker.computeSha256(tempFile)
        assertNotNull("Should compute hash for large file", hash)
        assertEquals("Hash should be correct length", 64, hash.length)
        assertTrue("Hash should be hex", hash.matches(Regex("[a-f0-9]{64}")))
    }

    @Test
    fun `security validation - comprehensive test`() {
        // Test valid inputs
        assertTrue("Valid URL", ModelDownloadWorker.isValidHttpsUrl("https://huggingface.co/user/model.gguf"))
        assertTrue("Valid filename", ModelDownloadWorker.isValidFilename("model.gguf"))
        assertTrue("Valid SHA256", ModelDownloadWorker.isValidSha256("a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3"))

        // Test various attack vectors
        val attackVectors = listOf(
            "https://evil.com/malware.exe" to "Wrong domain",
            "../../../etc/passwd" to "Path traversal in filename",
            "javascript:alert('xss')" to "JavaScript injection in URL",
            "<script>evil</script>.gguf" to "HTML injection in filename",
            "a".repeat(300) + ".gguf" to "Extremely long filename",
            "invalid-sha256-hash" to "Invalid SHA256 format"
        )

        attackVectors.forEach { (input, description) ->
            when {
                input.startsWith("http") -> assertFalse("$description should be rejected", ModelDownloadWorker.isValidHttpsUrl(input))
                input.contains(".gguf") || input.contains("passwd") -> assertFalse("$description should be rejected", ModelDownloadWorker.isValidFilename(input))
                else -> assertFalse("$description should be rejected", ModelDownloadWorker.isValidSha256(input))
            }
        }
    }
}
