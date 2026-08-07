package com.pixnpu.model

data class LocalModel(
    val name: String,
    val absolutePath: String,
    val fileSizeBytes: Long,
    val lastModified: Long,
    val sha256: String?,
    val verified: Boolean,
)

/** The API model id for a local model file (file base name, like llama.cpp --alias). */
val LocalModel.id: String get() = name.removeSuffix(".litertlm")