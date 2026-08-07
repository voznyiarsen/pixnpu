package com.pixnpu.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement,
)

@Serializable
data class ChatCompletionRequest(
    val messages: List<ChatMessage> = emptyList(),
    val model: String? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val n: Int = 1,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    /**
     * Thinking budget in tokens (llama.cpp / Pi convention): -1 = enabled with
     * infinite budget, >0 = enabled with that budget, 0 = disabled.
     */
    @SerialName("thinking_budget_tokens") val thinkingBudgetTokens: Int? = null,
    /**
     * Template kwargs (Pi sends {"enable_thinking": false} for its "off" level).
     */
    @SerialName("chat_template_kwargs") val chatTemplateKwargs: JsonObject? = null,
    /** OpenAI reasoning effort levels, mapped to token budgets. */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val obj: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage,
    @SerialName("system_fingerprint") val systemFingerprint: String? = null,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ResponseMessage,
    @SerialName("finish_reason") val finishReason: String = "stop",
)

@Serializable
data class ResponseMessage(
    val role: String = "assistant",
    val content: String,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("completion_tokens_details") val completionTokensDetails: CompletionTokensDetails? = null,
)

/** OpenAI usage breakdown; reasoning_tokens = estimated thinking-channel tokens. */
@Serializable
data class CompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int = 0,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val obj: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
    val usage: Usage? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class ErrorBody(
    val message: String,
    val type: String = "invalid_request_error",
    val param: String? = null,
    val code: String? = null,
)

@Serializable
data class ApiError(
    val error: ErrorBody,
)

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("object") val obj: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "llamacpp",
    val aliases: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Router-mode status ("loaded" / "not loaded"), absent in single-model mode. */
    val status: ModelStatus? = null,
    /**
     * Model metadata for the currently loaded model (llama.cpp /models `meta`).
     * Only the resident model reports n_ctx; others omit it and clients fall
     * back to their defaults.
     */
    val meta: ModelMeta? = null,
)

@Serializable
data class ModelMeta(
    @SerialName("n_ctx") val nCtx: Int,
)

/** llama.cpp router /v1/models + /models status object. */
@Serializable
data class ModelStatus(
    val value: String,
    val args: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ModelListResponse(
    @SerialName("object") val obj: String = "list",
    val data: List<ModelInfo> = emptyList(),
)

/**
 * Service identity advertised at GET /.
 *
 * Branded as llama.cpp so API-discovery clients (LM Studio, Open WebUI,
 * SillyTavern, ...) recognize the server; [impl] names the actual backend.
 */
@Serializable
data class ServiceInfo(
    val service: String = "llama.cpp",
    val impl: String = "pixnpu",
    val api: String = "v1",
    val mode: String = "single-model",
    val version: String = "1.0.0",
    val endpoints: List<String> = listOf(
        "/v1/models",
        "/v1/chat/completions",
        "/completion",
        "/props",
        "/slots",
        "/models",
        "/models/load",
        "/models/unload",
        "/tokenize",
        "/detokenize",
    ),
)
