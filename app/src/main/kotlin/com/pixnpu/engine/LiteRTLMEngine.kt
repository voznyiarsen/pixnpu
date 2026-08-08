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
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicBoolean

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
    private var currentModelPath: String? = null
    private var currentModality: Modality = Modality.TextOnly
    private var preferredBackend: BackendPreference = BackendPreference.Auto

    // Set once a generation fails with a backend (NPU dispatch) error: the
    // engine is reloaded on GPU and every subsequent load skips NPU. This
    // keeps the app usable when the NPU dispatch library is absent from the
    // APK or the model's compiled dispatch is broken (functiongemma-G5), where
    // NPU initialization succeeds but every inference fails at dispatch time.
    private val degradedBackend = AtomicBoolean(false)
    
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
      val supportsVideo: Boolean get() = threadSafe { activeSupportsVision && activeSupportsAudio }

     override suspend fun load(
         modelPath: String,
         params: GenerationParams,
         modality: Modality,
         backendPreference: BackendPreference,
     ): ActiveBackend =
         mutex.withLock {
             Log.d("LiteRTLMEngine", "Loading model: $modelPath modality=$modality backendPreference=$backendPreference")
             releaseConversation()
             unloadEngine()
             conversationHistory.clear()
             preferredBackend = backendPreference
             val init = initializeBackend(modelPath, params, modality)
             activeBackend = init.backend
             activeSupportsVision = modality.supportsVision
             activeSupportsAudio = modality.supportsAudio
             currentParams = params
             currentSystemPrompt = ""
             currentModelPath = modelPath
             currentModality = modality
             // An explicit (re)load resets the degradation, so a later NPU-fixed
             // build or another model can try NPU again.
             degradedBackend.set(false)
             conversation = createNewConversation(params, "")
             warmup(init.backend)
             _metrics.value = _metrics.value.copy(
                 status = EngineStatus.Ready,
                 backend = init.backend.label,
                 maxContextTokens = params.contextTokens,
                 supportsVision = modality.supportsVision,
                 supportsAudio = modality.supportsAudio,
                 supportsVideo = modality.supportsVision && modality.supportsAudio,
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
       * Thinking models (Gemma 3+/4) leak channel-switch control tokens into
       * the text stream: "<|channel|>" plus the name of the channel they
       * switch to (e.g. "<|channel|> <|reason|>"). The reasoning itself
       * arrives via channels["thought"], so the markers are not part of the
       * visible reply — strip them, keeping any actual text.
       */
      private val channelMarkerRegex = Regex("<\\|channel\\|>(?:\\s*<\\|[a-z_]+\\|>)?")

      private fun String.stripChannelMarkers(): String = replace(channelMarkerRegex, "")

      /**
       * Bounds a content list to [maxContentItems]: all text parts are merged
       * into a single trailing text item (media first, text last — gallery
       * practice), and if media alone still exceeds the cap the OLDEST items
       * are dropped. The merged text is always the last item, so the current
       * user prompt survives eviction. Long media-heavy histories never fail
       * with "Content exceeds maximum of N items".
       */
      private fun boundContentItems(items: List<Content>, max: Int = maxContentItems): List<Content> {
          val text = StringBuilder()
          val media = mutableListOf<Content>()
          for (item in items) {
              if (item is Content.Text) {
                  if (text.isNotEmpty()) text.append("\n")
                  text.append(item.text)
              } else {
                  media.add(item)
              }
          }
          val result = if (text.isEmpty()) media else media + Content.Text(text.toString())
          if (result.size <= max) return result
          return result.takeLast(max)
      }

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
          var attempt = 0
          while (true) {
              try {
                  generateOnce(content, trackHistory, paramsOverride).collect { emit(it) }
                  return@flow
              } catch (e: CancellationException) {
                  throw e
              } catch (e: Exception) {
                  // Backend (NPU dispatch) failures degrade once: the engine is
                  // reloaded on GPU and the generation retried automatically.
                  // Content/template errors must NOT retry — they would fail the
                  // same way on every backend.
                  if (attempt == 0 && isBackendFailure(e) && degradeToGpu()) {
                      attempt++
                      continue
                  }
                  throw e
              }
          }
      }

      private fun generateOnce(
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

          // Update metrics atomically. getTokenCount() is a blocking native call,
          // so it runs off the collector thread.
          val ctxTokensAtStart = withContext(Dispatchers.IO) { safeTokenCount() }
          mutex.withLock {
              _metrics.value = _metrics.value.copy(
                  status = EngineStatus.Generating,
                  ttftMs = null,
                  totalTokens = 0,
                  tokensPerSecond = 0.0,
                  currentTokensPerSecond = 0.0,
                  contextTokens = ctxTokensAtStart,
              )
          }

          try {
              val contents = Contents.of(content)
              Log.d("LiteRTLMEngine", "Starting generation on backend: ${state.backend?.label}")

              val (newConversation, fullPrompt) = withContext(Dispatchers.Default) {
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
                  // Thinking models (Gemma 3+/4) stream their reasoning through the
                  // "thought" channel instead of Content.Text; it is counted for
                  // usage (metrics.thinkingTokens) but not emitted as reply text.
                  var thinkingSeen = 0
                  newConversation.sendMessageAsync(
                      fullPrompt,
                      thinkingConfig = ThinkingConfig(
                          enableThinking = state.params.thinkingEnabled,
                          thinkingTokenBudget = state.params.thinkingTokenBudget,
                      ),
                  ).collect { message ->
                      val tokenText = message.contents.contents
                          .filterIsInstance<Content.Text>()
                          .joinToString("") { it.text }
                          .stripChannelMarkers()
                      message.channels["thought"]?.let { thinking ->
                          if (thinking.length > thinkingSeen) thinkingSeen = thinking.length
                      }
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
                  if (thinkingSeen > 0) {
                      mutex.withLock {
                          _metrics.value = _metrics.value.copy(
                              thinkingTokens = estimateChars(fullReply.length + thinkingSeen) - estimateChars(fullReply.length),
                          )
                      }
                  }
              } catch (e: CancellationException) {
                  // Cancelled mid-stream: propagate without running the sync
                  // fallback — regenerating the reply after the user hit stop
                  // would block the caller and resurrect a cancelled turn.
                  throw e
              } catch (e: Exception) {
                  // Fallback: synchronous generation with word-sized chunk emission
                  // (the pre-1.11 workaround). Runs on IO: sendMessage is a
                  // blocking native call that can take the full reply duration.
                  //
                  // CRITICAL: the async attempt already pushed the user message
                  // into newConversation's native history before failing, so
                  // reusing it here pushes the message a second time — the gemma3
                  // template then rejects the conversation with "Conversation
                  // roles must alternate user/assistant/..." (user at odd index).
                  // The fallback therefore builds a FRESH conversation.
                  Log.w("LiteRTLMEngine", "Native streaming failed, falling back to sync generation", e)
                  val fresh = withContext(Dispatchers.Default) {
                      buildConversationWithCleanedHistory(
                          Contents.of(content),
                          state.engine,
                          state.params,
                          state.systemPrompt,
                          state.backend,
                          state.history,
                      )
                  }
                  mutex.withLock {
                      conversation?.close()
                      conversation = fresh.first
                  }
                  val replyMessage = withContext(Dispatchers.IO) {
                      fresh.first.sendMessage(
                          fresh.second,
                          thinkingConfig = ThinkingConfig(
                              enableThinking = state.params.thinkingEnabled,
                              thinkingTokenBudget = state.params.thinkingTokenBudget,
                          ),
                      )
                  }
                  // Text only — the thinking channel stays out of the visible reply.
                  val reply = replyMessage.contents.contents
                      .filterIsInstance<Content.Text>()
                      .joinToString("")
                      .stripChannelMarkers()
                  replyMessage.channels["thought"]?.let { thinking ->
                      mutex.withLock {
                          _metrics.value = _metrics.value.copy(
                              thinkingTokens = estimateChars(fullReply.length + thinking.length) - estimateChars(fullReply.length),
                          )
                      }
                  }
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

          } catch (e: CancellationException) {
              // Normal stop path (UI cancel / API abort): not an error, rethrow
              // without the error log.
              throw e
          } catch (e: Exception) {
              Log.e("LiteRTLMEngine", "Generation failed", e)
              throw e
          } finally {
              val done = System.nanoTime()
              val totalSecs = (done - startedAt) / 1_000_000_000.0
              // Cancellation-safe metrics reset. Once a coroutine is cancelled,
              // every suspend call — including mutex.withLock — throws immediately,
              // which used to leave _metrics stuck at Generating and the API
              // server's busy gate locked forever. tryLock() is non-suspending,
              // so the reset always runs.
              if (mutex.tryLock()) {
                  try {
                      val ctxTokens =
                          if (coroutineContext.isActive) safeTokenCount()
                          else _metrics.value.contextTokens
                      _metrics.value = _metrics.value.copy(
                          status = EngineStatus.Ready,
                          totalTokens = textLength,
                          tokensPerSecond = if (totalSecs > 0) textLength / totalSecs else 0.0,
                          currentTokensPerSecond = if (totalSecs > 0) textLength / totalSecs else 0.0,
                          contextTokens = ctxTokens,
                      )
                  } finally {
                      mutex.unlock()
                  }
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
       * @return The new conversation plus the full prompt contents (cleaned history +
       *         new user content) that must be sent for generation.
       */
      private suspend fun buildConversationWithCleanedHistory(
          newUserContent: Contents,
          engineRef: Engine,
          params: GenerationParams,
          systemPrompt: String,
          backend: ActiveBackend?,
          history: List<Pair<List<Content>, String>>
      ): Pair<Conversation, Contents> {
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

          // Bound to the engine's per-generation content cap: merge all text
          // into one trailing item and evict the oldest media if a long
          // history would still exceed it (see boundContentItems).
          val boundedContents = boundContentItems(historyContents)
          
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
          val fullPromptContents = Contents.of(boundedContents)
          Log.d("LiteRTLMEngine", "Sending message with ${boundedContents.size} history items")
          
          return Pair(newConversation, fullPromptContents)
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
         val backendCandidates = when (preferredBackend) {
             BackendPreference.Auto -> buildList {
                 // After a dispatch failure NPU is skipped entirely: its init succeeds
                 // but every inference fails, so retrying NPU wastes a full load cycle.
                 if (!degradedBackend.get()) add(ActiveBackend.NPU)
                 add(ActiveBackend.GPU)
                 add(ActiveBackend.CPU())
             }
             BackendPreference.CPU -> listOf(ActiveBackend.CPU())
             BackendPreference.GPU -> listOf(ActiveBackend.GPU)
             BackendPreference.NPU -> listOf(ActiveBackend.NPU)
         }
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
             "Failed to initialize model on backend(s) ${backendCandidates.joinToString("/") { it.label }} " +
                 "for modality=$modality: ${lastError?.message}",
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
     * Reloads the engine on the next available backend (GPU) after a dispatch
     * failure, so the generation can be retried once. Only succeeds when the
     * current backend is NPU (degrading a GPU/CPU run would be pointless).
     */
    private suspend fun degradeToGpu(): Boolean = mutex.withLock {
        // An explicit backend preference is a user choice: honor it by
        // surfacing the failure instead of silently switching accelerators.
        if (preferredBackend != BackendPreference.Auto) return@withLock false
        if (degradedBackend.get()) return@withLock false
        val path = currentModelPath ?: return@withLock false
        val params = currentParams ?: return@withLock false
        degradedBackend.set(true)
        return@withLock try {
            releaseConversation()
            unloadEngine()
            val init = initializeBackend(path, params, currentModality)
            activeBackend = init.backend
            activeSupportsVision = currentModality.supportsVision
            activeSupportsAudio = currentModality.supportsAudio
            Log.w("LiteRTLMEngine", "Generation failed on NPU — degraded to backend ${init.backend.label}")
            true
        } catch (e: Exception) {
            Log.e("LiteRTLMEngine", "Failed to degrade backend", e)
            false
        }
    }

    /**
     * True when the failure is backend-related (NPU dispatch, delegate, kernel)
     * rather than content/template-related. Template errors ("Failed to apply
     * template", "roles must alternate") are content problems and must not
     * trigger a backend retry.
     */
    private fun isBackendFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val msg = t.message?.lowercase() ?: ""
            if (msg.contains("failed to apply template") ||
                msg.contains("roles must alternate") ||
                msg.contains("invalid operation")
            ) {
                return false
            }
            if (msg.contains("dispatch") || msg.contains("backend") ||
                msg.contains("delegate") || msg.contains("npu") ||
                msg.contains("kernel") || msg.contains("litert")
            ) {
                return true
            }
            t = t.cause
        }
        return false
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

    /** Char-count token estimate for already-counted text (thinking channel). */
    private fun estimateChars(count: Int): Int = if (count <= 0) 0 else (count / 4).coerceAtLeast(1)
}