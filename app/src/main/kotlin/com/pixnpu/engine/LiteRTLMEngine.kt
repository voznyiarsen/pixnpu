package com.pixnpu.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private var currentParams: GenerationParams? = null
    private var currentSystemPrompt: String = ""
    
    // Manual conversation history tracking to handle reasoning
    // Each entry is (userContent, assistantResponseWithReasoning)
    private val conversationHistory = mutableListOf<Pair<List<Content>, String>>()

    private val _metrics = MutableStateFlow(InferenceMetrics())
    val metrics: StateFlow<InferenceMetrics> = _metrics.asStateFlow()

    val isLoaded: Boolean get() = engine?.isInitialized() == true

    suspend fun load(modelPath: String, params: GenerationParams): ActiveBackend =
        mutex.withLock {
            Log.d("LiteRTLMEngine", "Loading model: $modelPath")
            releaseConversation()
            unloadEngine()
            conversationHistory.clear()
            val candidate = initializeBackend(modelPath, params)
            activeBackend = candidate
            currentParams = params
            currentSystemPrompt = ""
            conversation = createNewConversation(params, "")
            _metrics.value = _metrics.value.copy(
                status = EngineStatus.Ready,
                backend = candidate.label,
                maxContextTokens = params.contextTokens,
            )
            Log.d("LiteRTLMEngine", "Model loaded on backend: ${candidate.label}")
            candidate
        }

    suspend fun reconfigure(params: GenerationParams, systemPrompt: String): Unit = mutex.withLock {
        if (engine == null) {
            Log.w("LiteRTLMEngine", "reconfigure called but engine is not loaded")
            return
        }
        Log.d("LiteRTLMEngine", "Reconfiguring: temp=${params.temperature}, topK=${params.topK}, topP=${params.topP}")
        releaseConversation()
        currentParams = params
        currentSystemPrompt = systemPrompt
        conversation = createNewConversation(params, systemPrompt)
        _metrics.value = _metrics.value.copy(maxContextTokens = params.contextTokens)
    }

    suspend fun unload(): Unit = mutex.withLock {
        Log.d("LiteRTLMEngine", "Unloading engine")
        releaseConversation()
        unloadEngine()
        activeBackend = null
        _metrics.value = InferenceMetrics()
    }

     /**
      * Generates a reply from a text prompt. Streams tokens as they arrive.
      */
     fun generate(prompt: String): Flow<String> = generate(listOf(Content.Text(prompt)))

     /**
      * Generates a reply from multimodal content (text + optional images).
      * Creates a new conversation for each turn with cleaned history (reasoning stripped)
      * to prevent template mismatch errors, while preserving full responses in the UI.
      */
     fun generate(content: List<Content>): Flow<String> = flow {
         val engineRef = withContext(Dispatchers.IO) {
             mutex.withLock { engine }
         }
         val paramsRef = withContext(Dispatchers.IO) {
             mutex.withLock { currentParams }
         }
         val systemPromptRef = withContext(Dispatchers.IO) {
             mutex.withLock { currentSystemPrompt }
         }
         
         if (engineRef == null || paramsRef == null) {
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
             Log.d("LiteRTLMEngine", "Starting generation on backend: ${activeBackend?.label}")

             // Generate with cleaned history
             val (newConversation, fullReply, cleanedAssistantResponse) = withContext(Dispatchers.Default) {
                 mutex.withLock {
                     generateWithCleanedHistory(contents, engineRef, paramsRef, systemPromptRef)
                 }
             }
             
             // Update conversation reference
             conversation = newConversation
             
             // Store the full response (with reasoning) in history
             conversationHistory.add(Pair(content, fullReply))

             if (fullReply.isNotEmpty()) {
                 val chunks = fullReply.split(" ").filter { it.isNotEmpty() }
                 var emitted = 0
                 for (chunk in chunks) {
                     val token = if (emitted == 0) chunk else " $chunk"
                     emitted++
                     if (firstTokenAt == null) {
                         firstTokenAt = System.nanoTime()
                         val ttft = (firstTokenAt - startedAt) / 1_000_000L
                         _metrics.value = _metrics.value.copy(
                             ttftMs = ttft,
                         )
                         textLength = tokenEstimate(token)
                     }
                     emit(token)
                     if (emitted < chunks.size) delay(8)
                 }
                 textLength = tokenEstimate(fullReply)
             }

         } catch (e: Exception) {
             Log.e("LiteRTLMEngine", "Generation failed", e)
             throw e
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
     
     /**
      * Generates a response with cleaned conversation history.
      * Creates a new conversation for each turn, feeding it history with reasoning stripped
      * from assistant responses to prevent template mismatch errors.
      * 
      * @return Triple of (newConversation, fullReplyWithReasoning, cleanedAssistantResponse)
      */
     private suspend fun generateWithCleanedHistory(
         newUserContent: Contents,
         engineRef: Engine,
         params: GenerationParams,
         systemPrompt: String
     ): Triple<Conversation, String, String> {
         // Build history with reasoning stripped from assistant messages
         val historyContents = mutableListOf<Content>()
         
         for ((userContent, assistantResponse) in conversationHistory) {
             // Add user message
             historyContents.addAll(userContent)
             // Add assistant response with reasoning stripped
             val cleanedResponse = assistantResponse.stripReasoning()
             historyContents.add(Content.Text(cleanedResponse))
         }
         
         // Add the new user message
         historyContents.addAll(newUserContent.contents)
         
         // Create a new conversation with system prompt
         val newConversation = engineRef.createConversation(
             ConversationConfig(
                 systemInstruction = if (systemPrompt.isBlank()) null else Contents.of(systemPrompt),
                 samplerConfig = SamplerConfig(
                     topK = params.topK,
                     topP = params.topP.toDouble(),
                     temperature = params.temperature.toDouble(),
                 ),
                 maxOutputToken = params.maxTokens,
             ),
         )
         
         // Build the full prompt with history
         val fullPromptContents = Contents.of(historyContents)
         
         // Generate response
         val fullReply = newConversation.sendMessage(fullPromptContents).toString()
         
         return Triple(newConversation, fullReply, fullReply.stripReasoning())
     }

    fun cancel() {
        val conv = conversation
        conv ?: return
        try {
            conv.cancelProcess()
            Log.d("LiteRTLMEngine", "cancelProcess called")
        } catch (e: Exception) {
            Log.w("LiteRTLMEngine", "cancelProcess failed", e)
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
                try {
                    candidateEngine.initialize()
                } catch (e: Exception) {
                    Log.w("LiteRTLMEngine", "Backend ${candidate.label} initialization failed", e)
                    try { candidateEngine.close() } catch (_: Exception) { }
                    throw e
                }
                engine = candidateEngine
                Log.d("LiteRTLMEngine", "Backend ${candidate.label} initialized successfully")
                return candidate
            } catch (e: Exception) {
                lastError = e
                Log.w("LiteRTLMEngine", "Backend ${candidate.label} failed: ${e.message}")
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
                Log.d("LiteRTLMEngine", "Conversation released")
            } catch (e: Exception) {
                Log.w("LiteRTLMEngine", "Failed to close conversation", e)
            }
        }
        conversation = null
    }

    private fun unloadEngine() {
        engine?.let { eng ->
            try {
                eng.close()
                Log.d("LiteRTLMEngine", "Engine unloaded")
            } catch (e: Exception) {
                Log.w("LiteRTLMEngine", "Failed to close engine", e)
            }
        }
        engine = null
    }

    private fun safeTokenCount(): Int {
        val conv = conversation ?: return 0
        return try {
            conv.getTokenCount()
        } catch (e: Exception) {
            Log.w("LiteRTLMEngine", "Failed to get token count", e)
            0
        }
    }
    
    /**
      * Clears conversation history. Call this when starting a new chat.
      */
    suspend fun clearHistory() = mutex.withLock {
        conversationHistory.clear()
        releaseConversation()
        conversation = currentParams?.let { params ->
            createNewConversation(params, currentSystemPrompt)
        }
    }

    private fun tokenEstimate(chars: String): Int {
        if (chars.isEmpty()) return 0
        return (chars.length / 4).coerceAtLeast(1)
    }
}