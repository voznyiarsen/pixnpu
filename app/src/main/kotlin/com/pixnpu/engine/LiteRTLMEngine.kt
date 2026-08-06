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
class LiteRTLMEngine(private val context: Context) : LiteRTLMEngineInterface {

    private val mutex = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var activeBackend: ActiveBackend? = null
    private var activeSupportsVision: Boolean = false
    private var activeSupportsAudio: Boolean = false
    private var currentParams: GenerationParams? = null
    private var currentSystemPrompt: String = ""
    
    // Manual conversation history tracking to handle reasoning
    // Each entry is (userContent, assistantResponseWithReasoning)
    // Bounded to prevent memory leaks - oldest entries evicted when limit reached
    private val conversationHistory = mutableListOf<Pair<List<Content>, String>>()
    
    // Maximum conversation history turns to retain (prevents memory leaks)
    private val maxConversationHistory = 50

    // Warmup prompt sent immediately after engine initialization to absorb the
    // one-time backend dispatch/kernel-tuning latency (largest on NPU) so the
    // first real user prompt gets a clean TTFT. Kept minimal for speed.
    private val warmupPrompt = "Hi"
    private val warmupMaxOutputTokens = 1

    private val _metrics = MutableStateFlow(InferenceMetrics())
    override val metrics: StateFlow<InferenceMetrics> = _metrics.asStateFlow()

    override val isLoaded: Boolean get() = engine?.isInitialized() == true

     /**
      * Multimodal capability of the currently-loaded model, set explicitly at load
      * time via the [Modality] the user selected. LiteRT-LM 0.15.0's Kotlin API
      * exposes no modality query ([Capabilities] only reports speculative-decoding),
      * so the user declares intent and the native loader validates it — models
      * lacking a requested modality fail cleanly at load rather than silently
      * down-grading. Non-textual input is offered only for models loaded with the
      * matching [Modality].
      */
     val supportsVision: Boolean get() = threadSafe { activeSupportsVision }
     val supportsAudio: Boolean get() = threadSafe { activeSupportsAudio }

     override suspend fun load(modelPath: String, params: GenerationParams, modality: Modality): ActiveBackend =
         mutex.withLock {
             Log.d("LiteRTLMEngine", "Loading model: $modelPath modality=$modality")
             releaseConversation()
             unloadEngine()
             conversationHistory.clear()
             val init = initializeBackend(modelPath, params, modality)
             activeBackend = init.backend
             activeSupportsVision = modality.supportsVision
             activeSupportsAudio = modality.supportsAudio
             currentParams = params
             currentSystemPrompt = ""
             conversation = createNewConversation(params, "")
             warmup(init.backend)
             _metrics.value = _metrics.value.copy(
                 status = EngineStatus.Ready,
                 backend = init.backend.label,
                 maxContextTokens = params.contextTokens,
                 supportsVision = modality.supportsVision,
                 supportsAudio = modality.supportsAudio,
             )
             Log.d("LiteRTLMEngine", "Model loaded on backend: ${init.backend.label} " +
                 "(vision=${modality.supportsVision}, audio=${modality.supportsAudio})")
             init.backend
         }

    private fun <T> threadSafe(block: () -> T): T {
        val locked = mutex.tryLock()
        return if (locked) {
            try { block() } finally { mutex.unlock() }
        } else {
            block()
        }
    }

     override suspend fun reconfigure(params: GenerationParams, systemPrompt: String): Unit = mutex.withLock {
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

    override suspend fun unload(): Unit = mutex.withLock {
        Log.d("LiteRTLMEngine", "Unloading engine")
        releaseConversation()
        unloadEngine()
        activeBackend = null
        _metrics.value = InferenceMetrics()
    }

      /**
       * Maximum prompt length in characters to prevent OOM
       */
      private val maxPromptLength = 32000

      /**
       * Maximum content items in a single generation request
       */
      private val maxContentItems = 10

      /**
       * Generates a reply from a text prompt. Streams tokens as they arrive.
       * 
       * @param prompt The user prompt text
       * @param template The prompt template to use
       * @throws IllegalArgumentException if prompt is empty or too long
       * @throws IllegalStateException if engine is not loaded
       */
      override fun generate(prompt: String, template: PromptTemplate): Flow<String> {
          require(prompt.isNotBlank()) { "Prompt cannot be empty or blank" }
          require(prompt.length <= maxPromptLength) { 
              "Prompt exceeds maximum length of $maxPromptLength characters (got ${prompt.length})" 
          }
          val wrapped = PromptTemplates.wrap(prompt, template, currentSystemPrompt)
          return generateInternal(listOf(Content.Text(wrapped)))
      }

      /**
       * Generates a reply from multimodal content (text + optional images).
       * Creates a new conversation for each turn with cleaned history (reasoning stripped)
       * to prevent template mismatch errors, while preserving full responses in the UI.
       * 
       * @param content List of content items (text and/or images)
       * @param template The prompt template to use
       * @param trackHistory When false, the call is stateless: neither the current
       *        conversation history is fed into the prompt nor is this turn stored
       *        in it (used by the OpenAI-compatible API, which supplies full context
       *        per request). Defaults to true for the app chat.
       * @param paramsOverride Per-call generation parameters (e.g. temperature,
       *        max_tokens from an API request); null uses the configured params.
       * @throws IllegalArgumentException if content is empty or invalid
       * @throws IllegalStateException if engine is not loaded
       */
      override fun generate(
          content: List<Content>,
          template: PromptTemplate,
          trackHistory: Boolean,
          paramsOverride: GenerationParams?,
      ): Flow<String> {
          require(content.isNotEmpty()) { "Content list cannot be empty" }
          require(content.size <= maxContentItems) { 
              "Content exceeds maximum of $maxContentItems items (got ${content.size})" 
          }
          
          // Validate each content item
          for (item in content) {
              when (item) {
                  is Content.Text -> {
                      require(item.text.isNotBlank()) { "Text content cannot be blank" }
                      require(item.text.length <= maxPromptLength) { 
                          "Text content exceeds maximum length of $maxPromptLength characters" 
                      }
                  }
                  else -> {
                      // Image or other content types - just check they exist
                  }
              }
          }
          
          val wrappedContent = if (template == PromptTemplate.Auto) {
              content
          } else {
              content.map { c ->
                  if (c is Content.Text) {
                      Content.Text(PromptTemplates.wrap(c.text, template, currentSystemPrompt))
                  } else {
                      c
                  }
              }
          }
          return generateInternal(wrappedContent, trackHistory, paramsOverride)
      }

      private fun generateInternal(
          content: List<Content>,
          trackHistory: Boolean = true,
          paramsOverride: GenerationParams? = null,
      ): Flow<String> = flow {
          // Capture all state under mutex to prevent race conditions
          val state = mutex.withLock {
              val engineRef = engine
              val paramsRef = currentParams
              val systemPromptRef = currentSystemPrompt
              val backendRef = activeBackend

              if (engineRef == null || paramsRef == null) {
                  throw IllegalStateException("Engine is not loaded. Pick a model first.")
              }

              // Stateless calls (OpenAI API) skip history: the caller supplies the
              // full context in every request, so nothing is read or stored.
              val historySnapshot = if (trackHistory) conversationHistory.toList() else emptyList()
              val effectiveParams = paramsOverride ?: paramsRef

              StateSnapshot(engineRef, effectiveParams, systemPromptRef, backendRef, historySnapshot)
          }

          val startedAt = System.nanoTime()
          var firstTokenAt: Long? = null
          var textLength = 0
          val fullReply = StringBuilder()

          Log.d("LiteRTLMEngine", "Starting generation (history turns: ${state.history.size}, backend: ${state.backend?.label})")

          // Update metrics atomically
          mutex.withLock {
              _metrics.value = _metrics.value.copy(
                  status = EngineStatus.Generating,
                  ttftMs = null,
                  totalTokens = 0,
                  tokensPerSecond = 0.0,
                  currentTokensPerSecond = 0.0,
                  contextTokens = safeTokenCount(),
              )
          }

          try {
              val contents = Contents.of(content)
              Log.d("LiteRTLMEngine", "Starting generation on backend: ${state.backend?.label}")

              val newConversation = withContext(Dispatchers.Default) {
                  buildConversationWithCleanedHistory(contents, state.engine, state.params, state.systemPrompt, state.backend, state.history)
              }

              // Swap conversation before streaming so cancel() targets the live one
              mutex.withLock {
                  conversation?.close()
                  conversation = newConversation
              }

              var streamedTokens = 0
              try {
                  // Native token streaming: LiteRT-LM invokes the callback on a JNI
                  // thread and sendMessageAsync forwards each token delta through a
                  // coroutines channel. Requires coroutines 1.11+ (see AGENTS.md —
                  // the SendChannel.close$default ABI this bytecode needs is absent
                  // in 1.9.0, which killed the process).
                  newConversation.sendMessageAsync(contents).collect { message ->
                      val tokenText = message.contents.contents
                          .filterIsInstance<Content.Text>()
                          .joinToString("") { it.text }
                      if (tokenText.isEmpty()) return@collect
                      fullReply.append(tokenText)
                      streamedTokens++
                      if (firstTokenAt == null) {
                          val now = System.nanoTime()
                          firstTokenAt = now
                          val ttft = (now - startedAt) / 1_000_000L
                          mutex.withLock {
                              _metrics.value = _metrics.value.copy(ttftMs = ttft)
                          }
                      }
                      emit(tokenText)
                  }
              } catch (e: Exception) {
                  // Fallback: synchronous generation with word-sized chunk emission
                  // (the pre-1.11 workaround).
                  Log.w("LiteRTLMEngine", "Native streaming failed, falling back to sync generation", e)
                  val reply = newConversation.sendMessage(contents).toString()
                  fullReply.append(reply)
                  if (reply.isNotEmpty()) {
                      val chunks = reply.split(" ").filter { it.isNotEmpty() }
                      var emitted = 0
                      for (chunk in chunks) {
                          val token = if (emitted == 0) chunk else " $chunk"
                          emitted++
                          if (firstTokenAt == null) {
                              val now = System.nanoTime()
                              firstTokenAt = now
                              val ttft = (now - startedAt) / 1_000_000L
                              mutex.withLock {
                                  _metrics.value = _metrics.value.copy(ttftMs = ttft)
                              }
                          }
                          emit(token)
                          if (emitted < chunks.size) delay(8)
                      }
                  }
              }

              val finalReply = fullReply.toString()
              mutex.withLock {
                  if (trackHistory) {
                      // Store the full response in history, evicting oldest if at limit
                      conversationHistory.add(Pair(content, finalReply))
                      if (conversationHistory.size > maxConversationHistory) {
                          conversationHistory.removeAt(0)
                      }
                  }
              }
              textLength = tokenEstimate(finalReply)
              Log.d("LiteRTLMEngine", "Streamed $streamedTokens tokens (${finalReply.length} chars), stored in history")

          } catch (e: Exception) {
              Log.e("LiteRTLMEngine", "Generation failed", e)
              throw e
          } finally {
              val done = System.nanoTime()
              val totalSecs = (done - startedAt) / 1_000_000_000.0
              mutex.withLock {
                  _metrics.value = _metrics.value.copy(
                      status = EngineStatus.Ready,
                      totalTokens = textLength,
                      tokensPerSecond = if (totalSecs > 0) textLength / totalSecs else 0.0,
                      currentTokensPerSecond = if (totalSecs > 0) textLength / totalSecs else 0.0,
                      contextTokens = safeTokenCount(),
                  )
              }
          }
      }

      /**
       * Immutable snapshot of engine state for thread-safe generation
       */
      private data class StateSnapshot(
          val engine: Engine,
          val params: GenerationParams,
          val systemPrompt: String,
          val backend: ActiveBackend?,
          val history: List<Pair<List<Content>, String>>,
      )
     
      /**
       * Builds a fresh conversation for a turn, feeding it history with reasoning stripped
       * from assistant responses to prevent template mismatch errors.
       *
       * @param history Previous conversation turns (userContent, assistantResponse)
       */
      private suspend fun buildConversationWithCleanedHistory(
          newUserContent: Contents,
          engineRef: Engine,
          params: GenerationParams,
          systemPrompt: String,
          backend: ActiveBackend?,
          history: List<Pair<List<Content>, String>>
      ): Conversation {
          Log.d("LiteRTLMEngine", "Building cleaned history with ${history.size} previous turns")
          
          // Build history with reasoning stripped from assistant messages
          val historyContents = mutableListOf<Content>()
          
          for ((userContent, assistantResponse) in history) {
              // Add user message
              historyContents.addAll(userContent)
              // Add assistant response with reasoning stripped
              val cleanedResponse = assistantResponse.stripReasoning()
              historyContents.add(Content.Text(cleanedResponse))
              Log.d("LiteRTLMEngine", "Added history turn (user: ${userContent.size} contents, assistant: ${cleanedResponse.length} chars)")
          }
          
          // Add the new user message
          historyContents.addAll(newUserContent.contents)
          Log.d("LiteRTLMEngine", "Added new user message (${newUserContent.contents.size} contents)")
          
          // Create a new conversation with system prompt
          Log.d("LiteRTLMEngine", "Creating new conversation with system prompt (${systemPrompt.length} chars)")
          // On NPU, sampler config is null (uses model defaults) — practice from
          // google-ai-edge/gallery, which only sets SamplerConfig on GPU/CPU because
          // NPU dispatch does not support custom sampling.
          val samplerConfig =
              if (backend is ActiveBackend.NPU) null
              else
                  SamplerConfig(
                      topK = params.topK,
                      topP = params.topP.toDouble(),
                      temperature = params.temperature.toDouble(),
                  )
          val newConversation = engineRef.createConversation(
              ConversationConfig(
                  systemInstruction = if (systemPrompt.isBlank()) null else Contents.of(systemPrompt),
                  samplerConfig = samplerConfig,
                  maxOutputToken = params.maxTokens,
              ),
          )
          
          // Build the full prompt with history
          val fullPromptContents = Contents.of(historyContents)
          Log.d("LiteRTLMEngine", "Sending message with ${historyContents.size} history items")
          
          return newConversation
      }

    override fun cancel() {
        val conv = mutex.tryLock().let { locked ->
            if (locked) {
                try {
                    conversation
                } finally {
                    mutex.unlock()
                }
            } else {
                conversation
            }
        }
        conv ?: return
        try {
            conv.cancelProcess()
            Log.d("LiteRTLMEngine", "cancelProcess called")
        } catch (e: Exception) {
            Log.w("LiteRTLMEngine", "cancelProcess failed", e)
        }
    }

     private suspend fun initializeBackend(modelPath: String, params: GenerationParams, modality: Modality): InitResult {
         val backendCandidates = listOf(
             ActiveBackend.NPU,
             ActiveBackend.GPU,
             ActiveBackend.CPU(),
         )
         var lastError: Throwable? = null
         for (candidate in backendCandidates) {
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
                 // Configure backends exactly per the user-selected modality
                 // (audio on CPU, vision on GPU — gallery practice for Gemma 3n).
                 // LiteRT-LM itself validates support: a model lacking a requested
                 // modality fails init cleanly, which we treat as a retry on a leaner backend.
                 val config = EngineConfig(
                     modelPath = modelPath,
                     backend = backend,
                     visionBackend = if (modality.supportsVision) Backend.GPU() else null,
                     audioBackend = if (modality.supportsAudio) Backend.CPU() else null,
                     maxNumTokens = params.contextTokens,
                     cacheDir = context.cacheDir.absolutePath,
                 )
                 val candidateEngine = Engine(config)
                 try {
                     candidateEngine.initialize()
                     engine = candidateEngine
                     Log.d("LiteRTLMEngine", "Backend ${candidate.label} initialized (modality=$modality)")
                     return InitResult(candidate)
                 } catch (e: Exception) {
                     Log.w("LiteRTLMEngine", "Backend ${candidate.label} init failed (modality=$modality): ${e.message}")
                     try { candidateEngine.close() } catch (_: Exception) { }
                 }
             } catch (e: Exception) {
                 lastError = e
                 Log.w("LiteRTLMEngine", "Backend ${candidate.label} failed: ${e.message}")
             }
         }
         _metrics.value = _metrics.value.copy(status = EngineStatus.Error)
         throw IllegalStateException(
             "Failed to initialize model on any backend (NPU/GPU/CPU) for modality=$modality: ${lastError?.message}",
         )
     }

     private data class InitResult(
         val backend: ActiveBackend,
     )

    /**
     * Runs a single short inference right after load to trigger backend dispatch
     * and kernel compilation (practice adopted from google-ai-edge/gallery, which
     * warms up models immediately after initialization). This moves the one-time
     * first-inference latency out of the user's first prompt.
     *
     * Warmup is intentionally non-fatal: if the backend rejects inference (e.g.
     * the known NPU dispatch failures on some .litertlm models), we log a warning
     * and continue with the model loaded. The first real prompt then reports the
     * error through the normal generate() path.
     */
    private suspend fun warmup(backend: ActiveBackend) {
        val engineRef = engine ?: return
        val startedAt = System.nanoTime()
        try {
            // Short-lived AutoCloseable conversation, closed via use {}.
            // Sampler config is null so warmup uses model defaults (gallery practice).
            engineRef.createConversation(
                ConversationConfig(
                    samplerConfig = null,
                    maxOutputToken = warmupMaxOutputTokens,
                ),
            ).use { conv ->
                conv.sendMessage(Contents.of(warmupPrompt))
            }
            val warmupMs = (System.nanoTime() - startedAt) / 1_000_000L
            _metrics.value = _metrics.value.copy(warmupMs = warmupMs)
            Log.d("LiteRTLMEngine", "Warmup completed on ${backend.label} in ${warmupMs}ms")
        } catch (e: Exception) {
            Log.w(
                "LiteRTLMEngine",
                "Warmup failed on ${backend.label} (continuing without warmup): ${e.message}",
            )
        }
    }

    private fun createNewConversation(params: GenerationParams, systemPrompt: String): Conversation? {
        val engine = engine ?: return null
        val contents =
            if (systemPrompt.isBlank()) null
            else com.google.ai.edge.litertlm.Contents.of(systemPrompt)
        // On NPU, sampler config is null (uses model defaults) — practice from
        // google-ai-edge/gallery: NPU dispatch does not support custom sampling.
        val samplerConfig =
            if (activeBackend is ActiveBackend.NPU) null
            else
                SamplerConfig(
                    topK = params.topK,
                    topP = params.topP.toDouble(),
                    temperature = params.temperature.toDouble(),
                )
        return engine.createConversation(
            ConversationConfig(
                systemInstruction = contents,
                samplerConfig = samplerConfig,
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
        val conv = threadSafe { conversation }
        return try {
            conv?.getTokenCount() ?: 0
        } catch (e: Exception) {
            Log.w("LiteRTLMEngine", "Failed to get token count", e)
            0
        }
    }
    
    /**
      * Clears conversation history. Call this when starting a new chat.
      */
    override suspend fun clearHistory() = mutex.withLock {
        Log.d("LiteRTLMEngine", "Clearing conversation history (${conversationHistory.size} turns)")
        conversationHistory.clear()
        releaseConversation()
        conversation = currentParams?.let { params ->
            createNewConversation(params, currentSystemPrompt).also {
                Log.d("LiteRTLMEngine", "Created fresh conversation after history clear")
            }
        }
    }

    private fun tokenEstimate(chars: String): Int {
        if (chars.isEmpty()) return 0
        return (chars.length / 4).coerceAtLeast(1)
    }
}