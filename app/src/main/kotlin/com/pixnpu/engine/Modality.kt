package com.pixnpu.engine

/**
 * Explicit, user-chosen input modality for the loaded model. Replaces the previous
 * automatic (init-variant fallback) detection: the user picks which backends the
 * engine should configure (audio on CPU, vision on GPU, both, or text-only), and
 * LiteRT-LM itself validates the request at load — models lacking a modality
 * simply fail cleanly rather than silently down-grading.
 */
enum class Modality(val label: String, val supportsVision: Boolean, val supportsAudio: Boolean) {
    TextOnly("Text", false, false),
    AudioOnly("Audio", false, true),
    VisionOnly("Vision", true, false),
    AudioAndVision("Audio + Vision", true, true),
    Video("Video", true, true),
}
