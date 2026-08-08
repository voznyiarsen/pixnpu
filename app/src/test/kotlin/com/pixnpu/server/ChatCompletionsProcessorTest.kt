package com.pixnpu.server

import android.content.Context
import com.google.ai.edge.litertlm.Content
import com.pixnpu.engine.GenerationParams
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatCompletionsProcessorTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var processor: ChatCompletionsProcessor

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.cacheDir } returns tempDir.root
        processor = ChatCompletionsProcessor(context)
    }

    private fun request(vararg messages: Pair<String, String>) = ChatCompletionRequest(
        messages = messages.map { (role, content) ->
            ChatMessage(role = role, content = JsonPrimitive(content))
        },
    )

    // --- buildContent: roles ---

    @Test
    fun `text messages are role-prefixed and merged into one item`() {
        val contents = processor.buildContent(
            request(
                "system" to "Be concise",
                "user" to "Hello",
                "assistant" to "Hi there",
                "user" to "Again",
            ),
        )
        assertEquals(1, contents.size)
        assertEquals(
            Content.Text("System: Be concise\nUser: Hello\nAssistant: Hi there\nUser: Again"),
            contents[0],
        )
    }

    @Test
    fun `long text conversations merge into one item under the engine cap`() {
        // Pi sends the full message history on every request; 11+ messages used
        // to 400 with "Content exceeds maximum of 10 items" because each message
        // became a separate Content.Text. Merged, any text length is fine.
        val messages = (1..11).map { i -> "user" to "message $i" }
        val contents = processor.buildContent(request(*messages.toTypedArray()))
        assertEquals(1, contents.size)
        assertTrue(contents[0].toString().contains("message 11"))
    }

    @Test
    fun `blank message content is skipped`() {
        val contents = processor.buildContent(request("user" to "   ", "user" to "Hi"))
        assertEquals(1, contents.size)
        assertEquals(Content.Text("User: Hi"), contents[0])
    }

    @Test
    fun `empty messages list throws BadRequest`() {
        val e = assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.buildContent(ChatCompletionRequest())
        }
        assertTrue(e.message!!.contains("messages"))
    }

    @Test
    fun `unsupported role throws BadRequest`() {
        val e = assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.buildContent(request("tool" to "x"))
        }
        assertTrue(e.message!!.contains("Unsupported role 'tool'"))
    }

    @Test
    fun `developer role maps to system prefix`() {
        // Pi (and other modern OpenAI clients) send the `developer` role for
        // system instructions; it must not 400 like the old guard did.
        val contents = processor.buildContent(
            request(
                "developer" to "Be concise",
                "user" to "Hello",
            ),
        )
        assertEquals(1, contents.size)
        assertEquals(Content.Text("System: Be concise\nUser: Hello"), contents[0])
    }

    // --- buildContent: content parts ---

    @Test
    fun `content part array maps text and image_url data uri`() {
        val b64 = "iVBORw0KGgo="
        val request = ChatCompletionRequest(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = json.parseToJsonElement(
                        """[
                            {"type":"text","text":"describe this"},
                            {"type":"image_url","image_url":{"url":"data:image/png;base64,$b64"}}
                        ]""",
                    ),
                ),
            ),
        )
        val contents = processor.buildContent(request)
        assertEquals(2, contents.size)
        assertTrue(contents[0] is Content.ImageFile)
        val path = (contents[0] as Content.ImageFile).absolutePath
        assertTrue(File(path).isFile)
        assertEquals(b64, java.util.Base64.getEncoder().encodeToString(File(path).readBytes()))
        // Text is merged into a single trailing item (media first, text last).
        assertEquals(Content.Text("User: describe this"), contents[1])
    }

    @Test
    fun `more than ten media parts throws BadRequest`() {
        // Only media can exceed the engine's content cap now that text merges
        // into one item; it must surface as a clean 400, not a 500.
        val parts = (1..11).joinToString(",") {
            """{"type":"image_url","image_url":{"url":"data:image/png;base64,aGVsbG8="}}"""
        }
        val request = ChatCompletionRequest(
            messages = listOf(
                ChatMessage(role = "user", content = json.parseToJsonElement("[$parts]")),
            ),
        )
        val e = assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.buildContent(request)
        }
        assertTrue(e.message!!.contains("Content exceeds maximum of 10 items"))
    }

    @Test
    fun `image_url with string url is accepted`() {
        val request = ChatCompletionRequest(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = json.parseToJsonElement(
                        """[{"type":"image_url","image_url":"data:image/png;base64,aGVsbG8="}]""",
                    ),
                ),
            ),
        )
        val contents = processor.buildContent(request)
        assertEquals(1, contents.size)
        assertTrue(contents[0] is Content.ImageFile)
    }

    @Test
    fun `unsupported image url scheme throws BadRequest`() {
        val request = ChatCompletionRequest(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = json.parseToJsonElement(
                        """[{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}]""",
                    ),
                ),
            ),
        )
        val e = assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.buildContent(request)
        }
        assertTrue(e.message!!.contains("Unsupported image_url scheme"))
    }

    @Test
    fun `input_audio part maps to audio bytes`() {
        val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
        val request = ChatCompletionRequest(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = json.parseToJsonElement(
                        """[{"type":"input_audio","input_audio":{"data":"${java.util.Base64.getEncoder().encodeToString(wav)}","format":"wav"}}]""",
                    ),
                ),
            ),
        )
        val contents = processor.buildContent(request)
        assertEquals(1, contents.size)
        val audio = contents[0] as Content.AudioBytes
        assertTrue(audio.bytes.contentEquals(wav))
    }

    @Test
    fun `unknown part type throws BadRequest`() {
        val request = ChatCompletionRequest(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = json.parseToJsonElement("""[{"type":"video"}]"""),
                ),
            ),
        )
        assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.buildContent(request)
        }
    }

    // --- effectiveParams ---

    @Test
    fun `request params override defaults`() {
        val params = processor.effectiveParams(
            ChatCompletionRequest(temperature = 1.2, topP = 0.5, maxTokens = 512),
        )
        assertEquals(1.2f, params.temperature)
        assertEquals(0.5f, params.topP)
        assertEquals(512, params.maxTokens)
    }

    @Test
    fun `missing params fall back to defaults`() {
        val params = processor.effectiveParams(ChatCompletionRequest())
        assertEquals(GenerationParams().temperature, params.temperature)
        assertEquals(GenerationParams().topP, params.topP)
        assertEquals(GenerationParams().maxTokens, params.maxTokens)
    }

    @Test
    fun `max_completion_tokens is accepted as alias`() {
        val params = processor.effectiveParams(ChatCompletionRequest(maxCompletionTokens = 256))
        assertEquals(256, params.maxTokens)
    }

    @Test
    fun `both max_tokens and max_completion_tokens throws BadRequest`() {
        val e = assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.effectiveParams(ChatCompletionRequest(maxTokens = 10, maxCompletionTokens = 10))
        }
        assertEquals("max_tokens", e.param)
    }

    @Test
    fun `n greater than one throws BadRequest`() {
        val e = assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.effectiveParams(ChatCompletionRequest(n = 2))
        }
        assertEquals("n", e.param)
    }

    @Test
    fun `invalid max_tokens throws BadRequest`() {
        val e = assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.effectiveParams(ChatCompletionRequest(maxTokens = 0))
        }
        assertEquals("max_tokens", e.param)
    }

    @Test
    fun `invalid temperature throws BadRequest`() {
        val e = assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.effectiveParams(ChatCompletionRequest(temperature = 3.0))
        }
        assertEquals("temperature", e.param)
    }

    @Test
    fun `invalid top_p throws BadRequest`() {
        val e = assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.effectiveParams(ChatCompletionRequest(topP = 1.5))
        }
        assertEquals("top_p", e.param)
    }

    // --- thinking (Pi sends thinking_budget_tokens + chat_template_kwargs) ---

    @Test
    fun `thinking budget enables thinking with that budget`() {
        val params = processor.effectiveParams(ChatCompletionRequest(thinkingBudgetTokens = 1024))
        assertTrue(params.thinkingEnabled)
        assertEquals(1024, params.thinkingTokenBudget)
    }

    @Test
    fun `thinking stays off without thinking params`() {
        val params = processor.effectiveParams(ChatCompletionRequest())
        assertFalse(params.thinkingEnabled)
        assertEquals(-1, params.thinkingTokenBudget)
    }

    @Test
    fun `enable_thinking false overrides budget`() {
        val kwargs = buildJsonObject { put("enable_thinking", JsonPrimitive("false")) }
        val params = processor.effectiveParams(
            ChatCompletionRequest(thinkingBudgetTokens = 512, chatTemplateKwargs = kwargs),
        )
        assertFalse(params.thinkingEnabled)
    }

    @Test
    fun `negative thinking budget throws BadRequest`() {
        val e = assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.effectiveParams(ChatCompletionRequest(thinkingBudgetTokens = -1))
        }
        assertEquals("thinking_budget_tokens", e.param)
    }

    // --- estimateUsage ---

    @Test
    fun `usage estimate is char based`() {
        val usage = processor.estimateUsage("aaaa", "bb")
        assertEquals(1, usage.promptTokens)
        assertEquals(0, usage.completionTokens)
        assertEquals(1, usage.totalTokens)
    }

    @Test
    fun `usage reports reasoning tokens in completion details`() {
        val usage = processor.estimateUsage("aaaa", "bbbb", thinkingTokens = 8)
        assertEquals(8, usage.completionTokensDetails?.reasoningTokens)
        assertEquals(1 + 1 + 8, usage.totalTokens)
    }
}
