package com.pixnpu.server

import android.content.Context
import android.util.Log
import com.pixnpu.engine.EngineStatus
import com.pixnpu.engine.LiteRTLMEngineInterface
import com.pixnpu.engine.PromptTemplate
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.Writer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

private const val TAG = "OpenAiApiServer"

/**
 * Minimal OpenAI-compatible HTTP API (Ktor/CIO).
 *
 * Endpoints:
 *  - GET  /health, /          basic service info
 *  - GET  /v1/models          currently loaded model
 *  - POST /v1/chat/completions chat completions, JSON or SSE streaming
 *  - POST /completion, GET /props, GET /slots, POST /tokenize, POST /detokenize
 *    llama.cpp server-compatible API (see LlamaApi.kt)
 *
 * Binds to loopback (127.0.0.1) by default. The bind address is configurable
 * from Settings (e.g. 0.0.0.0 to expose it on the LAN — there is no auth, so
 * only do that on trusted networks).
 *
 * Stateless: each request maps the full messages array onto the engine with
 * trackHistory=false, so API calls never read from or write to the app chat.
 * Only one generation can run at a time (shared engine with the UI); a second
 * request gets HTTP 429 until the first finishes.
 */
class OpenAiApiServer(
    private val context: Context,
    private val engine: LiteRTLMEngineInterface,
) {
    companion object {
        /** Loopback only — the safe default. */
        const val HOST = "127.0.0.1"
        const val PORT = 8080
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** True for loopback-style bind addresses where no extra warning is needed. */
        fun isLoopback(host: String): Boolean =
            host == "127.0.0.1" || host == "localhost" || host == "::1" || host.startsWith("127.")
    }

    /**
     * Id and on-disk path of the currently loaded model (set by the ViewModel on
     * load/unload). Used for /v1/models, /props and model validation. Exposed
     * read-only; mutated only via [setCurrentModel].
     */
    private val modelIdRef = AtomicReference<String?>(null)
    private val modelPathRef = AtomicReference<String?>(null)

    val currentModelId: String? get() = modelIdRef.get()

    fun setCurrentModel(id: String?, path: String? = null) {
        modelIdRef.set(id)
        modelPathRef.set(path)
    }

    @Volatile
    private var server: CIOApplicationEngine? = null

    /** Serializes start()/stop() so a race cannot double-start or leak a server. */
    private val lifecycleLock = Any()

    val isRunning: Boolean get() = server != null

    /**
     * Starts the server bound to the given host/port. Blocks until startup
     * completes; call from Dispatchers.IO. Idempotent: a second call while
     * running is a no-op.
     *
     * @param tokenProvider Supplies the required API token (null/blank = no auth).
     *        Invoked per request, so changing the token while running applies to
     *        new requests.
     */
    fun start(
        host: String = HOST,
        port: Int = PORT,
        tokenProvider: () -> String? = { null },
    ) {
        synchronized(lifecycleLock) {
            if (server != null) {
                Log.w(TAG, "start() called while already running")
                return
            }
            val instance = embeddedServer(CIO, host = host, port = port) {
                openAiApiModule(
                    engine,
                    context,
                    modelIdProvider = { currentModelId },
                    modelPathProvider = { modelPathRef.get() },
                    tokenProvider = tokenProvider,
                )
            }
            server = instance
            instance.start(wait = false)
            Log.i(TAG, "API server listening on http://$host:$port")
        }
    }

    /**
     * Stops the server, waiting up to 2s for in-flight requests. Blocks the
     * calling thread briefly; call from Dispatchers.IO. Idempotent.
     */
    fun stop() {
        synchronized(lifecycleLock) {
            val instance = server ?: return
            instance.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
            server = null
            Log.i(TAG, "API server stopped")
        }
    }
}

/**
 * Top-level module so tests can mount the same routes via ktor testApplication.
 */
fun Application.openAiApiModule(
    engine: LiteRTLMEngineInterface,
    context: Context,
    modelIdProvider: () -> String?,
    modelPathProvider: () -> String? = { null },
    tokenProvider: () -> String? = { null },
) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    val processor = ChatCompletionsProcessor(context)
    val inFlight = AtomicBoolean(false)

    install(ContentNegotiation) {
        json(json)
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
    }

    fun unauthorized(call: ApplicationCall): Boolean {
        val expected = tokenProvider()
        if (expected.isNullOrEmpty()) return false
        val provided = call.request.headers[HttpHeaders.Authorization]
            ?.substringAfter(" ")
            ?.trim()
        return provided != expected
    }

    routing {
        get("/") { call.respond(ServiceInfo()) }
        get("/health") { call.respond(ServiceInfo()) }

        get("/v1/models") {
            if (unauthorized(call)) {
                return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(ErrorBody("Incorrect API key provided", code = "invalid_api_key")),
                )
            }
            val modelId = modelIdProvider()
            val data = modelId?.let {
                listOf(ModelInfo(id = it, created = System.currentTimeMillis() / 1000))
            } ?: emptyList()
            call.respond(ModelListResponse(data = data))
        }

        post("/v1/chat/completions") {
            if (unauthorized(call)) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(ErrorBody("Incorrect API key provided", code = "invalid_api_key")),
                )
            }
            val request = try {
                call.receive<ChatCompletionRequest>()
            } catch (e: Exception) {
                Log.w(TAG, "Malformed request body: ${e.message}")
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(ErrorBody("Malformed request body: ${e.message}")),
                )
            }
            try {
                handleChatCompletion(call, engine, processor, json, modelIdProvider, inFlight, request)
            } catch (e: ChatCompletionError) {
                call.respond(
                    HttpStatusCode.fromValue(e.status),
                    ApiError(ErrorBody(e.message ?: "error", e.errorType, e.param, e.code)),
                )
            } catch (e: IllegalArgumentException) {
                // Engine input validation (e.g. oversized prompts) is a client error.
                Log.w(TAG, "Bad request rejected by engine: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(ErrorBody(e.message ?: "Invalid request", param = null)),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Chat completion failed", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiError(ErrorBody("Internal server error", "server_error", null, "internal_error")),
                )
            }
        }

        // llama.cpp server-compatible endpoints share the same engine and the
        // same single-generation busy gate as the OpenAI API above.
        llamaApiRoutes(
            engine = engine,
            json = json,
            modelIdProvider = modelIdProvider,
            modelPathProvider = modelPathProvider,
            tokenProvider = tokenProvider,
            inFlight = inFlight,
        )
    }
}

private suspend fun handleChatCompletion(
    call: ApplicationCall,
    engine: LiteRTLMEngineInterface,
    processor: ChatCompletionsProcessor,
    json: Json,
    modelIdProvider: () -> String?,
    inFlight: AtomicBoolean,
    request: ChatCompletionRequest,
) {
    val modelId = modelIdProvider()
    if (!engine.isLoaded) throw ChatCompletionError.NoModelLoaded()
    if (request.model != null && request.model != modelId) {
        throw ChatCompletionError.ModelNotFound(request.model)
    }
    if (engine.metrics.value.status == EngineStatus.Generating || !inFlight.compareAndSet(false, true)) {
        throw ChatCompletionError.Busy()
    }
    try {
        val contents = processor.buildContent(request)
        val params = processor.effectiveParams(request)
        val flow = engine.generate(
            contents,
            template = PromptTemplate.Auto,
            trackHistory = false,
            paramsOverride = params,
        )
        val id = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000
        val model = modelId ?: "unknown"
        val promptText = contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
            .joinToString(" ") { it.text }

        if (request.stream) {
            val includeUsage = request.streamOptions?.includeUsage == true
            streamCompletion(
                call,
                json,
                id,
                created,
                model,
                promptText,
                includeUsage,
                processor,
                flow,
            )
        } else {
            val reply = buildString { flow.collect { append(it) } }
            call.respond(
                ChatCompletionResponse(
                    id = id,
                    created = created,
                    model = model,
                    choices = listOf(Choice(message = ResponseMessage(content = reply))),
                    usage = processor.estimateUsage(promptText, reply),
                ),
            )
        }
    } finally {
        inFlight.set(false)
    }
}

private suspend fun streamCompletion(
    call: ApplicationCall,
    json: Json,
    id: String,
    created: Long,
    model: String,
    promptText: String,
    includeUsage: Boolean,
    processor: ChatCompletionsProcessor,
    flow: Flow<String>,
) {
    fun chunk(delta: ChunkDelta, finish: String?, usage: Usage? = null): String =
        json.encodeToString(ChatCompletionChunk.serializer(), ChatCompletionChunk(
            id = id,
            created = created,
            model = model,
            choices = if (usage == null) {
                listOf(ChunkChoice(index = 0, delta = delta, finishReason = finish))
            } else {
                // OpenAI sends the usage frame with an empty choices array.
                emptyList()
            },
            usage = usage,
        ))
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        // Role frame first (OpenAI SSE convention: role + empty content).
        writeSse(chunk(ChunkDelta(role = "assistant", content = ""), null))
        flush()

        val reply = StringBuilder()
        try {
            flow.collect { token ->
                if (token.isNotEmpty()) {
                    reply.append(token)
                    writeSse(chunk(ChunkDelta(content = token), null))
                    flush()
                }
            }
        } catch (e: CancellationException) {
            // Client disconnected or the call was aborted — normal for streams,
            // not an error. The engine flow is cancelled and its cancellation-safe
            // finally resets metrics + the busy gate.
            Log.d(TAG, "Stream ended (client disconnected or aborted)")
            throw e
        } catch (e: Exception) {
            // I/O failure writing to a dead connection.
            Log.d(TAG, "Stream write failed: ${e.message}")
            return@respondTextWriter
        }
        writeSse(chunk(ChunkDelta(), "stop"))
        if (includeUsage) {
            writeSse(chunk(ChunkDelta(), null, usage = processor.estimateUsage(promptText, reply.toString())))
        }
        writeSse("[DONE]")
        flush()
    }
}

private fun Writer.writeSse(frame: String) {
    write("data: $frame\n\n")
}
