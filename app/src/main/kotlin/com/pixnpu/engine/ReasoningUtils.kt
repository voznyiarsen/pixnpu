package com.pixnpu.engine

import android.util.Log

private const val TAG = "ReasoningUtils"

/**
 * A single segment of an assistant reply, split at thinking boundaries.
 */
sealed class ThinkingSegment {
    /**
     * A reasoning block. [closed] is false when the opener tag has no matching
     * closer yet (e.g. generation was cut off mid-thought or is still streaming).
     */
    data class Thinking(val content: String, val closed: Boolean) : ThinkingSegment()

    /** Plain reply text outside any thinking block. */
    data class Text(val content: String) : ThinkingSegment()
}

// Matches <think>...</think> and <thinking>...</thinking> (case-insensitive, multi-line).
private val CLOSED_THINKING_REGEX =
    Regex("""<\s*think(?:ing)?\s*>(.*?)<\s*/\s*think(?:ing)?\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
// Matches an opener with no closer (rest of the text is treated as thinking).
private val UNCLOSED_THINKING_REGEX =
    Regex("""<\s*think(?:ing)?\s*>""", RegexOption.IGNORE_CASE)

/**
 * Splits an assistant reply into thinking and plain-text segments.
 *
 * Handles both `<think>...</think>` (DeepSeek-style) and `<thinking>...</thinking>`
 * (Gemma-3-style) blocks. A trailing opener without a closer (streaming or a
 * cut-off generation) yields a [ThinkingSegment.Thinking] with `closed = false`.
 * Empty blocks are dropped entirely.
 */
fun parseThinking(text: String): List<ThinkingSegment> {
    val segments = mutableListOf<ThinkingSegment>()
    var pos = 0
    for (match in CLOSED_THINKING_REGEX.findAll(text)) {
        val content = match.groupValues[1]
        if (match.range.first > pos) {
            val plain = text.substring(pos, match.range.first)
            if (plain.isNotBlank()) segments.add(ThinkingSegment.Text(plain))
        }
        pos = match.range.last + 1
        if (content.isBlank()) continue
        segments.add(ThinkingSegment.Thinking(content, closed = true))
    }
    if (pos < text.length) {
        val rest = text.substring(pos)
        val unclosed = UNCLOSED_THINKING_REGEX.find(rest)
        if (unclosed != null) {
            // Text between the last closed block and the unclosed opener.
            val prefix = rest.substring(0, unclosed.range.first)
            if (prefix.isNotBlank()) segments.add(ThinkingSegment.Text(prefix))
            val content = rest.substring(unclosed.range.last + 1)
            if (content.isNotBlank()) {
                segments.add(ThinkingSegment.Thinking(content, closed = false))
            }
        } else if (rest.isNotBlank()) {
            segments.add(ThinkingSegment.Text(rest))
        }
    }
    return segments
}

/**
 * Strips reasoning blocks (<think>...</think> / <thinking>...</thinking> and
 * unclosed openers) from text while preserving the rest.
 * Used to clean assistant responses before feeding them back into conversation history.
 */
fun String.stripReasoning(): String {
    val original = this
    val stripped = parseThinking(this)
        .filterIsInstance<ThinkingSegment.Text>()
        .joinToString("") { it.content }
        .replace(Regex("""\s+\n\s*"""), "\n")
        .trim()
    if (original != stripped) {
        runCatching { Log.d(TAG, "Stripped reasoning from response (${original.length} -> ${stripped.length} chars)") }
    }
    return stripped
}
