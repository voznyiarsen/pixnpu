package com.pixnpu.engine

enum class PromptTemplate(val label: String) {
    Auto("Auto (model default)"),
    ChatML("ChatML"),
    Gemma("Gemma"),
    Llama3("Llama-3"),
}

object PromptTemplates {

    fun wrap(raw: String, template: PromptTemplate, systemPrompt: String): String = when (template) {
        PromptTemplate.Auto -> raw
        PromptTemplate.ChatML -> buildString {
            if (systemPrompt.isNotBlank()) {
                append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
            }
            append("<|im_start|>user\n").append(raw).append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
        PromptTemplate.Gemma -> buildString {
            if (systemPrompt.isNotBlank()) {
                append("<start_of_turn>user\n")
                    .append(systemPrompt).append("\n").append(raw)
                    .append("<end_of_turn>\n")
            } else {
                append("<start_of_turn>user\n").append(raw).append("<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        }
        PromptTemplate.Llama3 -> buildString {
            if (systemPrompt.isNotBlank()) {
                append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
                    .append(systemPrompt).append("<|eot_id|>")
            } else {
                append("<|begin_of_text|>")
            }
            append("<|start_header_id|>user<|end_header_id|>\n\n").append(raw).append("<|eot_id|>")
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }
    }
}