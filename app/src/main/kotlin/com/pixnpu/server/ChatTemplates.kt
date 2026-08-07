package com.pixnpu.server

/**
 * Jinja chat templates advertised via `/props` (`chat_template`, `bos_token`,
 * `eos_token`) — llama.cpp reports the template baked into the GGUF metadata.
 *
 * LiteRT-LM formats prompts internally (PromptTemplate.Auto), so these strings
 * are informational: llama.cpp-compatible clients read them to pre-format
 * their own prompts. The family is detected from the model id; unknown models
 * report null (no template), like a GGUF without one.
 */
object ChatTemplates {

    data class Template(val jinja: String, val bos: String, val eos: String)

    private val GEMMA3 = Template(
        jinja = """{{ bos_token }}{% if messages[0]['role'] == 'system' %}{{ raise_exception('System role not supported') }}{% endif %}{% for message in messages %}{% if (message['role'] == 'user') != (loop.index0 % 2 == 0) %}{{ raise_exception('Conversation roles must alternate user/assistant/user/assistant/...') }}{% endif %}{% if message['role'] == 'user' %}{{ '<start_of_turn>' }}{% else %}{{ '<start_of_turn>model\n' }}{% endif %}{{ message['content'] }}{% if (loop.last and message['role'] == 'user') %}{{ '<end_of_turn>' }}{% elif message['role'] == 'assistant' %}{{ '<end_of_turn>\n' }}{% endif %}{% endfor %}{% if add_generation_prompt %}{{ '<start_of_turn>model\n' }}{% endif %}""",
        bos = "<bos>",
        eos = "<eos>",
    )

    private val GEMMA2 = Template(
        jinja = """{{ bos_token }}{% if messages[0]['role'] == 'user' %}{{ raise_exception('User role not supported') }}{% endif %}{% for message in messages %}{% if message['role'] == 'user' %}{{ '<start_of_turn>user\n' }}{{ message['content'] }}{{ '<end_of_turn>\n' }}{% elif message['role'] == 'assistant' %}{{ '<start_of_turn>model\n' }}{{ message['content'] }}{{ '<end_of_turn>\n' }}{% endif %}{% endfor %}{% if add_generation_prompt %}{{ '<start_of_turn>model\n' }}{% endif %}""",
        bos = "<bos>",
        eos = "<eos>",
    )

    private val LLAMA3 = Template(
        jinja = """{{ bos_token }}{% for message in messages %}{% if message['role'] == 'system' %}{{ '<|start_header_id|>system<|end_header_id|>\n\n' }}{% elif message['role'] == 'user' %}{{ '<|start_header_id|>user<|end_header_id|>\n\n' }}{% elif message['role'] == 'assistant' %}{{ '<|start_header_id|>assistant<|end_header_id|>\n\n' }}{% endif %}{% if message['role'] == 'tool' %}{{ '<|start_header_id|>ipython<|end_header_id|>\n\n' }}{% endif %}{{ message['content'] }}{% if not loop.last %}{{ '<|eot_id|>' }}{% endif %}{% endfor %}{% if add_generation_prompt %}{{ '<|start_header_id|>assistant<|end_header_id|>\n\n' }}{% endif %}""",
        bos = "<|begin_of_text|>",
        eos = "<|eot_id|>",
    )

    private val CHATML = Template(
        jinja = """{% if not add_generation_prompt is defined %}{% set add_generation_prompt = false %}{% endif %}{% for message in messages %}{{ '<|im_start|>' }}{{ message['role'] }}{{ '<|im_end|>\n' }}{{ message['content'] }}{{ '<|im_end|>\n' }}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant\n' }}{% endif %}""",
        bos = "<|im_start|>",
        eos = "<|im_end|>",
    )

    private val MISTRAL = Template(
        jinja = """{{ bos_token }}{% for message in messages %}{% if message['role'] == 'user' %}{{ '[INST] ' + message['content'] + ' [/INST]' }}{% elif message['role'] == 'assistant' %}{{ ' ' + message['content'] }}{% endif %}{% endfor %}{% if add_generation_prompt %}{{ ' [/INST]' }}{% endif %}""",
        bos = "<s>",
        eos = "</s>",
    )

    private val PHI3 = Template(
        jinja = """{{ bos_token }}{% for message in messages %}{% if message['role'] == 'user' %}{{ '<|user|>' + message['content'] + '<|end|>' }}{% elif message['role'] == 'assistant' %}{{ '<|assistant|>' + message['content'] + '<|end|>' }}{% endif %}{% endfor %}{% if add_generation_prompt %}{{ '<|assistant|>' }}{% endif %}""",
        bos = "<s>",
        eos = "<|endoftext|>",
    )

    /**
     * Returns the template for a model id (file base name), or null when the
     * family is unknown.
     */
    fun forModel(modelId: String?): Template? {
        if (modelId.isNullOrBlank()) return null
        return when {
            modelId.contains("gemma3", ignoreCase = true) -> GEMMA3
            modelId.contains("gemma", ignoreCase = true) -> GEMMA2
            modelId.contains("llama3", ignoreCase = true) -> LLAMA3
            modelId.contains("qwen", ignoreCase = true) -> CHATML
            modelId.contains("chatml", ignoreCase = true) -> CHATML
            modelId.contains("mistral", ignoreCase = true) -> MISTRAL
            modelId.contains("phi3", ignoreCase = true) || modelId.contains("phi-3", ignoreCase = true) -> PHI3
            modelId.contains("phi", ignoreCase = true) -> PHI3
            else -> null
        }
    }
}
