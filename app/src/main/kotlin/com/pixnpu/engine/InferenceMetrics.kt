package com.pixnpu.engine

enum class EngineStatus {
    Idle,
    Loading,
    Ready,
    Generating,
    Error,
}

data class InferenceMetrics(
    val status: EngineStatus = EngineStatus.Idle,
    val backend: String = "-",
    val contextTokens: Int = 0,
    val maxContextTokens: Int = 0,
    val ttftMs: Long? = null,
    val warmupMs: Long? = null,
    val totalTokens: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val currentTokensPerSecond: Double = 0.0,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsVideo: Boolean = false,
)