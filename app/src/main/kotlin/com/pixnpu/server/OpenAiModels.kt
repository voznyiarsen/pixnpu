package com.pixnpu.server

import com.pixnpu.engine.InferenceMetrics
import com.pixnpu.model.LocalModel
import com.pixnpu.model.id
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
    /**
     * GGUF architecture of the loaded model (llama.cpp /models `architecture`);
     * modality capabilities from the engine metrics. Present only on the
     * resident model.
     */
    val architecture: ModelArchitecture? = null,
)

@Serializable
data class ModelMeta(
    @SerialName("n_ctx") val nCtx: Int,
)

/**
 * llama.cpp router /models `architecture` (GGUF arch metadata). Clients (Pi,
 * its extensions) read `input_modalities` to decide whether images/video/audio
 * can be sent to a model. Only the loaded model reports capabilities — the
 * engine exposes them via InferenceMetrics; others omit the field.
 */
@Serializable
data class ModelArchitecture(
    @SerialName("input_modalities") val inputModalities: List<String> = listOf("text"),
    @SerialName("output_modalities") val outputModalities: List<String> = listOf("text"),
)

/** llama.cpp router /v1/models + /models status object. */
@Serializable
data class ModelStatus(
    val value: String,
    /**
     * True when the last load attempt of this model failed (llama.cpp
     * `failed`): Pi's loadAndWait stops polling and reports the failure.
     * `exit_code` is omitted — there is no crashed process to report.
     */
    val failed: Boolean = false,
    @SerialName("exit_code") val exitCode: Int? = null,
    /**
     * The CLI args the server (would) load the model with — a string array,
     * exactly like llama.cpp. This MUST stay a list: Pi's llama.cpp extension
     * calls `args.indexOf("--ctx-size")` while the model is unloaded, so a
     * JSON object (`{}`) here throws a TypeError inside its provider
     * registration, which surfaces as a bogus "Llama.cpp unreachable" error
     * and an empty model registry ("Cannot find model ... in pi registry").
     */
    val args: List<String> = emptyList(),
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

/**
 * The llama.cpp /models + /v1/models listing shared by both routes. Every
 * installed model is always reported (in both operating modes) — Pi's
 * `detectServerMode` reads `data[0]`, so an empty list crashes its provider
 * registration into a bogus "unreachable" error.
 *
 * - `status.value` uses the llama.cpp contract "loaded" / "unloaded" (Pi's
 *   /llama UI warns "X is not loaded" and hides the load action for any other
 *   value; "failed" stops Pi's loadAndWait polling on a broken load).
 * - `aliases` carries the bare model id first (Pi displays `aliases[0]`) and
 *   the file name second, like llama.cpp's path-derived aliases.
 * - `meta.n_ctx` + `architecture` are reported only for the resident model
 *   (Pi reads them for the context window / modality gates).
 * - `status.args` always contains `--ctx-size` so Pi can size unloaded
 *   models from the engine's real default instead of falling back to 128000.
 */
internal fun installedModelList(
    models: List<LocalModel>,
    loadedId: String?,
    failedId: String?,
    contextTokens: Int,
    metrics: InferenceMetrics,
): List<ModelInfo> {
    val args = listOf("--ctx-size", contextTokens.toString())
    return models.map { model ->
        val isLoaded = model.id == loadedId
        ModelInfo(
            id = model.id,
            created = model.lastModified / 1000,
            aliases = listOf(model.id, model.name),
            status = ModelStatus(
                value = if (isLoaded) "loaded" else "unloaded",
                failed = model.id == failedId,
                args = args,
            ),
            meta = if (isLoaded) {
                ModelMeta(nCtx = metrics.maxContextTokens.coerceAtLeast(1))
            } else {
                null
            },
            architecture = if (isLoaded) {
                ModelArchitecture(
                    inputModalities = buildList {
                        add("text")
                        if (metrics.supportsVision) add("image")
                        if (metrics.supportsVideo) add("video")
                        if (metrics.supportsAudio) add("audio")
                    },
                )
            } else {
                null
            },
        )
    }
}
