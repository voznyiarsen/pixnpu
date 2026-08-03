package com.pixnpu.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptTemplatesTest {

    @Test
    fun auto_returnsRawPrompt() {
        val result = PromptTemplates.wrap("Hello world", PromptTemplate.Auto, "")
        assertEquals("Hello world", result)
    }

    @Test
    fun chatml_withSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.ChatML, "You are helpful")
        assertEquals("<|im_start|>system\nYou are helpful<|im_end|>\n<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n", result)
    }

    @Test
    fun chatml_withoutSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.ChatML, "")
        assertEquals("<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n", result)
    }

    @Test
    fun gemma_withSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.Gemma, "You are helpful")
        assertEquals("<start_of_turn>user\nYou are helpful\nHello<end_of_turn>\n<start_of_turn>model\n", result)
    }

    @Test
    fun gemma_withoutSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.Gemma, "")
        assertEquals("<start_of_turn>user\nHello<end_of_turn>\n<start_of_turn>model\n", result)
    }

    @Test
    fun llama3_withSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.Llama3, "You are helpful")
        assertEquals("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nYou are helpful<|eot_id|><|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n", result)
    }

    @Test
    fun llama3_withoutSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.Llama3, "")
        assertEquals("<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n", result)
    }

    @Test
    fun qwen_withSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.Qwen, "You are helpful")
        assertEquals("<|im_start|>system\nYou are helpful<|im_end|>\n<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n", result)
    }

    @Test
    fun qwen_withoutSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.Qwen, "")
        assertEquals("<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n", result)
    }

    @Test
    fun mistral_withSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.Mistral, "You are helpful")
        assertEquals("[INST] <<SYS>>\nYou are helpful\n<</SYS>>\n\nHello [/INST] ", result)
    }

    @Test
    fun mistral_withoutSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.Mistral, "")
        assertEquals("[INST] Hello [/INST] ", result)
    }

    @Test
    fun phi3_withSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.Phi3, "You are helpful")
        assertEquals("<|system|>\nYou are helpful<|end|>\n<|user|>Hello<|end|>\n<|assistant|>", result)
    }

    @Test
    fun phi3_withoutSystemPrompt() {
        val result = PromptTemplates.wrap("Hello", PromptTemplate.Phi3, "")
        assertEquals("<|user|>Hello<|end|>\n<|assistant|>", result)
    }

    @Test
    fun templateLabels() {
        assertEquals("Auto (model default)", PromptTemplate.Auto.label)
        assertEquals("ChatML", PromptTemplate.ChatML.label)
        assertEquals("Gemma", PromptTemplate.Gemma.label)
        assertEquals("Llama-3", PromptTemplate.Llama3.label)
        assertEquals("Qwen", PromptTemplate.Qwen.label)
        assertEquals("Mistral", PromptTemplate.Mistral.label)
        assertEquals("Phi-3", PromptTemplate.Phi3.label)
    }
}
