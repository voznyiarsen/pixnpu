package com.pixnpu.server

import android.content.Context
import com.google.ai.edge.litertlm.Content
import com.pixnpu.engine.GenerationParams
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
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
    fun `text messages are role-prefixed in order`() {
        val contents = processor.buildContent(
            request(
                "system" to "Be concise",
                "user" to "Hello",
                "assistant" to "Hi there",
                "user" to "Again",
            ),
        )
        assertEquals(4, contents.size)
        assertEquals(Content.Text("System: Be concise"), contents[0])
        assertEquals(Content.Text("User: Hello"), contents[1])
        assertEquals(Content.Text("Assistant: Hi there"), contents[2])
        assertEquals(Content.Text("User: Again"), contents[3])
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
        assertEquals(Content.Text("User: describe this"), contents[0])
        assertTrue(contents[1] is Content.ImageFile)
        val path = (contents[1] as Content.ImageFile).absolutePath
        assertTrue(File(path).isFile)
        assertEquals(b64, java.util.Base64.getEncoder().encodeToString(File(path).readBytes()))
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
            ChatCompletionRequest(temperature = 1.2, maxTokens = 512),
        )
        assertEquals(1.2f, params.temperature)
        assertEquals(512, params.maxTokens)
    }

    @Test
    fun `missing params fall back to defaults`() {
        val params = processor.effectiveParams(ChatCompletionRequest())
        assertEquals(GenerationParams().temperature, params.temperature)
        assertEquals(GenerationParams().maxTokens, params.maxTokens)
    }

    @Test
    fun `invalid max_tokens throws BadRequest`() {
        assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.effectiveParams(ChatCompletionRequest(maxTokens = 0))
        }
    }

    @Test
    fun `invalid temperature throws BadRequest`() {
        assertThrows(ChatCompletionError.BadRequest::class.java) {
            processor.effectiveParams(ChatCompletionRequest(temperature = 3.0))
        }
    }

    // --- estimateUsage ---

    @Test
    fun `usage estimate is char based`() {
        val usage = processor.estimateUsage("aaaa", "bb")
        assertEquals(1, usage.promptTokens)
        assertEquals(0, usage.completionTokens)
        assertEquals(1, usage.totalTokens)
    }
}
