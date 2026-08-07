package com.pixnpu.engine

data class GenerationParams(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val maxTokens: Int = 1024,
    val contextTokens: Int = 8192,
    /**
     * Thinking/reasoning mode (Gemma 3+/4 models). Off by default; enabled via
     * the API (Pi sends thinking_budget_tokens / chat_template_kwargs).
     */
    val thinkingEnabled: Boolean = false,
    /** Thinking token budget; -1 = unbounded. Only meaningful when enabled. */
    val thinkingTokenBudget: Int = -1,
)