package com.pixnpu.engine

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class ActiveBackend(val label: String) {
    data object NPU : ActiveBackend("NPU")
    data object GPU : ActiveBackend("GPU")
    data class CPU(val threads: Int = 4) : ActiveBackend("CPU (${threads}t)")
}

/**
 * Thread-safe wrapper around the LiteRT-LM native engine. Loads a .litertlm model,
 * picks the fastest available hardware delegate (NPU -> GPU -> CPU), streams decoded
 * tokens and tracks real-time performance metrics.
 */
class LiteRTLMEngine(private val context: Context) {

    private val mutex = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var activeBackend: ActiveBackend? = null

    private val _metrics = MutableStateFlow(InferenceMetrics())
    val metrics: StateFlow<InferenceMetrics> = _metrics.asStateFlow()

    val isLoaded: Boolean get() = engine?.isInitialized() == true

    suspend fun load(modelPath: String, params: GenerationParams): ActiveBackend =
        mutex.withLock {
            releaseConversation()
            unloadEngine()
            val candidate = initializeBackend(modelPath, params)
            activeBackend = candidate
            conversation = createNewConversation(params, "")
            _metrics.value = _metrics.value.copy(
                status = EngineStatus.Ready,
                backend = candidate.label,
                maxContextTokens = params.contextTokens,
            )
            candidate
        }

    suspend fun reconfigure(params: GenerationParams, systemPrompt: String): Unit = mutex.withLock {
        engine ?: return
        releaseConversation()
        conversation = createNewConversation(params, systemPrompt)
        _metrics.value = _metrics.value.copy(maxContextTokens = params.contextTokens)
    }

    suspend fun unload(): Unit = mutex.withLock {
        releaseConversation()
        unloadEngine()
        activeBackend = null
        _metrics.value = InferenceMetrics()
    }

    /**
     * Generates a reply from a text prompt. Emits the complete response as a single delta.
     */
    fun generate(prompt: String): Flow<String> = generate(listOf(Content.Text(prompt)))

    /**
     * Generates a reply from multimodal content (text + optional images).
     * Emits the complete response as a single delta. Uses the engine's synchronous
     * API on a background dispatcher to avoid the coroutines channel internals.
     */
    fun generate(content: List<Content>): Flow<String> = flow {
        val conversation = mutex.withLock { conversation }
        if (conversation == null) {
            throw IllegalStateException("Engine is not loaded. Pick a model first.")
        }

        val startedAt = System.nanoTime()
        var firstTokenAt: Long? = null
        var textLength = 0

        _metrics.value = _metrics.value.copy(
            status = EngineStatus.Generating,
            ttftMs = null,
            totalTokens = 0,
            tokensPerSecond = 0.0,
            currentTokensPerSecond = 0.0,
            contextTokens = safeTokenCount(),
        )

        try {
            val contents = Contents.of(content)
            val reply = withContext(Dispatchers.Default) {
                conversation.sendMessage(contents)?.toString().orEmpty()
            }
            if (reply.isNotEmpty()) {
                val now = System.nanoTime()
                if (firstTokenAt == null) {
                    firstTokenAt = now
                    _metrics.value = _metrics.value.copy(
                        ttftMs = (now - startedAt) / 1_000_000L,
                    )
                }
                textLength = tokenEstimate(reply)
                emit(reply)
            }
        } finally {
            val done = System.nanoTime()
            val totalSecs = (done - startedAt) / 1_000_000_000.0
            _metrics.value = _metrics.value.copy(
                status = EngineStatus.Ready,
                totalTokens = textLength,
                tokensPerSecond = if (totalSecs > 0) textLength / totalSecs else 0.0,
                currentTokensPerSecond = if (totalSecs > 0) textLength / totalSecs else 0.0,
                contextTokens = safeTokenCount(),
            )
        }
    }

    fun cancel() {
        val conversation = conversation ?: return
        try {
            conversation.cancelProcess()
        } catch (_: Exception) {
            // no-op if no generation is in flight
        }
    }

    private suspend fun initializeBackend(modelPath: String, params: GenerationParams): ActiveBackend {
        val candidates = listOf(
            ActiveBackend.NPU,
            ActiveBackend.GPU,
            ActiveBackend.CPU(),
        )
        var lastError: Throwable? = null
        for (candidate in candidates) {
            _metrics.value = _metrics.value.copy(
                status = EngineStatus.Loading,
                backend = candidate.label,
            )
            try {
                val backend = when (candidate) {
                    ActiveBackend.NPU -> Backend.NPU(
                        nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
                    )
                    ActiveBackend.GPU -> Backend.GPU()
                    is ActiveBackend.CPU -> Backend.CPU(threadCount = candidate.threads)
                }
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = params.contextTokens,
                    cacheDir = context.cacheDir.absolutePath,
                )
                val candidateEngine = Engine(config)
                candidateEngine.initialize()
                engine = candidateEngine
                return candidate
            } catch (t: Throwable) {
                lastError = t
            }
        }
        _metrics.value = _metrics.value.copy(status = EngineStatus.Error)
        throw IllegalStateException(
            "Failed to initialize any backend (NPU/GPU/CPU): ${lastError?.message}",
        )
    }

    private fun createNewConversation(params: GenerationParams, systemPrompt: String): Conversation? {
        val engine = engine ?: return null
        val contents =
            if (systemPrompt.isBlank()) null
            else com.google.ai.edge.litertlm.Contents.of(systemPrompt)
        return engine.createConversation(
            ConversationConfig(
                systemInstruction = contents,
                samplerConfig = SamplerConfig(
                    topK = params.topK,
                    topP = params.topP.toDouble(),
                    temperature = params.temperature.toDouble(),
                ),
                maxOutputToken = params.maxTokens,
            ),
        )
    }

    private fun releaseConversation() {
        conversation?.let { conv ->
            try {
                conv.close()
            } catch (_: Exception) {
            }
        }
        conversation = null
    }

    private fun unloadEngine() {
        engine?.let { eng ->
            try {
                eng.close()
            } catch (_: Exception) {
            }
        }
        engine = null
    }

    private fun safeTokenCount(): Int {
        val conversation = conversation ?: return 0
        return try {
            conversation.getTokenCount()
        } catch (_: Exception) {
            0
        }
    }

    private fun tokenEstimate(chars: String): Int {
        if (chars.isEmpty()) return 0
        return (chars.length / 4).coerceAtLeast(1)
    }
}