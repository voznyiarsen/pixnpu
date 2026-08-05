package com.pixnpu.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    @SerialName("owned_by") val ownedBy: String = "pixnpu",
)

@Serializable
data class ModelListResponse(
    @SerialName("object") val obj: String = "list",
    val data: List<ModelInfo> = emptyList(),
)

@Serializable
data class ServiceInfo(
    val service: String = "pixnpu",
    val api: String = "v1",
    val endpoints: List<String> = listOf("/v1/models", "/v1/chat/completions"),
)
