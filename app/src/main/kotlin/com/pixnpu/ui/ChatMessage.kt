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

/**
 * A video picked from storage. The audio track and sampled frames are extracted
 * at send time (see VideoFile.kt) and fed to the model as AudioBytes + ImageFiles.
 */
data class VideoClip(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
)

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
    val imageUri: Uri? = null,
    val audioBytes: ByteArray? = null,
    val textFile: TextFileClip? = null,
    val video: VideoClip? = null,
)
