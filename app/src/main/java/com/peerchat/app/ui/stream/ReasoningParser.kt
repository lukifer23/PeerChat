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
    private var pendingMarker: String? = null // Handle partial markers at chunk boundaries
    private var consecutiveErrors = 0
    private val maxErrors = 5

    fun handle(chunk: String) {
        if (chunk.isEmpty()) return
        
        var remaining = if (pendingMarker != null) {
            // Prepend pending marker from previous chunk
            pendingMarker + chunk
        } else {
            chunk
        }
        pendingMarker = null
        
        while (remaining.isNotEmpty()) {
            try {
                if (inReasoning) {
                    val endMatch = findNextMarker(remaining, END_MARKERS)
                    if (endMatch == null) {
                        // Check if we have a partial end marker at the end
                        val partialEnd = findPartialMarker(remaining, END_MARKERS)
                        if (partialEnd != null) {
                            pendingMarker = partialEnd
                            reasoning.append(remaining.substring(0, remaining.length - partialEnd.length))
                        } else {
                            reasoning.append(remaining)
                        }
                        return
                    }
                    val endIndex = endMatch.index + endMatch.marker.length
                    reasoning.append(remaining.substring(0, endIndex))
                    remaining = remaining.substring(endIndex)
                    inReasoning = false
                    reasoningEndNs = timeSource()
                    consecutiveErrors = 0 // Reset error count on successful parse
                } else {
                    val startMatch = findNextMarker(remaining, START_MARKERS)
                    if (startMatch == null) {
                        // Check if we have a partial start marker at the end
                        val partialStart = findPartialMarker(remaining, START_MARKERS)
                        if (partialStart != null) {
                            pendingMarker = partialStart
                            val prefix = remaining.substring(0, remaining.length - partialStart.length)
                            if (prefix.isNotEmpty()) {
                                visible.append(prefix)
                                onVisibleToken(prefix)
                            }
                        } else {
                            visible.append(remaining)
                            onVisibleToken(remaining)
                        }
                        return
                    }
                    
                    // Validate marker pair (ensure we're not in a bad state)
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
                    consecutiveErrors = 0 // Reset error count on successful parse
                }
            } catch (e: Exception) {
                consecutiveErrors++
                if (consecutiveErrors >= maxErrors) {
                    // Too many errors, reset state to prevent infinite loops
                    com.peerchat.app.util.Logger.w("ReasoningParser: too many errors, resetting state", mapOf(
                        "errors" to consecutiveErrors,
                        "chunkLength" to chunk.length
                    ))
                    reset()
                    visible.append(remaining)
                    onVisibleToken(remaining)
                    return
                }
                // Continue parsing on error
                remaining = remaining.substring(1)
            }
        }
    }
    
    /**
     * Find partial marker at the end of text (for handling chunk boundaries)
     */
    private fun findPartialMarker(text: String, markers: Array<String>): String? {
        if (text.isEmpty()) return null
        
        // Check if end of text matches start of any marker
        for (marker in markers) {
            if (text.length < marker.length && marker.startsWith(text)) {
                return text
            }
            // Check reverse - if text ends with start of marker
            for (i in 1 until marker.length) {
                val markerPrefix = marker.substring(0, i)
                if (text.endsWith(markerPrefix)) {
                    return markerPrefix
                }
            }
        }
        return null
    }

    fun snapshot(): ReasoningSnapshot {
        val reasoningText = reasoning.toString()
        return ReasoningSnapshot(
            visible = visible.toString(),
            reasoning = reasoningText,
            reasoningChars = reasoningContentLength(reasoningText)
        )
    }

    fun result(): ReasoningParseResult {
        val reasoningText = reasoning.toString()
        val duration = if (reasoningStartNs != null && reasoningEndNs != null) {
            ((reasoningEndNs!! - reasoningStartNs!!) / 1_000_000).coerceAtLeast(0)
        } else null
        
        // Validate reasoning state
        val isValid = !inReasoning || reasoningText.isNotEmpty()
        if (!isValid) {
            com.peerchat.app.util.Logger.w("ReasoningParser: invalid state detected", mapOf(
                "inReasoning" to inReasoning,
                "reasoningLength" to reasoningText.length
            ))
        }
        
        return ReasoningParseResult(
            visible = visible.toString(),
            reasoning = reasoningText,
            reasoningChars = reasoningContentLength(reasoningText),
            reasoningDurationMs = duration
        )
    }
    
    /**
     * Reset parser state (for error recovery)
     */
    fun reset() {
        visible.clear()
        reasoning.clear()
        inReasoning = false
        reasoningStartNs = null
        reasoningEndNs = null
        pendingMarker = null
        consecutiveErrors = 0
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

internal data class ReasoningSnapshot(
    val visible: String,
    val reasoning: String,
    val reasoningChars: Int
)
