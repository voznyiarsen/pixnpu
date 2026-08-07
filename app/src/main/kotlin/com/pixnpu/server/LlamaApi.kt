package com.pixnpu.server

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.pixnpu.engine.EngineStatus
import com.pixnpu.engine.GenerationParams
import com.pixnpu.engine.LiteRTLMEngineInterface
import com.pixnpu.engine.PromptTemplate
import com.pixnpu.model.LocalModel
import com.pixnpu.model.id
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.Writer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val TAG = "LlamaApi"

/** Max characters allowed in a raw /completion prompt. */
private const val MAX_PROMPT_CHARS = 32000

/**
 * llama.cpp server-compatible endpoints, faithful to llama.cpp's wire format:
 *
 *  - POST /completion    text completion (JSON or SSE streaming; the SSE stream
 *                        ends on a `"stop":true` frame — llama.cpp sends no
 *                        [DONE] terminator here)
 *  - GET  /props         default generation settings + model info; router mode
 *                        advertises `role: "router"` (llama.cpp style) and
 *                        accepts `?model=<id>` for a specific model
 *  - GET  /slots         slot state (exactly one shared slot)
 *  - GET  /models        router mode: every installed model + status
 *  - POST /models/load   router mode: load a model by id (async — responds 200
 *                        immediately, status shows up on GET /models)
 *  - POST /models/unload router mode: unload a model by id
 *  - POST /tokenize      always 501: LiteRT-LM exposes no tokenizer
 *  - POST /detokenize    always 501: there is no detokenizer
 *
 * Shares the engine's busy gate with /v1/chat/completions: exactly one
 * generation (UI or API) may run at a time. Model loads are asynchronous and
 * NOT part of the busy gate — Pi's loadAndWait sequence (POST /models/load,
 * then poll GET /models every 250 ms until status is "loaded") would
 * deadlock against a synchronous load, and the llama.cpp 503s it produces
 * surface as "llama.cpp returned HTTP 503" in the Pi UI.
 */
fun Route.llamaApiRoutes(
    engine: LiteRTLMEngineInterface,
    json: Json,
    modelIdProvider: () -> String?,
    modelPathProvider: () -> String?,
    tokenProvider: () -> String?,
    inFlight: AtomicBoolean,
    modelsProvider: () -> List<LocalModel> = { emptyList() },
    routerModeProvider: () -> Boolean = { false },
    routerLoader: suspend (String) -> Boolean = { false },
    routerUnloader: suspend (String) -> Boolean = { false },
    loadingModelIdProvider: () -> String? = { null },
    loadFailureProvider: () -> String? = { null },
) {
    fun unauthorized(call: ApplicationCall): Boolean {
        val expected = tokenProvider()
        if (expected.isNullOrEmpty()) return false
        val provided = call.request.headers[HttpHeaders.Authorization]
            ?.substringAfter(" ")
            ?.trim()
        return provided != expected
    }

    suspend fun authError(call: ApplicationCall) {
        call.respond(HttpStatusCode.Unauthorized, LlamaError("Incorrect API key provided"))
    }

    post("/completion") {
        if (unauthorized(call)) {
            return@post authError(call)
        }
        val request = try {
            call.receive<LlamaCompletionRequest>()
        } catch (e: Exception) {
            Log.w(TAG, "Malformed request body: ${e.message}")
            return@post call.respond(
                HttpStatusCode.BadRequest,
                LlamaError("Malformed request body: ${e.message}"),
            )
        }
        val prompt = when (request.prompt) {
            // llama.cpp accepts token-id arrays; without a tokenizer the text
            // cannot be reconstructed, so those are rejected explicitly.
            is JsonArray -> return@post call.respond(
                HttpStatusCode.BadRequest,
                LlamaError("Token-array prompts are not supported (no tokenizer available)"),
            )
            else -> promptText(request.prompt)
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    LlamaError("'prompt' must be a non-empty string"),
                )
        }

        if (engine.metrics.value.status == EngineStatus.Generating || !inFlight.compareAndSet(false, true)) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable, LlamaError("Slot busy"))
        }
        try {
            if (!engine.isLoaded) {
                return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    LlamaError("No model is loaded"),
                )
            }
            val modelId = modelIdProvider() ?: "unknown"
            val params = try {
                effectiveParams(request)
            } catch (e: LlamaRequestException) {
                return@post call.respond(HttpStatusCode.BadRequest, LlamaError(e.message ?: "Invalid request"))
            }
            val flow = engine.generate(
                listOf(Content.Text(prompt)),
                template = PromptTemplate.Auto,
                trackHistory = false,
                paramsOverride = params,
            )
            val settings = requestSettings(request, prompt, modelId, params, engine)
            if (request.stream) {
                streamCompletion(call, json, engine, modelId, prompt, params, settings, flow)
            } else {
                val startedAt = System.nanoTime()
                val reply = buildString { flow.collect { append(it) } }
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                val content = if (request.echo == true) prompt + reply else reply
                call.respond(
                    LlamaCompletionResponse(
                        content = content,
                        stop = true,
                        generationSettings = settings,
                        timings = timings(prompt, reply, elapsedMs),
                        tokensPredicted = estimate(reply),
                        tokensEvaluated = estimate(prompt),
                        truncated = truncated(params.maxTokens, reply),
                        model = modelId,
                    ),
                )
            }
        } finally {
            inFlight.set(false)
        }
    }

    get("/props") {
        if (unauthorized(call)) {
            return@get authError(call)
        }
        val router = routerModeProvider()
        val modelId = modelIdProvider()
        if (router) {
            // Router-mode /props: no ?model= -> the router itself (llama.cpp
            // returns role "router" + model_path "none"); ?model=<id> -> that
            // model's props, or a llama.cpp-style error when it isn't resident
            // (400 "model is not loaded" / 503 while loading) — Pi's llama.cpp
            // extension maps exactly these codes to its unloaded/loading states.
            val requested = call.request.queryParameters["model"]
            if (requested != null) {
                val model = modelsProvider().firstOrNull { it.id == requested }
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        LlamaError("model '$requested' is not found"),
                    )
                if (requested == loadingModelIdProvider()) {
                    return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        LlamaPropsErrorBody(LlamaPropsError(503, "model is loading")),
                    )
                }
                if (requested != modelId) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        LlamaPropsErrorBody(
                            LlamaPropsError(400, "model is not loaded", "invalid_request_error"),
                        ),
                    )
                }
                val template = ChatTemplates.forModel(requested)
                call.respond(
                    LlamaProps(
                        defaultGenerationSettings = defaultSettings(engine, modelId),
                        modelPath = modelPathProvider() ?: model.absolutePath,
                        chatTemplate = template?.jinja,
                        role = "model",
                        modelAlias = requested,
                        bosToken = template?.bos,
                        eosToken = template?.eos,
                        modalities = engineModalities(engine),
                    ),
                )
            } else {
                val template = ChatTemplates.forModel(modelId)
                call.respond(
                    LlamaProps(
                        defaultGenerationSettings = defaultSettings(engine, modelId),
                        // llama.cpp router reports "none" while nothing is loaded.
                        modelPath = modelPathProvider() ?: "none",
                        chatTemplate = template?.jinja,
                        role = "router",
                        modelAlias = "llama-server",
                        maxInstances = 1,
                        modelsAutoload = true,
                        bosToken = template?.bos,
                        eosToken = template?.eos,
                    ),
                )
            }
        } else {
            val template = ChatTemplates.forModel(modelId)
            call.respond(
                LlamaProps(
                    defaultGenerationSettings = defaultSettings(engine, modelId),
                    modelPath = modelPathProvider(),
                    chatTemplate = template?.jinja,
                    bosToken = template?.bos,
                    eosToken = template?.eos,
                    modalities = engineModalities(engine),
                ),
            )
        }
    }

    get("/slots") {
        if (unauthorized(call)) {
            return@get authError(call)
        }
        val modelId = modelIdProvider()
        val default = GenerationParams()
        val metrics = engine.metrics.value
        val processing = metrics.status == EngineStatus.Generating
        call.respond(
            listOf(
                LlamaSlot(
                    id = 0,
                    state = if (processing) "processing" else "idle",
                    model = modelId,
                    nCtx = metrics.maxContextTokens.coerceAtLeast(default.contextTokens),
                    nPredict = default.maxTokens,
                    completionTokens = if (processing) 0 else metrics.totalTokens,
                ),
            ),
        )
    }

    // --- Router-mode model management (llama.cpp /models endpoints) ---

    get("/models") {
        if (unauthorized(call)) {
            return@get authError(call)
        }
        if (!routerModeProvider()) {
            return@get call.respond(
                HttpStatusCode.NotFound,
                LlamaError("router mode is disabled — GET /v1/models lists the loaded model"),
            )
        }
        val loadedId = modelIdProvider()
        val metrics = engine.metrics.value
        val failedId = loadFailureProvider()
        call.respond(
            ModelListResponse(
                data = modelsProvider().map { model ->
                    val isLoaded = model.id == loadedId
                    ModelInfo(
                        id = model.id,
                        created = model.lastModified / 1000,
                        aliases = listOf(model.id),
                        // llama.cpp status contract: "loaded" / "unloaded" (Pi's
                        // /llama UI shows "X is not loaded" and hides the load
                        // action for any other value). failed marks the last load
                        // attempt that failed — Pi's loadAndWait stops polling and
                        // reports the failure instead of hanging forever.
                        status = ModelStatus(
                            value = if (isLoaded) "loaded" else "unloaded",
                            failed = model.id == failedId,
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
                },
            ),
        )
    }

    post("/models/load") {
        if (unauthorized(call)) {
            return@post authError(call)
        }
        if (!routerModeProvider()) {
            return@post call.respond(
                HttpStatusCode.NotFound,
                LlamaError("router mode is disabled — load models in the app"),
            )
        }
        val id = runCatching {
            call.receive<RouterModelRequest>().model
        }.getOrNull().orEmpty()
        if (id.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, LlamaError("'model' is required"))
        }
        val model = modelsProvider().firstOrNull { it.id == id }
            ?: return@post call.respond(HttpStatusCode.NotFound, LlamaError("model '$id' is not found"))
        if (id == modelIdProvider()) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                LlamaError("model '$id' is already loaded"),
            )
        }
        if (loadingModelIdProvider() != null) {
            // Another model is already being loaded by a previous request.
            // llama.cpp has one slot, so a concurrent load is busy. 503 tells
            // llama.cpp clients the slot is busy; Pi's loadAndWait treats it
            // as retryable, which is correct here.
            return@post call.respond(HttpStatusCode.ServiceUnavailable, LlamaError("Slot busy"))
        }
        // llama.cpp loads asynchronously and answers 200 immediately — Pi's
        // loadAndWait POSTs then polls GET /models until status "loaded" (or
        // "failed"). Waiting for the load here would deadlock that poll, and a
        // synchronous load failure surfaced as 503 is exactly the
        // "llama.cpp returned HTTP 503" error seen in the Pi UI. Loads also
        // don't hold the busy gate, so a background load can't block chat.
        call.application.launch {
            runCatching { routerLoader(id) }
                .onFailure { Log.w(TAG, "Model load '$id' failed", it) }
        }
        call.respond(mapOf("success" to true))
    }

    post("/models/unload") {
        if (unauthorized(call)) {
            return@post authError(call)
        }
        if (!routerModeProvider()) {
            return@post call.respond(
                HttpStatusCode.NotFound,
                LlamaError("router mode is disabled — unload models in the app"),
            )
        }
        val id = runCatching {
            call.receive<RouterModelRequest>().model
        }.getOrNull().orEmpty()
        if (id.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, LlamaError("'model' is required"))
        }
        if (id != modelIdProvider()) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                LlamaError("model '$id' is not loaded"),
            )
        }
        if (engine.metrics.value.status == EngineStatus.Generating) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable, LlamaError("Slot busy"))
        }
        if (!routerUnloader(id)) {
            return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                LlamaError("model '$id' could not be unloaded"),
            )
        }
        call.respond(mapOf("success" to true))
    }

    post("/tokenize") {
        if (unauthorized(call)) {
            return@post authError(call)
        }
        // LiteRT-LM 0.15.0 exposes only Conversation.getTokenCount() — no tokenizer
        // (token ids / pieces), so a faithful response is impossible. 501 beats
        // fabricated ids.
        call.respond(
            HttpStatusCode.NotImplemented,
            LlamaError(
                "Not implemented: the LiteRT-LM backend has no tokenizer API " +
                    "(only Conversation.getTokenCount()), so token ids cannot be produced",
            ),
        )
    }

    post("/detokenize") {
        if (unauthorized(call)) {
            return@post authError(call)
        }
        call.respond(
            HttpStatusCode.NotImplemented,
            LlamaError("Not implemented: the LiteRT-LM backend has no detokenizer API"),
        )
    }
}

/** Modality capabilities reported by /props for the loaded model. */
private fun engineModalities(engine: LiteRTLMEngineInterface): LlamaModalities? {
    val metrics = engine.metrics.value
    return LlamaModalities(
        vision = metrics.supportsVision,
        video = metrics.supportsVideo,
        audio = metrics.supportsAudio,
    )
}

private fun promptText(prompt: JsonElement?): String? {
    val text = when (prompt) {
        is JsonPrimitive -> prompt.contentOrNull
        else -> return null
    }
    if (text.isNullOrBlank()) return null
    if (text.length > MAX_PROMPT_CHARS) return null
    return text
}

private fun effectiveParams(request: LlamaCompletionRequest): GenerationParams {
    val defaults = GenerationParams()
    val nPredict = request.nPredict ?: -1
    if (nPredict < -1) {
        throw LlamaRequestException("'n_predict' must be >= -1, got $nPredict")
    }
    val maxTokens = if (nPredict == -1) defaults.maxTokens else nPredict
    if (maxTokens < 1) {
        // llama.cpp accepts 0 ("predict nothing"); the engine needs >= 1.
        throw LlamaRequestException("'n_predict' must be a positive integer or -1")
    }
    val temperature = request.temperature
    if (temperature != null && temperature < 0.0) {
        throw LlamaRequestException("'temperature' must be >= 0")
    }
    val topK = request.topK
    if (topK != null && topK < 0) {
        throw LlamaRequestException("'top_k' must be >= 0")
    }
    val topP = request.topP
    if (topP != null && (topP < 0.0 || topP > 1.0)) {
        throw LlamaRequestException("'top_p' must be between 0 and 1")
    }
    val slotId = request.slotId
    if (slotId != null && slotId != -1 && slotId != 0) {
        throw LlamaRequestException("This server has 1 slot (slot_id 0)")
    }
    return GenerationParams(
        temperature = temperature?.toFloat() ?: defaults.temperature,
        topK = topK?.takeIf { it > 0 } ?: defaults.topK,
        topP = topP?.toFloat() ?: defaults.topP,
        maxTokens = maxTokens,
    )
}

/** Echoes the effective settings back in the response (llama.cpp convention). */
private fun requestSettings(
    request: LlamaCompletionRequest,
    prompt: String,
    modelId: String,
    params: GenerationParams,
    engine: LiteRTLMEngineInterface,
): LlamaGenerationSettings {
    val base = defaultSettings(engine, modelId)
    return LlamaGenerationSettings(
        nCtx = base.nCtx,
        nPredict = params.maxTokens,
        model = modelId,
        prompt = prompt,
        temperature = params.temperature.toDouble(),
        topK = params.topK,
        topP = params.topP.toDouble(),
        minP = request.minP ?: base.minP,
        repeatPenalty = request.repeatPenalty ?: base.repeatPenalty,
        presencePenalty = request.presencePenalty ?: base.presencePenalty,
        frequencyPenalty = request.frequencyPenalty ?: base.frequencyPenalty,
        nKeep = request.nKeep ?: base.nKeep,
        seed = request.seed ?: base.seed,
        stop = request.stop?.let { stopList(it) } ?: base.stop,
        stream = request.stream,
        cachePrompt = request.cachePrompt ?: base.cachePrompt,
        echo = request.echo ?: base.echo,
        slotId = request.slotId ?: base.slotId,
        nProbs = request.nProbs ?: base.nProbs,
        samplers = request.samplers ?: base.samplers,
    )
}

private fun stopList(stop: JsonElement): List<String> = when (stop) {
    is JsonPrimitive -> listOfNotNull(stop.contentOrNull)
    is JsonArray -> stop.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    else -> emptyList()
}

private fun defaultSettings(
    engine: LiteRTLMEngineInterface,
    modelId: String?,
): LlamaGenerationSettings {
    val p = GenerationParams()
    return LlamaGenerationSettings(
        nCtx = engine.metrics.value.maxContextTokens.coerceAtLeast(p.contextTokens),
        nPredict = p.maxTokens,
        model = modelId ?: "",
        temperature = p.temperature.toDouble(),
        topK = p.topK,
        topP = p.topP.toDouble(),
    )
}

private suspend fun streamCompletion(
    call: ApplicationCall,
    json: Json,
    engine: LiteRTLMEngineInterface,
    modelId: String,
    prompt: String,
    params: GenerationParams,
    settings: LlamaGenerationSettings,
    flow: Flow<String>,
) {
    val startedAt = System.nanoTime()
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        val reply = StringBuilder()
        fun frame(chunk: LlamaCompletionChunk) {
            writeSse(json.encodeToString(LlamaCompletionChunk.serializer(), chunk))
            flush()
        }
        try {
            flow.collect { token ->
                if (token.isNotEmpty()) {
                    reply.append(token)
                    frame(
                        LlamaCompletionChunk(
                            content = token,
                            stop = false,
                            model = modelId,
                            tokensPredicted = estimate(reply.toString()),
                            tokensEvaluated = estimate(prompt),
                            truncated = truncated(params.maxTokens, reply.toString()),
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            // Client disconnected or aborted — normal for streams; the engine's
            // cancellation-safe finally resets metrics and the busy gate.
            Log.d(TAG, "Stream ended (client disconnected)")
            throw e
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        frame(
            LlamaCompletionChunk(
                content = "",
                stop = true,
                model = modelId,
                tokensPredicted = estimate(reply.toString()),
                tokensEvaluated = estimate(prompt),
                truncated = truncated(params.maxTokens, reply.toString()),
                generationSettings = settings,
                timings = timings(prompt, reply.toString(), elapsedMs),
            ),
        )
    }
}

private fun timings(prompt: String, reply: String, predictedMs: Long): LlamaTimings {
    val promptN = estimate(prompt)
    val predictedN = estimate(reply)
    val predictedPerSecond =
        if (predictedMs > 0) predictedN / (predictedMs / 1000.0) else 0.0
    return LlamaTimings(
        promptN = promptN,
        predictedN = predictedN,
        predictedMs = predictedMs.toDouble(),
        predictedPerSecond = predictedPerSecond,
    )
}

/** The engine stops exactly at maxTokens, which is llama.cpp's truncation case. */
private fun truncated(maxTokens: Int, reply: String): Boolean = estimate(reply) >= maxTokens

/** chars/4 — the same rough estimator the OpenAI usage numbers use. */
private fun estimate(text: String): Int = (text.length / 4).coerceAtLeast(0)

private fun Writer.writeSse(frame: String) {
    write("data: $frame\n\n")
}

/** Validation error mapped to an HTTP 400 (see the /completion handler). */
private class LlamaRequestException(message: String) : Exception(message)
