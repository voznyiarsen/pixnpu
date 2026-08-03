package com.pixnpu.engine

data class GenerationParams(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val maxTokens: Int = 1024,
    val contextTokens: Int = 8192,
)