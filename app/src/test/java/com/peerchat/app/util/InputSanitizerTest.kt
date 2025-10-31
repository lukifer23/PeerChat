package com.peerchat.app.util

import org.junit.Assert.*
import org.junit.Test

class InputSanitizerTest {

    @Test
    fun `sanitizeChatInput - removes dangerous HTML tags`() {
        val input = "<script>alert('xss')</script>Hello<img src=x onerror=alert(1)> world"
        val result = InputSanitizer.sanitizeChatInput(input)

        assertFalse("Should remove script tags", result.contains("<script>"))
        assertFalse("Should remove img tags", result.contains("<img"))
        assertTrue("Should keep safe content", result.contains("Hello"))
        assertTrue("Should keep safe content", result.contains("world"))
        assertTrue("Should mark removed content", result.contains("[REMOVED]"))
    }

    @Test
    fun `sanitizeChatInput - removes event handlers`() {
        val input = "<div onclick=\"alert('xss')\">Click me</div>"
        val result = InputSanitizer.sanitizeChatInput(input)

        assertFalse("Should remove onclick handler", result.contains("onclick"))
        assertTrue("Should keep text content", result.contains("Click me"))
    }

    @Test
    fun `sanitizeChatInput - removes nested dangerous content`() {
        val input = "<iframe><script>evil()</script></iframe>Safe text"
        val result = InputSanitizer.sanitizeChatInput(input)

        assertFalse("Should remove iframe", result.contains("<iframe>"))
        assertFalse("Should remove script", result.contains("<script>"))
        assertTrue("Should keep safe text", result.contains("Safe text"))
    }

    @Test
    fun `sanitizeChatInput - normalizes whitespace`() {
        val input = "  Hello   world  \n  test  "
        val result = InputSanitizer.sanitizeChatInput(input)

        assertEquals("Should normalize whitespace", "Hello world test", result)
    }

    @Test
    fun `sanitizeChatInput - removes control characters`() {
        val input = "Hello${'\u0000'}${'\u0001'}${'\u0002'} world${'\u007F'}"
        val result = InputSanitizer.sanitizeChatInput(input)

        assertEquals("Should remove control characters", "Hello world", result)
    }

    @Test
    fun `sanitizeChatInput - enforces length limit`() {
        val longInput = "a".repeat(50001)

        try {
            InputSanitizer.sanitizeChatInput(longInput)
            fail("Should throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue("Should mention length", e.message?.contains("too long") == true)
        }
    }

    @Test
    fun `sanitizeFilename - handles valid filenames`() {
        val validNames = listOf(
            "model.gguf",
            "llama-7b-q4_0.gguf",
            "test_123.gguf"
        )

        validNames.forEach { name ->
            val result = InputSanitizer.sanitizeFilename(name)
            assertEquals("Should not change valid filename", name, result)
        }
    }

    @Test
    fun `sanitizeFilename - replaces dangerous characters`() {
        val dangerous = "file<script>.gguf"
        val result = InputSanitizer.sanitizeFilename(dangerous)

        assertFalse("Should remove script tags", result.contains("<"))
        assertFalse("Should remove script tags", result.contains(">"))
        assertTrue("Should keep safe parts", result.contains("file"))
        assertTrue("Should keep extension", result.endsWith(".gguf"))
    }

    @Test
    fun `sanitizeFilename - removes path traversal attempts`() {
        val traversal = "../../../etc/passwd"
        val result = InputSanitizer.sanitizeFilename(traversal)

        assertFalse("Should remove path traversal", result.contains(".."))
        assertFalse("Should remove slashes", result.contains("/"))
        assertFalse("Should remove backslashes", result.contains("\\"))
    }

    @Test
    fun `sanitizeFilename - removes leading dots`() {
        val hidden = ".hidden.txt"
        val result = InputSanitizer.sanitizeFilename(hidden)

        assertFalse("Should remove leading dot", result.startsWith("."))
    }

    @Test
    fun `sanitizeFilename - enforces length limit`() {
        val longName = "a".repeat(256)

        try {
            InputSanitizer.sanitizeFilename(longName)
            fail("Should throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue("Should mention length", e.message?.contains("too long") == true)
        }
    }


    @Test
    fun `isValidDocumentUrl - accepts valid URLs`() {
        assertTrue("Should accept HTTPS URLs", InputSanitizer.isValidDocumentUrl("https://example.com/doc.pdf"))
        assertTrue("Should accept HTTP URLs", InputSanitizer.isValidDocumentUrl("http://example.com/doc.pdf"))
        assertFalse("Should reject non-HTTP schemes", InputSanitizer.isValidDocumentUrl("ftp://example.com/file"))
        assertFalse("Should reject path traversal", InputSanitizer.isValidDocumentUrl("https://example.com/../../../etc/passwd"))
    }

    @Test
    fun `containsDangerousContent - detects various threats`() {
        val dangerousInputs = listOf(
            "<script>alert('xss')</script>",
            "<img src=x onerror=alert(1)>",
            "javascript:alert('xss')",
            "<iframe src='evil.com'></iframe>",
            "<object data='evil.swf'></object>",
            "eval('evil code')",
            "vbscript:msgbox('evil')"
        )

        dangerousInputs.forEach { input ->
            assertTrue("Should detect danger in: $input", InputSanitizer.containsDangerousContent(input))
        }

        val safeInputs = listOf(
            "Hello world",
            "This is a normal message",
            "https://example.com",
            "Math: 2 + 2 = 4"
        )

        safeInputs.forEach { input ->
            assertFalse("Should not detect danger in: $input", InputSanitizer.containsDangerousContent(input))
        }
    }

    @Test
    fun `sanitizeChatInput - handles edge cases gracefully`() {
        // Empty input
        assertEquals("", InputSanitizer.sanitizeChatInput(""))

        // Only dangerous content
        val onlyDangerous = "<script>evil</script><img src=x>"
        val result = InputSanitizer.sanitizeChatInput(onlyDangerous)
        assertTrue("Should mark all as removed", result.contains("[REMOVED]"))

        // Mixed content
        val mixed = "Safe text <script>evil</script> more safe text"
        val mixedResult = InputSanitizer.sanitizeChatInput(mixed)
        assertTrue("Should keep safe parts", mixedResult.contains("Safe text"))
        assertTrue("Should keep safe parts", mixedResult.contains("more safe text"))
        assertTrue("Should mark dangerous parts", mixedResult.contains("[REMOVED]"))
    }

    @Test
    fun `sanitizeFilename - handles various dangerous patterns`() {
        val testCases = mapOf(
            "normal.gguf" to "normal.gguf",
            "file<script>.gguf" to "file_script_.gguf",
            "file:with:colons.gguf" to "file_with_colons.gguf",
            "file|with|pipes.gguf" to "file_with_pipes.gguf",
            "file?with?marks.gguf" to "file_with_marks.gguf",
            "file*with*stars.gguf" to "file_with_stars.gguf",
            "file\"with\"quotes.gguf" to "file_with_quotes.gguf"
        )

        testCases.forEach { (input, expected) ->
            val result = InputSanitizer.sanitizeFilename(input)
            assertEquals("Should sanitize $input correctly", expected, result)
        }
    }
}
