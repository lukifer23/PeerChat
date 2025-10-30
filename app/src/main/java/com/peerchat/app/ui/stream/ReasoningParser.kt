package com.peerchat.app.ui.stream

internal class ReasoningParser(
    private val onVisibleToken: (String) -> Unit,
    private val timeSource: () -> Long = System::nanoTime
) {
    private val visible = StringBuilder()
    private val reasoning = StringBuilder()
    private var inReasoning = false
    private var reasoningStartNs: Long? = null
    private var reasoningEndNs: Long? = null

    fun handle(chunk: String) {
        var remaining = chunk
        while (remaining.isNotEmpty()) {
            if (inReasoning) {
                val endMatch = findNextMarker(remaining, END_MARKERS)
                if (endMatch == null) {
                    reasoning.append(remaining)
                    return
                }
                val endIndex = endMatch.index + endMatch.marker.length
                reasoning.append(remaining.substring(0, endIndex))
                remaining = remaining.substring(endIndex)
                inReasoning = false
                reasoningEndNs = timeSource()
            } else {
                val startMatch = findNextMarker(remaining, START_MARKERS)
                if (startMatch == null) {
                    visible.append(remaining)
                    onVisibleToken(remaining)
                    return
                }
                if (startMatch.index > 0) {
                    val prefix = remaining.substring(0, startMatch.index)
                    visible.append(prefix)
                    onVisibleToken(prefix)
                }
                reasoning.append(startMatch.marker)
                remaining = remaining.substring(startMatch.index + startMatch.marker.length)
                inReasoning = true
                if (reasoningStartNs == null) {
                    reasoningStartNs = timeSource()
                }
            }
        }
    }

    fun result(): ReasoningParseResult {
        val reasoningText = reasoning.toString()
        val duration = if (reasoningStartNs != null && reasoningEndNs != null) {
            ((reasoningEndNs!! - reasoningStartNs!!) / 1_000_000).coerceAtLeast(0)
        } else null
        return ReasoningParseResult(
            visible = visible.toString(),
            reasoning = reasoningText,
            reasoningChars = reasoningContentLength(reasoningText),
            reasoningDurationMs = duration
        )
    }

    private fun findNextMarker(text: String, markers: Array<String>): MarkerMatch? {
        var bestIndex = -1
        var bestMarker: String? = null
        for (marker in markers) {
            val idx = text.indexOf(marker)
            if (idx >= 0 && (bestIndex < 0 || idx < bestIndex)) {
                bestIndex = idx
                bestMarker = marker
            }
        }
        return if (bestIndex >= 0 && bestMarker != null) MarkerMatch(bestIndex, bestMarker) else null
    }

    private fun reasoningContentLength(text: String): Int {
        var cleaned = text
        START_MARKERS.forEach { cleaned = cleaned.replace(it, "") }
        END_MARKERS.forEach { cleaned = cleaned.replace(it, "") }
        return cleaned.length
    }

    private data class MarkerMatch(val index: Int, val marker: String)

    companion object {
        private val START_MARKERS = arrayOf("<think>", "<reasoning>", "<|startofthink|>")
        private val END_MARKERS = arrayOf("</think>", "</reasoning>", "<|endofthink|>")
    }
}

internal data class ReasoningParseResult(
    val visible: String,
    val reasoning: String,
    val reasoningChars: Int,
    val reasoningDurationMs: Long?
)
