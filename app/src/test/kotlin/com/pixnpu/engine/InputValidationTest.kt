package com.pixnpu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for input validation in generation parameters and prompt templates
 */
class InputValidationTest {

    @Test
    fun generationParams_defaultValues() {
        val params = GenerationParams()
        assertEquals(0.7f, params.temperature, 0.001f)
        assertEquals(40, params.topK)
        assertEquals(0.95f, params.topP, 0.001f)
        assertEquals(1024, params.maxTokens)
        assertEquals(8192, params.contextTokens)
    }

    @Test
    fun generationParams_customValues() {
        val params = GenerationParams(
            temperature = 0.9f,
            topK = 50,
            topP = 0.9f,
            maxTokens = 2048,
            contextTokens = 16384
        )
        assertEquals(0.9f, params.temperature, 0.001f)
        assertEquals(50, params.topK)
        assertEquals(0.9f, params.topP, 0.001f)
        assertEquals(2048, params.maxTokens)
        assertEquals(16384, params.contextTokens)
    }

    @Test
    fun promptTemplate_labels() {
        assertEquals("Auto (model default)", PromptTemplate.Auto.label)
        assertEquals("ChatML", PromptTemplate.ChatML.label)
        assertEquals("Gemma", PromptTemplate.Gemma.label)
        assertEquals("Llama-3", PromptTemplate.Llama3.label)
        assertEquals("Qwen", PromptTemplate.Qwen.label)
        assertEquals("Mistral", PromptTemplate.Mistral.label)
        assertEquals("Phi-3", PromptTemplate.Phi3.label)
    }

    @Test
    fun promptTemplates_wrapAuto_returnsRaw() {
        val result = PromptTemplates.wrap("Hello world", PromptTemplate.Auto, "")
        assertEquals("Hello world", result)
    }

    @Test
    fun promptTemplates_wrapChatML_withSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.ChatML, "You are helpful")
        val expected = "<|im_start|>system\nYou are helpful<|im_end|>\n<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n"
        assertEquals(expected, result)
    }

    @Test
    fun promptTemplates_wrapGemma_withSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.Gemma, "You are helpful")
        val expected = "<start_of_turn>user\nYou are helpful\nHello<end_of_turn>\n<start_of_turn>model\n"
        assertEquals(expected, result)
    }

    @Test
    fun reasoningUtils_stripReasoning() {
        val input = "<think>Some inner thought</think>Hello world!"
        val result = input.stripReasoning()
        assertEquals("Hello world!", result)
    }

    @Test
    fun reasoningUtils_stripReasoning_multiLine() {
        val input = """
            <think>
            Step 1: Calculate
            Step 2: Solve
            </think>
            The answer is 42.
        """.trimIndent()
        val result = input.stripReasoning()
        assertEquals("The answer is 42.", result)
    }

    @Test
    fun reasoningUtils_stripReasoning_unclosed() {
        val input = "<think>I am still thinking..."
        val result = input.stripReasoning()
        assertEquals("", result)
    }

    @Test
    fun reasoningUtils_stripReasoning_noReasoning() {
        val input = "Just normal response."
        val result = input.stripReasoning()
        assertEquals("Just normal response.", result)
    }
}
