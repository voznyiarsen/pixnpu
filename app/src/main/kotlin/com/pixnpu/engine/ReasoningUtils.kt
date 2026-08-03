package com.pixnpu.engine

import android.util.Log

private const val TAG = "ReasoningUtils"

/**
 * Strips reasoning blocks (<think>...</think>) from text while preserving the rest.
 * Used to clean assistant responses before feeding them back into conversation history.
 */
fun String.stripReasoning(): String {
    val original = this
    val stripped = this.replace(Regex("""<think>.*?</think>"""), "")
        .replace(Regex("""\s+\n\s*"""), "\n")
        .trim()
    if (original != stripped) {
        Log.d(TAG, "Stripped reasoning from response (${original.length} -> ${stripped.length} chars)")
    }
    return stripped
}
