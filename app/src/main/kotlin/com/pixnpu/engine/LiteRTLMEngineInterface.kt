package com.pixnpu.engine

import com.pixnpu.engine.Modality
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the LiteRT-LM engine to enable testability and dependency injection.
 */
interface LiteRTLMEngineInterface {
    
    /**
     * Observable engine metrics
     */
    val metrics: StateFlow<InferenceMetrics>
    
    /**
     * Check if the engine has a model loaded
     */
    val isLoaded: Boolean
    
     /**
      * Load a model from the given path with the specified parameters and input modality.
      * @param modelPath The path to the .litertlm model file
      * @param params The generation parameters to use
      * @param modality The user-chosen input modality (text/audio/vision/audio+vision)
      * @param backendPreference Accelerator preference (Auto falls back through
      *        NPU → GPU → CPU with automatic degradation; an explicit choice pins
      *        the load to that backend and surfaces failures instead)
      * @return The active backend that was used for loading
      */
     suspend fun load(
         modelPath: String,
         params: GenerationParams,
         modality: Modality = Modality.TextOnly,
         backendPreference: BackendPreference = BackendPreference.Auto,
     ): ActiveBackend
    
    /**
     * Reconfigure the engine with new parameters and system prompt
     * @param params The new generation parameters
     * @param systemPrompt The new system prompt
     */
    suspend fun reconfigure(params: GenerationParams, systemPrompt: String)
    
    /**
     * Unload the current model
     */
    suspend fun unload()
    
    /**
     * Generate a response from a text prompt
     * @param prompt The text prompt
     * @param template The prompt template to use
     * @return A flow of generated text chunks
     */
    fun generate(prompt: String, template: PromptTemplate = PromptTemplate.Auto): Flow<String>
    
    /**
     * Generate a response from multimodal content
     * @param content The list of content items (text and/or images)
     * @param template The prompt template to use
     * @param trackHistory Whether this turn should be read from and stored in the
     *        conversation history. Set to false for stateless calls (e.g. the
     *        OpenAI-compatible API) where the caller supplies the full context.
     * @param paramsOverride Generation parameters for this call only; when null the
     *        currently configured params are used
     * @return A flow of generated text chunks
     */
    fun generate(
        content: List<Content>,
        template: PromptTemplate = PromptTemplate.Auto,
        trackHistory: Boolean = true,
        paramsOverride: GenerationParams? = null,
    ): Flow<String>
    
    /**
     * Cancel the current generation
     */
    fun cancel()
    
    /**
     * Clear the conversation history
     */
    suspend fun clearHistory()
}
