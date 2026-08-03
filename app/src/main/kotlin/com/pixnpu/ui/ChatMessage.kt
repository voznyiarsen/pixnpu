package com.pixnpu.ui

import android.net.Uri

enum class ChatRole {
    USER,
    ASSISTANT,
}

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
    val imageUri: Uri? = null,
)