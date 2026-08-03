package com.pixnpu.model

sealed interface DownloadState {
    data object Idle : DownloadState

    data class Downloading(
        val url: String,
        val fileName: String,
        val bytesReceived: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
        val attempt: Int = 1,
        val maxAttempts: Int = 1,
    ) : DownloadState

    data class Verifying(
        val fileName: String,
        val bytesRead: Long,
        val totalBytes: Long,
    ) : DownloadState

    data class Importing(
        val fileName: String,
        val bytesRead: Long,
        val totalBytes: Long?,
    ) : DownloadState

    data class Paused(val fileName: String, val bytesReceived: Long, val totalBytes: Long? = null) : DownloadState

    data class Complete(val fileName: String, val absolutePath: String) : DownloadState

    data class Failed(val fileName: String, val message: String) : DownloadState

    val finished: Boolean
        get() = this is Complete || this is Failed
}

fun DownloadState.progress(): Float = when (this) {
    is DownloadState.Downloading -> {
        val total = totalBytes
        if (total != null && total > 0) {
            (bytesReceived.toFloat() / total).coerceIn(0f, 1f)
        } else {
            0f
        }
    }
    is DownloadState.Verifying -> (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
    is DownloadState.Importing -> {
        val total = totalBytes
        if (total != null && total > 0) {
            (bytesRead.toFloat() / total).coerceIn(0f, 1f)
        } else {
            0f
        }
    }
    is DownloadState.Paused -> {
        val total = totalBytes
        if (total != null && total > 0) {
            (bytesReceived.toFloat() / total).coerceIn(0f, 1f)
        } else {
            0f
        }
    }
    else -> 0f
}
