package com.peerchat.app.ui.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
