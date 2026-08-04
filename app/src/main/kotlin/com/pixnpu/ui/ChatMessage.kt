package com.pixnpu.ui

import android.net.Uri

enum class ChatRole {
    USER,
    ASSISTANT,
}

/**
 * A recorded voice clip: raw 16-bit PCM mono bytes at 16 kHz, as expected by
 * LiteRT-LM's [com.google.ai.edge.litertlm.Content.AudioBytes].
 */
data class AudioClip(
    val bytes: ByteArray,
    val durationMs: Long,
)

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
    val imageUri: Uri? = null,
    val audioBytes: ByteArray? = null,
)
