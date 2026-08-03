package com.pixnpu.engine

/**
 * Strips reasoning blocks (<think>...</think>) from text while preserving the rest.
 * Used to clean assistant responses before feeding them back into conversation history.
 */
fun String.stripReasoning(): String {
    return this.replace(Regex("""<think>.*?</think>"""), "")
        .replace(Regex("""\s+\n\s*"""), "\n")
        .trim()
}
