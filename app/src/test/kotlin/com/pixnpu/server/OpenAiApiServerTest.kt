package com.pixnpu.server

import android.content.Context
import com.google.ai.edge.litertlm.Content
import com.pixnpu.engine.ActiveBackend
import com.pixnpu.engine.EngineStatus
import com.pixnpu.engine.GenerationParams
import com.pixnpu.engine.InferenceMetrics
import com.pixnpu.engine.LiteRTLMEngineInterface
import com.pixnpu.engine.Modality
import com.pixnpu.engine.PromptTemplate
import com.pixnpu.model.LocalModel
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiApiServerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class FakeEngine(
        var loaded: Boolean = true,
        status: EngineStatus = EngineStatus.Ready,
        private val replyFlow: () -> Flow<String> = { flowOf("Hello", " world") },
    ) : LiteRTLMEngineInterface {
        var modelId: String? = null
        var lastTrackHistory: Boolean? = null
        var lastParamsOverride: GenerationParams? = null
        val generatedContents = mutableListOf<List<Content>>()

        private val _metrics = MutableStateFlow(InferenceMetrics(status = status, maxContextTokens = 8192))
        override val metrics: StateFlow<InferenceMetrics> = _metrics.asStateFlow()

        override val isLoaded: Boolean get() = loaded
        override suspend fun load(modelPath: String, params: GenerationParams, modality: Modality): ActiveBackend =
            ActiveBackend.CPU()

        override suspend fun reconfigure(params: GenerationParams, systemPrompt: String) = Unit

        override suspend fun unload() = Unit

        override fun generate(prompt: String, template: PromptTemplate): Flow<String> = flowOf("")

        override fun generate(
            content: List<Content>,
            template: PromptTemplate,
            trackHistory: Boolean,
            paramsOverride: GenerationParams?,
        ): Flow<String> {
            generatedContents.add(content)
            lastTrackHistory = trackHistory
            lastParamsOverride = paramsOverride
            return replyFlow()
        }

        override fun cancel() = Unit

        override suspend fun clearHistory() = Unit
    }

    private fun testServer(
        engine: FakeEngine,
        token: String? = null,
        models: List<LocalModel> = emptyList(),
        routerMode: Boolean = false,
        routerLoader: suspend (String) -> Boolean = { true },
        routerUnloader: suspend (String) -> Boolean = { true },
        block: suspend io.ktor.client.HttpClient.() -> Unit,
    ) {
        val context = mockk<Context>(relaxed = true)
        testApplication {
            application {
                openAiApiModule(
                    engine = engine,
                    context = context,
                    modelIdProvider = { engine.modelId },
                    tokenProvider = { token },
                    modelsProvider = { models },
                    routerModeProvider = { routerMode },
                    routerLoader = routerLoader,
                    routerUnloader = routerUnloader,
                )
            }
            val client = createClient { }
            client.block()
        }
    }

    // --- /v1/models ---

    @Test
    fun `models list reports loaded model`() = testServer(FakeEngine().apply { modelId = "gemma3-270m-it-q8" }) {
        val response = get("/v1/models")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<ModelListResponse>(response.bodyAsText())
        assertEquals(1, body.data.size)
        assertEquals("gemma3-270m-it-q8", body.data[0].id)
        assertEquals("model", body.data[0].obj)
    }

    @Test
    fun `models list reports meta context window for the loaded model`() =
        testServer(FakeEngine().apply { modelId = "gemma3-270m-it-q8" }) {
            val response = get("/v1/models")
            val body = json.decodeFromString<ModelListResponse>(response.bodyAsText())
            // Pi reads meta.n_ctx for its context-window fallback (128000);
            // the engine's actual context must win for loaded models.
            assertEquals(8192, body.data[0].meta?.nCtx)
        }

    @Test
    fun `models list is empty when nothing is loaded`() = testServer(FakeEngine()) {
        val response = get("/v1/models")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<ModelListResponse>(response.bodyAsText())
        assertTrue(body.data.isEmpty())
    }

    // --- /health ---

    @Test
    fun `health returns ok status`() = testServer(FakeEngine()) {
        val response = get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
    }

    @Test
    fun `health reports busy while generating`() =
        testServer(FakeEngine(status = EngineStatus.Generating)) {
            val response = get("/health")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("""{"status":"error","slots_idle":0}""", response.bodyAsText())
        }

    // --- service identity ---

    @Test
    fun `service advertises as llama cpp`() = testServer(FakeEngine()) {
        val response = get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<ServiceInfo>(response.bodyAsText())
        assertEquals("llama.cpp", body.service)
        assertEquals("pixnpu", body.impl)
        assertEquals("single-model", body.mode)
    }

    @Test
    fun `service advertises router mode`() = testServer(FakeEngine(), routerMode = true) {
        val response = get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<ServiceInfo>(response.bodyAsText())
        assertEquals("router", body.mode)
    }

    // --- router mode ---

    private val routerModels = listOf(
        LocalModel("gemma3-270m-it-q8.litertlm", "/models/gemma3-270m-it-q8.litertlm", 100, 1000, null, false),
        LocalModel("llama3-2.litertlm", "/models/llama3-2.litertlm", 200, 2000, null, false),
    )

    @Test
    fun `router models list reports all installed models with status`() =
        testServer(FakeEngine().apply { modelId = "gemma3-270m-it-q8" }, models = routerModels, routerMode = true) {
            val response = get("/v1/models")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<ModelListResponse>(response.bodyAsText())
            assertEquals(2, body.data.size)
            assertEquals("gemma3-270m-it-q8", body.data[0].id)
            assertEquals("loaded", body.data[0].status?.value)
            assertEquals("not loaded", body.data[1].status?.value)
            assertEquals("llamacpp", body.data[0].ownedBy)
        }

    @Test
    fun `router completion auto-loads the requested model`() {
        val engine = FakeEngine(loaded = false)
        var loaded: String? = null
        testServer(
            engine,
            models = routerModels,
            routerMode = true,
            routerLoader = { id -> loaded = id; true },
        ) {
            val response = post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"llama3-2","messages":[{"role":"user","content":"Hi"}]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<ChatCompletionResponse>(response.bodyAsText())
            assertEquals("llama3-2", body.model)
        }
        assertEquals("llama3-2", loaded)
    }

    @Test
    fun `router completion does not reload the already loaded model`() {
        val engine = FakeEngine().apply { modelId = "gemma3-270m-it-q8" }
        var loadCalls = 0
        testServer(
            engine,
            models = routerModels,
            routerMode = true,
            routerLoader = { id -> loadCalls++; true },
        ) {
            val response = post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gemma3-270m-it-q8","messages":[{"role":"user","content":"Hi"}]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertEquals(0, loadCalls)
    }

    @Test
    fun `router completion 404s for an unknown model`() =
        testServer(FakeEngine(loaded = false), models = routerModels, routerMode = true) {
            val response = post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"nope","messages":[{"role":"user","content":"Hi"}]}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = json.decodeFromString<ApiError>(response.bodyAsText())
            assertEquals("model_not_found", body.error.code)
        }

    @Test
    fun `router completion 503s when the model fails to load`() =
        testServer(FakeEngine(loaded = false), models = routerModels, routerMode = true, routerLoader = { false }) {
            val response = post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"llama3-2","messages":[{"role":"user","content":"Hi"}]}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val body = json.decodeFromString<ApiError>(response.bodyAsText())
            assertEquals("model_load_failed", body.error.code)
        }

    @Test
    fun `router completion without a model and nothing loaded returns 400`() =
        testServer(FakeEngine(loaded = false), models = routerModels, routerMode = true) {
            val response = post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"messages":[{"role":"user","content":"Hi"}]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<ApiError>(response.bodyAsText())
            assertEquals("no_model_loaded", body.error.code)
        }

    // --- non-streaming completion ---

    @Test
    fun `non-streaming completion returns full reply`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m","messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<ChatCompletionResponse>(response.bodyAsText())
        assertEquals("chat.completion", body.obj)
        assertEquals("m", body.model)
        assertEquals("Hello world", body.choices[0].message.content)
        assertEquals("stop", body.choices[0].finishReason)
    }

    @Test
    fun `completion is stateless and passes request params`() {
        val engine = FakeEngine().apply { modelId = "m" }
        testServer(engine) {
            post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m","temperature":1.2,"max_tokens":42,"messages":[{"role":"user","content":"Hi"}]}""")
            }
        }
        assertEquals(false, engine.lastTrackHistory)
        assertEquals(1.2f, engine.lastParamsOverride?.temperature)
        assertEquals(42, engine.lastParamsOverride?.maxTokens)
    }

    @Test
    fun `top_p maps to engine params`() {
        val engine = FakeEngine().apply { modelId = "m" }
        testServer(engine) {
            post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m","top_p":0.3,"messages":[{"role":"user","content":"Hi"}]}""")
            }
        }
        assertEquals(0.3f, engine.lastParamsOverride?.topP)
    }

    @Test
    fun `max_completion_tokens maps to max tokens`() {
        val engine = FakeEngine().apply { modelId = "m" }
        testServer(engine) {
            post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m","max_completion_tokens":77,"messages":[{"role":"user","content":"Hi"}]}""")
            }
        }
        assertEquals(77, engine.lastParamsOverride?.maxTokens)
    }

    @Test
    fun `both max_tokens and max_completion_tokens return 400`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m","max_tokens":10,"max_completion_tokens":10,"messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `n greater than 1 returns 400`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m","n":2,"messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("n", body.error.param)
    }

    // --- streaming ---

    @Test
    fun `streaming completion emits SSE frames and DONE`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m","stream":true,"messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/event-stream", response.contentType()?.withoutParameters()?.toString())
        assertEquals("no-cache", response.headers["Cache-Control"])
        val body = response.bodyAsText()
        assertTrue(body.contains("\"object\":\"chat.completion.chunk\""))
        assertTrue(body.contains("\"delta\":{\"role\":\"assistant\""))
        assertTrue(body.contains("\"content\":\"Hello\""))
        assertTrue(body.contains("\"content\":\" world\""))
        assertTrue(body.contains("\"finish_reason\":\"stop\""))
        assertTrue(body.trimEnd().endsWith("data: [DONE]"))
    }

    @Test
    fun `stream with include_usage emits usage chunk before DONE`() =
        testServer(FakeEngine().apply { modelId = "m" }) {
            val response = post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"m","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"Hi"}]}""",
                )
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"choices\":[],\"usage\":{\"prompt_tokens\""))
            val usageIndex = body.indexOf("\"usage\"")
            val doneIndex = body.indexOf("data: [DONE]")
            assertTrue(usageIndex != -1 && doneIndex > usageIndex)
        }

    // --- auth ---

    @Test
    fun `requests without token are rejected with 401 when token configured`() =
        testServer(FakeEngine().apply { modelId = "m" }, token = "secret") {
            val response = post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m","messages":[{"role":"user","content":"Hi"}]}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = json.decodeFromString<ApiError>(response.bodyAsText())
            assertEquals("invalid_api_key", body.error.code)
        }

    @Test
    fun `requests with wrong token are rejected with 401`() =
        testServer(FakeEngine().apply { modelId = "m" }, token = "secret") {
            val response = post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer wrong")
                setBody("""{"model":"m","messages":[{"role":"user","content":"Hi"}]}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `requests with correct bearer token succeed`() =
        testServer(FakeEngine().apply { modelId = "m" }, token = "secret") {
            val response = post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer secret")
                setBody("""{"model":"m","messages":[{"role":"user","content":"Hi"}]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `models list requires token when configured`() =
        testServer(FakeEngine().apply { modelId = "m" }, token = "secret") {
            val response = get("/v1/models")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val authed = get("/v1/models") {
                header("Authorization", "Bearer secret")
            }
            assertEquals(HttpStatusCode.OK, authed.status)
        }

    // --- errors ---

    @Test
    fun `unknown model id returns 404 model_not_found`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"other","messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("model_not_found", body.error.code)
    }

    @Test
    fun `empty messages returns 400`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m","messages":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `malformed json returns 400`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = json.decodeFromString<ApiError>(response.bodyAsText())
        assertTrue(body.error.message!!.contains("Malformed request body"))
    }

    @Test
    fun `busy engine returns 429`() = testServer(FakeEngine(status = EngineStatus.Generating).apply { modelId = "m" }) {
        val response = post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m","messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        val body = json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("busy", body.error.code)
    }

    @Test
    fun `no loaded engine returns 400 no_model_loaded`() = testServer(FakeEngine(loaded = false).apply { modelId = "m" }) {
        val response = post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m","messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("no_model_loaded", body.error.code)
    }

    @Test
    fun `unknown route returns 404`() = testServer(FakeEngine()) {
        val response = get("/v1/embeddings")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // --- root /chat/completions (Pi llama.cpp extension) ---

    @Test
    fun `root chat completions route works without v1 prefix`() =
        testServer(FakeEngine().apply { modelId = "m" }) {
            // Pi's openai-completions provider sets baseURL to the server URL
            // without /v1, so the SDK posts to POST /chat/completions — this
            // was a 404 (empty body) before the dual mount.
            val response = post("/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m","messages":[{"role":"user","content":"Hi"}]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<ChatCompletionResponse>(response.bodyAsText())
            assertEquals("m", body.model)
            assertTrue(body.choices[0].message.content == "Hello world")
        }

    @Test
    fun `root chat completions requires auth like the v1 route`() =
        testServer(FakeEngine().apply { modelId = "m" }, token = "secret") {
            val response = post("/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m","messages":[{"role":"user","content":"Hi"}]}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `root chat completions streams like the v1 route`() =
        testServer(FakeEngine().apply { modelId = "m" }, routerLoader = { true }) {
            val response = post("/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m","stream":true,"messages":[{"role":"user","content":"Hi"}]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("data: [DONE]"))
            assertTrue(text.contains("""{"role":"assistant"""") || text.contains("\"content\""))
        }
}
