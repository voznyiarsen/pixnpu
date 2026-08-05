package com.pixnpu.engine

import kotlinx.serialization.Serializable

/**
 * A named set of sampling parameters, persisted in SharedPreferences as JSON.
 * Only the sampling knobs (temperature / top-K / top-P) are part of a preset.
 */
@Serializable
data class SamplingPreset(
    val name: String,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
)
