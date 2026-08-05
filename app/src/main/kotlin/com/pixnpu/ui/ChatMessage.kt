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

/**
 * A text file read from storage and attached to a message as context.
 * Its contents are wrapped in file markers so the model knows where the
 * text came from.
 */
data class TextFileClip(
    val name: String,
    val content: String,
    val truncated: Boolean = false,
)

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
    val imageUri: Uri? = null,
    val audioBytes: ByteArray? = null,
    val textFile: TextFileClip? = null,
)
