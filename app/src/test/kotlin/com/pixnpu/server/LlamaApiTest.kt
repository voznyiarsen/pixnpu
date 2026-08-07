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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class FakeEngine(
        var loaded: Boolean = true,
        status: EngineStatus = EngineStatus.Ready,
        private val replyFlow: () -> Flow<String> = { flowOf("Hello", " world") },
    ) : LiteRTLMEngineInterface {
        var modelId: String? = null
        var modelPath: String? = null
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
                    modelPathProvider = { engine.modelPath },
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

    // --- POST /completion (non-streaming) ---

    @Test
    fun `completion returns full reply with llama schema`() =
        testServer(FakeEngine().apply { modelId = "m"; modelPath = "/models/m.litertlm" }) {
            val response = post("/completion") {
                contentType(ContentType.Application.Json)
                setBody("""{"prompt":"Hi"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<LlamaCompletionResponse>(response.bodyAsText())
            assertEquals("Hello world", body.content)
            assertTrue(body.stop)
            assertEquals("m", body.model)
            assertEquals("Hi", body.generationSettings.prompt)
            assertEquals(2, body.timings.predictedN)
            assertFalse(body.truncated)
        }

    @Test
    fun `completion passes raw prompt and maps params`() {
        val engine = FakeEngine().apply { modelId = "m" }
        testServer(engine) {
            post("/completion") {
                contentType(ContentType.Application.Json)
                setBody("""{"prompt":"Hi","n_predict":42,"temperature":1.2,"top_k":20,"top_p":0.3}""")
            }
        }
        assertEquals(listOf(Content.Text("Hi")), engine.generatedContents.last())
        assertEquals(false, engine.lastTrackHistory)
        assertEquals(42, engine.lastParamsOverride?.maxTokens)
        assertEquals(1.2f, engine.lastParamsOverride?.temperature)
        assertEquals(20, engine.lastParamsOverride?.topK)
        assertEquals(0.3f, engine.lastParamsOverride?.topP)
    }

    @Test
    fun `n_predict -1 falls back to engine default`() {
        val engine = FakeEngine().apply { modelId = "m" }
        testServer(engine) {
            post("/completion") {
                contentType(ContentType.Application.Json)
                setBody("""{"prompt":"Hi","n_predict":-1}""")
            }
        }
        assertEquals(GenerationParams().maxTokens, engine.lastParamsOverride?.maxTokens)
    }

    @Test
    fun `completion echoes prompt when echo is true`() =
        testServer(FakeEngine().apply { modelId = "m" }) {
            val response = post("/completion") {
                contentType(ContentType.Application.Json)
                setBody("""{"prompt":"Hi","echo":true}""")
            }
            val body = json.decodeFromString<LlamaCompletionResponse>(response.bodyAsText())
            assertEquals("HiHello world", body.content)
        }

    @Test
    fun `token-array prompt returns 400`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/completion") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":[1,2,3]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `n_predict below -1 returns 400`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/completion") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"Hi","n_predict":-5}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `slot_id beyond 0 returns 400`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/completion") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"Hi","slot_id":3}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `missing prompt returns 400`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/completion") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `busy engine returns 503 slot busy`() = testServer(FakeEngine(status = EngineStatus.Generating).apply { modelId = "m" }) {
        val response = post("/completion") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"Hi"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("Slot busy"))
    }

    @Test
    fun `no loaded engine returns 503`() = testServer(FakeEngine(loaded = false).apply { modelId = "m" }) {
        val response = post("/completion") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"Hi"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    // --- POST /completion (streaming) ---

    @Test
    fun `streaming completion emits llama frames and stops without DONE`() =
        testServer(FakeEngine().apply { modelId = "m" }) {
            val response = post("/completion") {
                contentType(ContentType.Application.Json)
                setBody("""{"prompt":"Hi","stream":true}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("text/event-stream", response.contentType()?.withoutParameters()?.toString())
            val body = response.bodyAsText()
            assertTrue(body.contains("\"content\":\"Hello\",\"stop\":false"))
            assertTrue(body.contains("\"content\":\" world\",\"stop\":false"))
            assertTrue(body.contains("\"stop\":true"))
            assertTrue(body.contains("\"generation_settings\""))
            assertTrue(body.contains("\"timings\""))
            assertFalse(body.contains("[DONE]"))
        }

    // --- GET /props ---

    @Test
    fun `props reports defaults and model path`() =
        testServer(FakeEngine().apply { modelId = "m"; modelPath = "/models/m.litertlm" }) {
            val response = get("/props")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<LlamaProps>(response.bodyAsText())
            assertEquals(1, body.totalSlots)
            assertEquals("/models/m.litertlm", body.modelPath)
            assertEquals(8192, body.defaultGenerationSettings.nCtx)
            assertEquals(1024, body.defaultGenerationSettings.nPredict)
            assertEquals(0.7, body.defaultGenerationSettings.temperature, 1e-6)
        }

    @Test
    fun `props reports jinja chat template for known model families`() =
        testServer(FakeEngine().apply { modelId = "gemma3-270m-it-q8"; modelPath = "/models/gemma3-270m-it-q8.litertlm" }) {
            val response = get("/props")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<LlamaProps>(response.bodyAsText())
            assertNotNull(body.chatTemplate)
            assertTrue(body.chatTemplate!!.contains("<start_of_turn>"))
            assertEquals("<bos>", body.bosToken)
            assertEquals("<eos>", body.eosToken)
        }

    @Test
    fun `props reports null chat template for unknown models`() =
        testServer(FakeEngine().apply { modelId = "m" }) {
            val response = get("/props")
            val body = json.decodeFromString<LlamaProps>(response.bodyAsText())
            assertEquals(null, body.chatTemplate)
            assertEquals(null, body.bosToken)
        }

    @Test
    fun `props reports router fields in router mode`() =
        testServer(FakeEngine(), models = routerModels, routerMode = true) {
            val response = get("/props")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<LlamaProps>(response.bodyAsText())
            assertEquals("router", body.role)
            assertEquals("llama-server", body.modelAlias)
            assertEquals("none", body.modelPath)
            assertEquals(1, body.maxInstances)
            assertEquals(true, body.modelsAutoload)
        }

    @Test
    fun `props with model param reports that model's path and template`() =
        testServer(FakeEngine(), models = routerModels, routerMode = true) {
            val response = get("/props?model=llama3-2")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<LlamaProps>(response.bodyAsText())
            assertEquals("/models/llama3-2.litertlm", body.modelPath)
            assertEquals("llama3-2", body.modelAlias)
            assertNotNull(body.chatTemplate)
            assertTrue(body.chatTemplate!!.contains("start_header_id"))
        }

    @Test
    fun `props with unknown model param returns 404 in router mode`() =
        testServer(FakeEngine(), models = routerModels, routerMode = true) {
            val response = get("/props?model=nope")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // --- router model management ---

    private val routerModels = listOf(
        LocalModel("gemma3-270m-it-q8.litertlm", "/models/gemma3-270m-it-q8.litertlm", 100, 1000, null, false),
        LocalModel("llama3-2.litertlm", "/models/llama3-2.litertlm", 200, 2000, null, false),
    )

    @Test
    fun `models lists all models with status in router mode`() =
        testServer(FakeEngine().apply { modelId = "gemma3-270m-it-q8" }, models = routerModels, routerMode = true) {
            val response = get("/models")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<ModelListResponse>(response.bodyAsText())
            assertEquals(2, body.data.size)
            assertEquals("loaded", body.data[0].status?.value)
            assertEquals("not loaded", body.data[1].status?.value)
        }

    @Test
    fun `models returns 404 when router mode is disabled`() = testServer(FakeEngine()) {
        val response = get("/models")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `models load loads a model by id`() {
        var loadedId: String? = null
        testServer(
            FakeEngine(),
            models = routerModels,
            routerMode = true,
            routerLoader = { id -> loadedId = id; true },
        ) {
            val response = post("/models/load") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"llama3-2"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("success"))
        }
        assertEquals("llama3-2", loadedId)
    }

    @Test
    fun `models load rejects already loaded model`() =
        testServer(FakeEngine().apply { modelId = "llama3-2" }, models = routerModels, routerMode = true) {
            val response = post("/models/load") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"llama3-2"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<LlamaError>(response.bodyAsText())
            assertTrue(body.error.contains("already loaded"))
        }

    @Test
    fun `models load rejects unknown model`() =
        testServer(FakeEngine(), models = routerModels, routerMode = true) {
            val response = post("/models/load") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"nope"}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `models load 503 when the load fails`() =
        testServer(FakeEngine(), models = routerModels, routerMode = true, routerLoader = { false }) {
            val response = post("/models/load") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"llama3-2"}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

    @Test
    fun `models unload unloads the loaded model`() {
        var unloadedId: String? = null
        testServer(
            FakeEngine().apply { modelId = "llama3-2" },
            models = routerModels,
            routerMode = true,
            routerUnloader = { id -> unloadedId = id; true },
        ) {
            val response = post("/models/unload") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"llama3-2"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("success"))
        }
        assertEquals("llama3-2", unloadedId)
    }

    @Test
    fun `models unload rejects a model that is not loaded`() =
        testServer(FakeEngine().apply { modelId = "gemma3-270m-it-q8" }, models = routerModels, routerMode = true) {
            val response = post("/models/unload") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"llama3-2"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<LlamaError>(response.bodyAsText())
            assertTrue(body.error.contains("not loaded"))
        }

    // --- GET /slots ---

    @Test
    fun `slots reports idle when not generating`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = get("/slots")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<List<LlamaSlot>>(response.bodyAsText())
        assertEquals(1, body.size)
        assertEquals("idle", body[0].state)
        assertEquals("m", body[0].model)
    }

    @Test
    fun `slots reports processing while generating`() =
        testServer(FakeEngine(status = EngineStatus.Generating).apply { modelId = "m" }) {
            val response = get("/slots")
            val body = json.decodeFromString<List<LlamaSlot>>(response.bodyAsText())
            assertEquals("processing", body[0].state)
        }

    // --- /tokenize and /detokenize ---

    @Test
    fun `tokenize returns 501`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/tokenize") {
            contentType(ContentType.Application.Json)
            setBody("""{"content":"Hello"}""")
        }
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        val body = json.decodeFromString<LlamaError>(response.bodyAsText())
        assertNotNull(body.error)
    }

    @Test
    fun `detokenize returns 501`() = testServer(FakeEngine().apply { modelId = "m" }) {
        val response = post("/detokenize") {
            contentType(ContentType.Application.Json)
            setBody("""{"tokens":[1,2,3]}""")
        }
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        val body = json.decodeFromString<LlamaError>(response.bodyAsText())
        assertNotNull(body.error)
    }

    // --- auth ---

    @Test
    fun `llama endpoints require token when configured`() =
        testServer(FakeEngine().apply { modelId = "m" }, token = "secret") {
            val response = post("/completion") {
                contentType(ContentType.Application.Json)
                setBody("""{"prompt":"Hi"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val authed = post("/completion") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer secret")
                setBody("""{"prompt":"Hi"}""")
            }
            assertEquals(HttpStatusCode.OK, authed.status)
        }
}
