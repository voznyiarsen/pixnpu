package com.pixnpu.model

data class LocalModel(
    val name: String,
    val absolutePath: String,
    val fileSizeBytes: Long,
    val lastModified: Long,
    val sha256: String?,
    val verified: Boolean,
)