package com.peerchat.app.ui.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningParserTest {

    @Test
    fun `visible tokens emit directly when no reasoning markers`() {
        val emitted = mutableListOf<String>()
        val parser = ReasoningParser(onVisibleToken = { emitted += it })

        parser.handle("Hello ")
        parser.handle("world!")

        val result = parser.result()
        assertEquals("Hello world!", result.visible)
        assertEquals("", result.reasoning)
        assertEquals(listOf("Hello ", "world!"), emitted)
        assertEquals(0, result.reasoningChars)
        assertNull(result.reasoningDurationMs)
    }

    @Test
    fun `reasoning markers are captured with duration and length`() {
        val emitted = mutableListOf<String>()
        val ticks = ArrayDeque(listOf(0L, 2_000_000L))
        val parser = ReasoningParser(
            onVisibleToken = { emitted += it },
            timeSource = { ticks.removeFirst() }
        )

        parser.handle("Step 1 ")
        parser.handle("<think>considering</think> answer")

        val result = parser.result()
        assertEquals("Step 1  answer", result.visible)
        assertEquals("<think>considering</think>", result.reasoning)
        assertEquals(listOf("Step 1 ", " answer"), emitted)
        assertEquals(11, result.reasoningChars)
        assertEquals(2L, result.reasoningDurationMs)
    }

    @Test
    fun `unterminated reasoning preserves partial buffer`() {
        val parser = ReasoningParser(onVisibleToken = {}, timeSource = { 0L })

        parser.handle("prefix <|startofthink|>partial")

        val result = parser.result()
        assertEquals("prefix ", result.visible)
        assertEquals("<|startofthink|>partial", result.reasoning)
        assertEquals(7, result.reasoningChars)
        assertNull(result.reasoningDurationMs)
    }

    @Test
    fun `multiple reasoning blocks are captured`() {
        val emitted = mutableListOf<String>()
        val ticks = ArrayDeque(listOf(0L, 1_000_000L, 2_000_000L, 3_000_000L))
        val parser = ReasoningParser(
            onVisibleToken = { emitted += it },
            timeSource = { ticks.removeFirst() }
        )

        parser.handle("Start <reasoning>first</reasoning> middle <reasoning>second</reasoning> end")

        val result = parser.result()
        assertEquals("Start  middle  end", result.visible)
        // Reasoning should contain both blocks
        assertTrue(result.reasoning.contains("first"))
        assertTrue(result.reasoning.contains("second"))
        assertEquals(12, result.reasoningChars) // "first" + "second"
        assertEquals(3L, result.reasoningDurationMs) // From first start to last end
    }

    @Test
    fun `nested reasoning markers handle correctly`() {
        val emitted = mutableListOf<String>()
        val parser = ReasoningParser(onVisibleToken = { emitted += it })

        parser.handle("Text <reasoning>inner<reasoning>nested</reasoning></reasoning> more")

        val result = parser.result()
        assertEquals("Text  more", result.visible)
        // Nested markers should be captured as part of reasoning
        assertTrue(result.reasoning.contains("nested"))
    }

    @Test
    fun `snapshot provides incremental state`() {
        val parser = ReasoningParser(onVisibleToken = {})

        parser.handle("Hello <reasoning>thinking</reasoning>")

        val snapshot = parser.snapshot()
        assertEquals("Hello ", snapshot.visible)
        assertEquals("<reasoning>thinking</reasoning>", snapshot.reasoning)
        assertEquals(8, snapshot.reasoningChars)
    }

    @Test
    fun `empty input handled gracefully`() {
        val parser = ReasoningParser(onVisibleToken = {})
        parser.handle("")
        
        val result = parser.result()
        assertEquals("", result.visible)
        assertEquals("", result.reasoning)
        assertEquals(0, result.reasoningChars)
    }
}
