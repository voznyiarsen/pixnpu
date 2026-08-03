package com.pixnpu.engine

import android.util.Log

private const val TAG = "ReasoningUtils"

/**
 * Strips reasoning blocks (<think>...</think> and unclosed <think>...) from text while preserving the rest.
 * Used to clean assistant responses before feeding them back into conversation history.
 */
fun String.stripReasoning(): String {
    val original = this
    var stripped = this.replace(Regex("""<think>.*?</think>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
    // Also remove unclosed <think> blocks (e.g. if generation stopped mid-thought)
    stripped = stripped.replace(Regex("""<think>.*""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        .replace(Regex("""\s+\n\s*"""), "\n")
        .trim()
    if (original != stripped) {
        runCatching { Log.d(TAG, "Stripped reasoning from response (${original.length} -> ${stripped.length} chars)") }
    }
    return stripped
}
