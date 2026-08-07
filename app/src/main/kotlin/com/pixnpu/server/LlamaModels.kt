package com.pixnpu.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * DTOs for the llama.cpp server HTTP API (/completion, /props, /slots,
 * /tokenize, /detokenize), faithful to llama.cpp's wire format.
 *
 * Endpoints whose semantics LiteRT-LM cannot provide (there is no tokenizer —
 * only Conversation.getTokenCount()) are answered with HTTP 501 and an
 * explanatory [LlamaError] body instead of fabricated data.
 */

@Serializable
data class LlamaCompletionRequest(
    /** Raw prompt: a string, or (in llama.cpp) an array of token ids. */
    val prompt: JsonElement? = null,
    val stream: Boolean = false,
    @SerialName("n_predict") val nPredict: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("min_p") val minP: Double? = null,
    @SerialName("repeat_penalty") val repeatPenalty: Double? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("n_keep") val nKeep: Int? = null,
    val seed: Int? = null,
    val stop: JsonElement? = null,
    @SerialName("cache_prompt") val cachePrompt: Boolean? = null,
    val echo: Boolean? = null,
    @SerialName("slot_id") val slotId: Int? = null,
    @SerialName("ignore_eos") val ignoreEos: Boolean? = null,
    val samplers: List<String>? = null,
    @SerialName("n_probs") val nProbs: Int? = null,
)

@Serializable
data class LlamaGenerationSettings(
    @SerialName("n_ctx") val nCtx: Int,
    @SerialName("n_predict") val nPredict: Int,
    val model: String,
    val prompt: String = "",
    val temperature: Double,
    @SerialName("top_k") val topK: Int,
    @SerialName("top_p") val topP: Double,
    @SerialName("min_p") val minP: Double = 0.05,
    @SerialName("repeat_penalty") val repeatPenalty: Double = 1.1,
    @SerialName("presence_penalty") val presencePenalty: Double = 0.0,
    @SerialName("frequency_penalty") val frequencyPenalty: Double = 0.0,
    @SerialName("n_keep") val nKeep: Int = 0,
    @SerialName("n_discard") val nDiscard: Int = 0,
    val seed: Int = -1,
    val stop: List<String> = emptyList(),
    val stream: Boolean = false,
    @SerialName("cache_prompt") val cachePrompt: Boolean = false,
    val echo: Boolean = false,
    @SerialName("slot_id") val slotId: Int = -1,
    @SerialName("n_probs") val nProbs: Int = 0,
    val samplers: List<String> = emptyList(),
)

@Serializable
data class LlamaTimings(
    @SerialName("prompt_n") val promptN: Int,
    @SerialName("predicted_n") val predictedN: Int,
    @SerialName("prompt_ms") val promptMs: Double = 0.0,
    @SerialName("predicted_ms") val predictedMs: Double,
    @SerialName("prompt_per_second") val promptPerSecond: Double = 0.0,
    @SerialName("predicted_per_second") val predictedPerSecond: Double,
)

@Serializable
data class LlamaCompletionResponse(
    val content: String,
    val stop: Boolean,
    @SerialName("generation_settings") val generationSettings: LlamaGenerationSettings,
    val timings: LlamaTimings,
    @SerialName("tokens_predicted") val tokensPredicted: Int,
    @SerialName("tokens_evaluated") val tokensEvaluated: Int,
    val truncated: Boolean,
    val model: String,
)

/** One SSE frame for /completion (llama.cpp sends no [DONE] terminator). */
@Serializable
data class LlamaCompletionChunk(
    val content: String,
    val stop: Boolean,
    val model: String,
    @SerialName("tokens_predicted") val tokensPredicted: Int = 0,
    @SerialName("tokens_evaluated") val tokensEvaluated: Int = 0,
    val truncated: Boolean = false,
    @SerialName("generation_settings") val generationSettings: LlamaGenerationSettings? = null,
    val timings: LlamaTimings? = null,
)

@Serializable
data class LlamaProps(
    @SerialName("default_generation_settings") val defaultGenerationSettings: LlamaGenerationSettings,
    @SerialName("total_slots") val totalSlots: Int = 1,
    @SerialName("chat_template") val chatTemplate: String? = null,
    @SerialName("model_path") val modelPath: String? = null,
    @SerialName("system_prompt") val systemPrompt: String = "",
    /** "router" in router mode, "model" otherwise (llama.cpp ServerRole). */
    val role: String = "model",
    /** Router-mode identity (llama.cpp returns "llama-server"). */
    @SerialName("model_alias") val modelAlias: String? = null,
    /** Router-mode: number of models the router can keep loaded (1 engine slot). */
    @SerialName("max_instances") val maxInstances: Int? = null,
    /** Router-mode: models are loaded on demand. */
    @SerialName("models_autoload") val modelsAutoload: Boolean? = null,
    /** Jinja template of the loaded model (llama.cpp GGUF chat_template). */
    @SerialName("bos_token") val bosToken: String? = null,
    @SerialName("eos_token") val eosToken: String? = null,
)

@Serializable
data class LlamaSlot(
    val id: Int,
    /** "idle" or "processing" — one shared engine slot. */
    val state: String,
    val model: String?,
    @SerialName("n_ctx") val nCtx: Int,
    @SerialName("n_predict") val nPredict: Int,
    @SerialName("n_past") val nPast: Int = 0,
    val prompt: String = "",
    @SerialName("prompt_tokens") val promptTokens: List<Int>? = null,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    val params: JsonObject = JsonObject(emptyMap()),
    val timings: LlamaTimings? = null,
)

@Serializable
data class LlamaTokenizeRequest(
    val content: String = "",
    @SerialName("add_special") val addSpecial: Boolean? = null,
    @SerialName("with_pieces") val withPieces: Boolean? = null,
)

@Serializable
data class LlamaDetokenizeRequest(
    val tokens: List<Int> = emptyList(),
)

/** llama.cpp-style error body: {"error": "..."}. */
@Serializable
data class LlamaError(
    val error: String,
)

/** Body of the router management endpoints (llama.cpp /models/load, /models/unload). */
@Serializable
data class RouterModelRequest(
    val model: String = "",
)
