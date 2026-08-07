package com.pixnpu.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.pixnpu.engine.GenerationParams
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Errors that map to OpenAI-style HTTP error responses.
 */
sealed class ChatCompletionError(
    message: String,
    val errorType: String,
    val param: String?,
    val code: String?,
    val status: Int,
) : Exception(message) {
    class BadRequest(message: String, param: String? = null, code: String? = null) :
        ChatCompletionError(message, "invalid_request_error", param, code, 400)

    class ModelNotFound(model: String) :
        ChatCompletionError("The model '$model' does not exist", "invalid_request_error", null, "model_not_found", 404)

    class NoModelLoaded :
        ChatCompletionError(
            "No model is loaded. Load a model in the app before using the API",
            "server_error",
            null,
            "no_model_loaded",
            400,
        )

    class Busy :
        ChatCompletionError(
            "Another generation is already in progress",
            "server_error",
            null,
            "busy",
            429,
        )

    class ModelLoadFailed :
        ChatCompletionError(
            "The requested model could not be loaded (busy or load failed)",
            "server_error",
            null,
            "model_load_failed",
            503,
        )
}

/**
 * Maps OpenAI chat-completion requests onto the LiteRT-LM engine.
 *
 * Stateless by design: every request carries the full message list, which is
 * flattened into a single prompt (role-prefixed), matching how OpenAI clients
 * behave. No history is read from or stored in the engine.
 */
class ChatCompletionsProcessor(private val context: Context) {

    companion object {
        private const val TAG = "ChatCompletionsProcessor"
        private const val rolePrefixSystem = "System: "
        private const val rolePrefixUser = "User: "
        private const val rolePrefixAssistant = "Assistant: "

        /**
         * Cap for decoded base64 payloads (audio bytes, data: URIs) — a bound
         * against memory exhaustion from hostile or buggy clients.
         */
        private const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024

        /** Ceiling for the base64 *string* length that can decode to the cap. */
        private val MAX_BASE64_LEN = ((MAX_PAYLOAD_BYTES * 4) / 3 + 4).toInt()
    }

    /** Rejects base64 that would decode past [MAX_PAYLOAD_BYTES]. */
    private fun checkPayloadSize(base64: String, what: String) {
        if (base64.length > MAX_BASE64_LEN) {
            throw ChatCompletionError.BadRequest(
                "$what exceeds the ${MAX_PAYLOAD_BYTES / (1024 * 1024)} MiB limit",
            )
        }
    }

    /**
     * Builds the engine content list from the request messages.
     * @throws ChatCompletionError.BadRequest on unsupported content
     */
    fun buildContent(request: ChatCompletionRequest): List<Content> {
        if (request.messages.isEmpty()) {
            throw ChatCompletionError.BadRequest("'messages' must not be empty")
        }
        val result = mutableListOf<Content>()
        for (message in request.messages) {
            val prefix = when (message.role) {
                "system" -> rolePrefixSystem
                "user" -> rolePrefixUser
                "assistant" -> rolePrefixAssistant
                else -> throw ChatCompletionError.BadRequest(
                    "Unsupported role '${message.role}' (expected system, user or assistant)",
                )
            }
            val content = message.content
            when (content) {
                is JsonPrimitive -> {
                    val text = content.contentOrNull
                        ?: throw ChatCompletionError.BadRequest("Message content is null")
                    if (text.isBlank()) continue
                    result.add(Content.Text(prefix + text))
                }

                is JsonArray -> {
                    result.addAll(parseParts(prefix, content))
                }

                else -> throw ChatCompletionError.BadRequest(
                    "Message content must be a string or an array of content parts",
                )
            }
        }
        if (result.isEmpty()) {
            throw ChatCompletionError.BadRequest("'messages' contains no usable content")
        }
        return result
    }

    private fun parseParts(prefix: String, parts: JsonArray): List<Content> {
        val result = mutableListOf<Content>()
        for (part in parts) {
            val obj = part.jsonObjectOrNull()
                ?: throw ChatCompletionError.BadRequest("Content parts must be objects")
            when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull
                        ?: throw ChatCompletionError.BadRequest("Text part is missing 'text'")
                    if (text.isNotBlank()) result.add(Content.Text(prefix + text))
                }

                "image_url" -> result.add(imagePart(obj))

                "input_audio" -> result.add(audioPart(obj))

                else -> throw ChatCompletionError.BadRequest(
                    "Unsupported content part type '$type' (expected text, image_url or input_audio)",
                )
            }
        }
        return result
    }

    private fun imagePart(obj: JsonObject): Content {
        val urlElement = obj["image_url"]
        val url = when (urlElement) {
            is JsonPrimitive -> urlElement.contentOrNull
            is JsonObject -> urlElement["url"]?.jsonPrimitive?.contentOrNull
            else -> null
        } ?: throw ChatCompletionError.BadRequest("image_url part is missing 'url'")

        val path = when {
            url.startsWith("data:") -> writeDataUri(url, "img")
            url.startsWith("file://") -> url.removePrefix("file://")
            else -> throw ChatCompletionError.BadRequest(
                "Unsupported image_url scheme (expected data: or file://), got: " +
                    url.take(32) + "...",
            )
        }
        if (!File(path).isFile) {
            throw ChatCompletionError.BadRequest("image_url file does not exist: $path")
        }
        return Content.ImageFile(path)
    }

    private fun audioPart(obj: JsonObject): Content {
        val audio = obj["input_audio"]?.jsonObject
            ?: throw ChatCompletionError.BadRequest("input_audio part is missing 'input_audio'")
        val data = audio["data"]?.jsonPrimitive?.contentOrNull
            ?: throw ChatCompletionError.BadRequest("input_audio part is missing 'data'")
        checkPayloadSize(data, "input_audio 'data'")
        val bytes = try {
            Base64.getDecoder().decode(data)
        } catch (e: IllegalArgumentException) {
            throw ChatCompletionError.BadRequest("input_audio 'data' is not valid base64")
        }
        if (bytes.isEmpty()) {
            throw ChatCompletionError.BadRequest("input_audio 'data' decoded to empty bytes")
        }
        // LiteRT-LM's native preprocessor requires a WAV container (see AGENTS.md);
        // callers must supply WAV bytes. Raw PCM or unsupported formats fail
        // through the normal engine error path.
        return Content.AudioBytes(bytes)
    }

    /**
     * Decodes a data: URI (e.g. data:image/png;base64,...) into a temp file and
     * returns its path. Temp files live in the cache dir and are cleaned by the OS.
     */
    private fun writeDataUri(uri: String, tag: String): String {
        val comma = uri.indexOf(',')
        if (comma <= 0) {
            throw ChatCompletionError.BadRequest("Malformed data: URI")
        }
        val metadata = uri.substring(0, comma)
        val payload = uri.substring(comma + 1)
        if (!metadata.endsWith(";base64", ignoreCase = true)) {
            throw ChatCompletionError.BadRequest("data: URI must be base64-encoded")
        }
        checkPayloadSize(payload, "data: URI payload")
        val bytes = try {
            Base64.getDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw ChatCompletionError.BadRequest("data: URI payload is not valid base64")
        }
        if (bytes.isEmpty()) {
            throw ChatCompletionError.BadRequest("data: URI payload is empty")
        }
        val ext = metadata.substringAfter('/').substringBefore(';').take(8)
            .filter { it.isLetterOrDigit() }
            .ifEmpty { "bin" }
        val file = File(context.cacheDir, "${tag}_${System.nanoTime()}.$ext")
        file.writeBytes(bytes)
        Log.d(TAG, "Wrote data: URI to ${file.absolutePath} (${bytes.size} bytes)")
        return file.absolutePath
    }

    /**
     * Per-call generation parameters derived from the request; unspecified
     * values fall back to the engine defaults. `max_completion_tokens` is the
     * modern alias for `max_tokens` (OpenAI accepts both, but not both at once).
     */
    fun effectiveParams(request: ChatCompletionRequest): GenerationParams {
        val maxTokens = request.maxTokens
        val maxCompletionTokens = request.maxCompletionTokens
        if (maxTokens != null && maxTokens < 1) {
            throw ChatCompletionError.BadRequest("'max_tokens' must be a positive integer", "max_tokens")
        }
        if (maxCompletionTokens != null && maxCompletionTokens < 1) {
            throw ChatCompletionError.BadRequest(
                "'max_completion_tokens' must be a positive integer",
                "max_completion_tokens",
            )
        }
        if (maxTokens != null && maxCompletionTokens != null) {
            throw ChatCompletionError.BadRequest(
                "Only one of 'max_tokens' and 'max_completion_tokens' may be set",
                "max_tokens",
            )
        }
        val temperature = request.temperature
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw ChatCompletionError.BadRequest("'temperature' must be between 0 and 2", "temperature")
        }
        val topP = request.topP
        if (topP != null && (topP < 0.0 || topP > 1.0)) {
            throw ChatCompletionError.BadRequest("'top_p' must be between 0 and 1", "top_p")
        }
        val n = request.n
        if (n < 1) {
            throw ChatCompletionError.BadRequest("'n' must be at least 1", "n")
        }
        if (n > 1) {
            // The engine generates a single response per call; reject the rest
            // explicitly instead of silently returning one choice.
            throw ChatCompletionError.BadRequest(
                "This server supports n=1 only, got n=$n",
                "n",
            )
        }
        val thinkingEnabled = request.thinkingBudgetTokens
        if (thinkingEnabled != null && thinkingEnabled < 0) {
            throw ChatCompletionError.BadRequest(
                "'thinking_budget_tokens' must be non-negative",
                "thinking_budget_tokens",
            )
        }
        val thinkingBudget = request.chatTemplateKwargs?.get("enable_thinking")
            ?.jsonPrimitive?.contentOrNull
            ?: return GenerationParams(
                temperature = temperature?.toFloat() ?: GenerationParams().temperature,
                topP = topP?.toFloat() ?: GenerationParams().topP,
                maxTokens = (maxTokens ?: maxCompletionTokens) ?: GenerationParams().maxTokens,
                thinkingEnabled = thinkingEnabled != null,
                thinkingTokenBudget = thinkingEnabled ?: -1,
            )
        // chat_template_kwargs.enable_thinking is an explicit off-switch used by
        // Pi's llama.cpp extension for its "thinking off" level; treat a
        // literal "false" as off even when a budget was passed.
        return GenerationParams(
            temperature = temperature?.toFloat() ?: GenerationParams().temperature,
            topP = topP?.toFloat() ?: GenerationParams().topP,
            maxTokens = (maxTokens ?: maxCompletionTokens) ?: GenerationParams().maxTokens,
            thinkingEnabled = thinkingEnabled != null && thinkingBudget != "false",
            thinkingTokenBudget = thinkingEnabled ?: -1,
        )
    }

    /**
     * Rough token usage estimate (chars/4, mirroring the engine's estimator).
     * [thinkingTokens] (engine-measured, if the model reasoned) is reported in
     * `completion_tokens_details.reasoning_tokens` like Gemini/OpenAI do.
     */
    fun estimateUsage(promptText: String, reply: String, thinkingTokens: Int = 0): Usage {
        val promptTokens = (promptText.length / 4).coerceAtLeast(1)
        val completionTokens = (reply.length / 4).coerceAtLeast(0)
        return Usage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens + thinkingTokens,
            completionTokensDetails = CompletionTokensDetails(reasoningTokens = thinkingTokens),
        )
    }
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = try {
    jsonObject
} catch (_: IllegalArgumentException) {
    null
}
