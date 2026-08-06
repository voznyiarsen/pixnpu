package com.pixnpu.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Content
import com.pixnpu.di.AppContainer
import com.pixnpu.engine.GenerationParams
import com.pixnpu.engine.LiteRTLMEngine
import com.pixnpu.engine.LiteRTLMEngineInterface
import com.pixnpu.engine.PromptTemplate
import com.pixnpu.engine.PromptTemplates
import com.pixnpu.engine.SamplingPreset
import com.pixnpu.model.DownloadState
import com.pixnpu.model.LocalModel
import com.pixnpu.model.ModelLoadStatus
import com.pixnpu.model.ModelManager
import com.pixnpu.model.ModelManagerInterface
import com.pixnpu.engine.Modality
import com.pixnpu.server.OpenAiApiServer
import com.pixnpu.ui.components.pcm16ToWav
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Use interfaces for better testability
    private val container = AppContainer(application)
    private val manager: ModelManagerInterface = container.modelManager
    private val engine: LiteRTLMEngineInterface = container.engine

    // Persisted settings (see SettingsSheet in the UI)
    private val prefs =
        application.getSharedPreferences("pixnpu_settings", Context.MODE_PRIVATE)

    private val _apiPort = MutableStateFlow(
        prefs.getInt("api_port", OpenAiApiServer.PORT),
    )
    val apiPort: StateFlow<Int> = _apiPort.asStateFlow()

    private val _apiHost = MutableStateFlow(
        prefs.getString("api_host", OpenAiApiServer.HOST) ?: OpenAiApiServer.HOST,
    )
    val apiHost: StateFlow<String> = _apiHost.asStateFlow()

    private val _apiToken = MutableStateFlow(prefs.getString("api_token", "") ?: "")
    val apiToken: StateFlow<String> = _apiToken.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean("keep_screen_on", false))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _markdownEnabled = MutableStateFlow(prefs.getBoolean("markdown_enabled", true))
    val markdownEnabled: StateFlow<Boolean> = _markdownEnabled.asStateFlow()

    private val _samplingPresets = MutableStateFlow(loadSamplingPresets())
    val samplingPresets: StateFlow<List<SamplingPreset>> = _samplingPresets.asStateFlow()
    
    // Keep references to concrete implementations for cases where interface isn't sufficient
    private val rawManager: ModelManager = container.rawModelManager
    private val rawEngine: LiteRTLMEngine = container.rawEngine

    val models: StateFlow<List<LocalModel>> = manager.models
    val downloadState: StateFlow<DownloadState> = manager.downloadState
    val metrics = engine.metrics

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _params = MutableStateFlow(GenerationParams())
    val params: StateFlow<GenerationParams> = _params.asStateFlow()

    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _template = MutableStateFlow(PromptTemplate.Auto)
    val template: StateFlow<PromptTemplate> = _template.asStateFlow()

    private val _selectedModel = MutableStateFlow<LocalModel?>(null)
    val selectedModel: StateFlow<LocalModel?> = _selectedModel.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isLoadingModel = MutableStateFlow<String?>(null)
    val isLoadingModel: StateFlow<String?> = _isLoadingModel.asStateFlow()

    private val _modelLoadStatus = MutableStateFlow<Map<String, ModelLoadStatus>>(emptyMap())
    val modelLoadStatus: StateFlow<Map<String, ModelLoadStatus>> = _modelLoadStatus.asStateFlow()

    private val _pendingImageUri = MutableStateFlow<Uri?>(null)
    val pendingImageUri: StateFlow<Uri?> = _pendingImageUri.asStateFlow()

    private val _pendingAudio = MutableStateFlow<AudioClip?>(null)
    val pendingAudio: StateFlow<AudioClip?> = _pendingAudio.asStateFlow()

    private val _pendingTextFile = MutableStateFlow<TextFileClip?>(null)
    val pendingTextFile: StateFlow<TextFileClip?> = _pendingTextFile.asStateFlow()

    private val _engineMessage = MutableStateFlow<String?>(null)
    val engineMessage: StateFlow<String?> = _engineMessage.asStateFlow()

    private val _apiServerEnabled = MutableStateFlow(false)
    val apiServerEnabled: StateFlow<Boolean> = _apiServerEnabled.asStateFlow()

    val apiServerUrl: StateFlow<String> = _apiHost
        .combine(_apiPort) { host, port -> "http://$host:$port" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "http://${OpenAiApiServer.HOST}:${OpenAiApiServer.PORT}")

    private var generationJob: Job? = null
    private var apiServerJob: Job? = null
    private val messageId = AtomicLong(0)

    private val _selectedModality = MutableStateFlow(Modality.TextOnly)
    val selectedModality: StateFlow<Modality> = _selectedModality.asStateFlow()

    init {
        _selectedModality.value = prefs.getString("modality", null)
            ?.let { name -> runCatching { Modality.valueOf(name) }.getOrNull() }
            ?: Modality.TextOnly
        _params.value = GenerationParams(
            temperature = prefs.getFloat("param_temperature", GenerationParams().temperature),
            topK = prefs.getInt("param_topK", GenerationParams().topK),
            topP = prefs.getFloat("param_topP", GenerationParams().topP),
            maxTokens = prefs.getInt("param_maxTokens", GenerationParams().maxTokens),
            contextTokens = prefs.getInt("param_contextTokens", GenerationParams().contextTokens),
        )
        _systemPrompt.value = prefs.getString("system_prompt", "") ?: ""
        _template.value = prefs.getString("template", null)
            ?.let { name -> runCatching { PromptTemplate.valueOf(name) }.getOrNull() }
            ?: PromptTemplate.Auto
        startReconfigWatcher()
    }

    @OptIn(FlowPreview::class)
    private fun startReconfigWatcher() {
        viewModelScope.launch {
            _params.combine(_systemPrompt) { p, s -> p to s }
                .debounce(250)
                .collect { (params, systemPrompt) ->
                    if (engine.isLoaded) {
                        withContext(Dispatchers.IO) { engine.reconfigure(params, systemPrompt) }
                    }
                }
        }
    }

    fun updateParams(params: GenerationParams) {
        _params.value = params
        prefs.edit()
            .putFloat("param_temperature", params.temperature)
            .putInt("param_topK", params.topK)
            .putFloat("param_topP", params.topP)
            .putInt("param_maxTokens", params.maxTokens)
            .putInt("param_contextTokens", params.contextTokens)
            .apply()
    }

    fun updateSystemPrompt(value: String) {
        _systemPrompt.value = value
        prefs.edit().putString("system_prompt", value).apply()
    }

    fun setTemplate(template: PromptTemplate) {
        _template.value = template
        prefs.edit().putString("template", template.name).apply()
    }

    /**
     * Maximum URL length
     */
    private val maxUrlLength = 2048

    fun downloadModel(url: String, expectedSha256: String?) {
        Log.d("MainViewModel", "Download requested: $url")
        
        // Validate URL before passing to manager
        try {
            require(url.isNotBlank()) { "URL cannot be blank" }
            require(url.length <= maxUrlLength) { "URL exceeds maximum length" }
            require(url.startsWith("http://") || url.startsWith("https://")) { 
                "URL must start with http:// or https://" 
            }
            manager.startDownload(url, expectedSha256)
        } catch (e: IllegalArgumentException) {
            _engineMessage.value = e.message ?: "Invalid URL"
        }
    }

    fun importModel(uri: Uri) {
        Log.d("MainViewModel", "Import requested: $uri")
        manager.importModel(uri)
    }

    fun pauseDownload() = manager.pause()

    fun cancelDownload() {
        Log.d("MainViewModel", "Cancel requested")
        manager.cancel()
    }

    fun setSelectedModality(modality: Modality) {
        _selectedModality.value = modality
        prefs.edit().putString("modality", modality.name).apply()
    }

    fun setApiPort(port: Int) {
        val clamped = port.coerceIn(OpenAiApiServer.MIN_PORT, OpenAiApiServer.MAX_PORT)
        if (clamped == _apiPort.value) return
        _apiPort.value = clamped
        prefs.edit().putInt("api_port", clamped).apply()
        if (_apiServerEnabled.value) {
            // A running server keeps its original port; stop it so the change applies.
            apiServerJob?.cancel()
            apiServerJob = viewModelScope.launch {
                withContext(Dispatchers.IO) { container.openAiApiServer.stop() }
                _apiServerEnabled.value = false
                _engineMessage.value = "API server stopped — port changed to $clamped"
            }
        }
    }

    fun setApiHost(host: String) {
        val trimmed = host.trim()
        if (trimmed.isEmpty() || trimmed == _apiHost.value) return
        _apiHost.value = trimmed
        prefs.edit().putString("api_host", trimmed).apply()
        if (_apiServerEnabled.value) {
            // A running server keeps its original bind address; stop it so the change applies.
            apiServerJob?.cancel()
            apiServerJob = viewModelScope.launch {
                withContext(Dispatchers.IO) { container.openAiApiServer.stop() }
                _apiServerEnabled.value = false
                _engineMessage.value = "API server stopped — bind host changed to $trimmed"
            }
        }
    }

    fun setApiToken(token: String) {
        val trimmed = token.trim()
        if (trimmed == _apiToken.value) return
        _apiToken.value = trimmed
        prefs.edit().putString("api_token", trimmed).apply()
        if (_apiServerEnabled.value) {
            // A running server keeps its original token; stop it so the change applies.
            apiServerJob?.cancel()
            apiServerJob = viewModelScope.launch {
                withContext(Dispatchers.IO) { container.openAiApiServer.stop() }
                _apiServerEnabled.value = false
                _engineMessage.value = "API server stopped — token changed"
            }
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _keepScreenOn.value = enabled
        prefs.edit().putBoolean("keep_screen_on", enabled).apply()
    }

    fun setMarkdownEnabled(enabled: Boolean) {
        _markdownEnabled.value = enabled
        prefs.edit().putBoolean("markdown_enabled", enabled).apply()
    }

    /** Max length of a custom preset name. */
    private val maxPresetNameLength = 40

    fun createSamplingPreset(name: String, params: GenerationParams) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > maxPresetNameLength) {
            _engineMessage.value = "Preset name must be 1-$maxPresetNameLength characters"
            return
        }
        val updated = _samplingPresets.value
            .filterNot { it.name == trimmed } +
            SamplingPreset(trimmed, params.temperature, params.topK, params.topP)
        _samplingPresets.value = updated
        saveSamplingPresets(updated)
        _engineMessage.value = "Preset \"$trimmed\" saved"
    }

    fun deleteSamplingPreset(name: String) {
        val updated = _samplingPresets.value.filterNot { it.name == name }
        _samplingPresets.value = updated
        saveSamplingPresets(updated)
        _engineMessage.value = "Preset \"$name\" deleted"
    }

    fun applySamplingPreset(name: String) {
        val preset = _samplingPresets.value.find { it.name == name } ?: return
        updateParams(
            _params.value.copy(
                temperature = preset.temperature,
                topK = preset.topK,
                topP = preset.topP,
            ),
        )
        _engineMessage.value = "Preset \"$name\" applied"
    }

    private fun saveSamplingPresets(presets: List<SamplingPreset>) {
        prefs.edit()
            .putString("sampling_presets", Json.encodeToString(presets))
            .apply()
    }

    private fun loadSamplingPresets(): List<SamplingPreset> = runCatching {
        Json.decodeFromString<List<SamplingPreset>>(
            prefs.getString("sampling_presets", "[]") ?: "[]",
        )
    }.getOrDefault(emptyList())

    /**
     * Restores every persisted setting (host, port, modality, params, prompt,
     * template, toggles, presets) to its default value.
     */
    fun resetSettings() {
        if (_apiServerEnabled.value) {
            apiServerJob?.cancel()
            apiServerJob = viewModelScope.launch {
                withContext(Dispatchers.IO) { container.openAiApiServer.stop() }
                _apiServerEnabled.value = false
            }
        }
        _apiPort.value = OpenAiApiServer.PORT
        _apiHost.value = OpenAiApiServer.HOST
        _apiToken.value = ""
        _selectedModality.value = Modality.TextOnly
        _params.value = GenerationParams()
        _systemPrompt.value = ""
        _template.value = PromptTemplate.Auto
        _keepScreenOn.value = false
        _markdownEnabled.value = true
        _samplingPresets.value = emptyList()
        prefs.edit().clear().apply()
        _engineMessage.value = "Settings reset to defaults"
    }

    /**
     * Keeps the API server's view of the loaded model in sync with the UI state.
     */
    private fun syncServerModelId() {
        container.openAiApiServer.currentModelId.set(
            _selectedModel.value?.name?.removeSuffix(".litertlm"),
        )
    }

    fun toggleApiServer() {
        if (_apiServerEnabled.value) {
            apiServerJob?.cancel()
            apiServerJob = viewModelScope.launch {
                withContext(Dispatchers.IO) { container.openAiApiServer.stop() }
                _apiServerEnabled.value = false
            }
        } else {
            if (!engine.isLoaded) {
                _engineMessage.value = "Load a model before starting the API server"
                return
            }
            apiServerJob?.cancel()
            apiServerJob = viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    container.openAiApiServer.start(
                        _apiHost.value,
                        _apiPort.value,
                        tokenProvider = { _apiToken.value },
                    )
                }
                _apiServerEnabled.value = true
            }
        }
    }

     fun loadModel(model: LocalModel, modality: Modality = _selectedModality.value) {
Log.d("MainViewModel", "Loading model: ${model.name} modality=${modality}")
        
        // Validate model file exists
        val modelFile = java.io.File(model.absolutePath)
        if (!modelFile.exists() || !modelFile.isFile) {
            _engineMessage.value = "Model file not found: ${model.name}"
            _modelLoadStatus.value = _modelLoadStatus.value.toMutableMap().apply {
                this[model.name] = ModelLoadStatus.Failed
            }
            return
        }
        
        viewModelScope.launch {
            if (_isLoadingModel.value != null) {
                Log.w("MainViewModel", "Load ignored: another model is already loading")
                return@launch
            }
            // If a model is currently unloading, wait for it to finish before
            // starting the new load, otherwise the in-flight unload can race the
            // new load (unloading the freshly-loaded engine and leaving the UI
            // reporting the wrong model).
            while (_modelLoadStatus.value.any { it.value == ModelLoadStatus.Unloading }) {
                Log.d("MainViewModel", "Waiting for model unload to finish before loading ${model.name}")
                delay(50)
            }
            _engineMessage.value = null
            _isLoadingModel.value = model.name
            _modelLoadStatus.value = _modelLoadStatus.value.toMutableMap().apply {
                this[model.name] = ModelLoadStatus.Loading
                this.keys.filter { it != model.name }.forEach { key ->
                    this[key] = if (_selectedModel.value?.name == key) ModelLoadStatus.Unloading else ModelLoadStatus.Idle
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    if (engine.isLoaded) engine.unload()
                    engine.load(model.absolutePath, _params.value, modality)
                    engine.clearHistory()
                }
                _selectedModel.value = model
                syncServerModelId()
                _modelLoadStatus.value = _modelLoadStatus.value.toMutableMap().apply {
                    this[model.name] = ModelLoadStatus.Success
                    this.keys.filter { it != model.name && this[it] == ModelLoadStatus.Unloading }.forEach {
                        this[it] = ModelLoadStatus.Idle
                    }
                }
                clearChat()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load model: ${model.name}", e)
                _selectedModel.value = null
                syncServerModelId()
                _engineMessage.value = e.message ?: "Failed to load model"
                _modelLoadStatus.value = _modelLoadStatus.value.toMutableMap().apply {
                    this[model.name] = ModelLoadStatus.Failed
                    this.keys.filter { it != model.name && this[it] == ModelLoadStatus.Unloading }.forEach {
                        this[it] = ModelLoadStatus.Idle
                    }
                }
            } finally {
                _isLoadingModel.value = null
            }
        }
    }

    fun unloadModel(model: LocalModel? = null) {
        viewModelScope.launch {
            val targetModel = model ?: _selectedModel.value
            targetModel?.let { m ->
                _modelLoadStatus.value = _modelLoadStatus.value + (m.name to ModelLoadStatus.Unloading)
            }
            _selectedModel.value = null
            syncServerModelId()
            try {
                withContext(Dispatchers.IO) { engine.unload() }
            } finally {
                targetModel?.let { m ->
                    _modelLoadStatus.value = _modelLoadStatus.value + (m.name to ModelLoadStatus.Idle)
                }
            }
        }
    }

     fun deleteModel(model: LocalModel) {
        viewModelScope.launch {
            if (_selectedModel.value?.absolutePath == model.absolutePath) {
                withContext(Dispatchers.IO) { engine.unload() }
                _selectedModel.value = null
                syncServerModelId()
            }
            _modelLoadStatus.value = _modelLoadStatus.value - model.name
            manager.delete(model)
        }
    }

     fun verifyModel(model: LocalModel) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    manager.verify(model, isCancelled = { rawManager.isCancelled() })
                }
            } catch (e: Exception) {
                _engineMessage.value = e.message ?: "Verification failed"
            }
        }
    }

    fun setPendingImage(uri: Uri?) {
        _pendingImageUri.value = uri
    }

    fun setPendingAudio(clip: AudioClip?) {
        _pendingAudio.value = clip
    }

    fun setPendingTextFile(clip: TextFileClip?) {
        _pendingTextFile.value = clip
    }

    /**
     * Maximum prompt length in characters
     */
    private val maxPromptLength = 32000

    /**
     * Maximum message history to retain (prevents memory leaks)
     */
    private val maxMessageHistory = 200

    fun send(text: String) {
        val prompt = text.trim()
        val imageUri = _pendingImageUri.value
        val audio = _pendingAudio.value
        val textFile = _pendingTextFile.value
        
        // Validate input
        if (prompt.isEmpty() && imageUri == null && audio == null && textFile == null) return
        if (prompt.isNotEmpty() && prompt.length > maxPromptLength) {
            _engineMessage.value = "Prompt exceeds maximum length of $maxPromptLength characters"
            return
        }
        
        if (_isGenerating.value) {
            Log.w("MainViewModel", "send() called while generating, ignoring")
            return
        }
        if (!engine.isLoaded) {
            _engineMessage.value = "No model loaded. Select a model from Models tab."
            return
        }

        Log.d("MainViewModel", "Sending prompt (${prompt.length} chars, image=${imageUri != null}, audio=${audio != null}, file=${textFile != null})")
        val userMessage = ChatMessage(
            id = messageId.incrementAndGet(),
            role = ChatRole.USER,
            text = prompt,
            imageUri = imageUri,
            audioBytes = audio?.bytes,
            textFile = textFile,
        )
        val assistantMessage =
            ChatMessage(messageId.incrementAndGet(), ChatRole.ASSISTANT, "", streaming = true)
        _messages.update { messages ->
            val newMessages = messages + userMessage + assistantMessage
            // Evict oldest messages if over limit to prevent memory leaks
            if (newMessages.size > maxMessageHistory) {
                newMessages.drop(newMessages.size - maxMessageHistory)
            } else {
                newMessages
            }
        }
        _pendingImageUri.value = null
        _pendingAudio.value = null
        _pendingTextFile.value = null
        _isGenerating.value = true

        generationJob = viewModelScope.launch {
            try {
                val contentList = buildList {
                    if (audio != null) {
                        // LiteRT-LM's native preprocessor (miniaudio) needs a container
                        // header to decode audio; raw PCM fails with error -10.
                        add(Content.AudioBytes(pcm16ToWav(audio.bytes)))
                    }
                    if (imageUri != null) {
                        val path = withContext(Dispatchers.IO) {
                            resolveImagePath(imageUri)
                        }
                        if (path != null) {
                            add(Content.ImageFile(path))
                        }
                    }
                    if (textFile != null) {
                        // File contents are injected as text context, wrapped in
                        // markers so the model can attribute the context to a file.
                        add(Content.Text(PromptTemplates.wrapFileContext(textFile.name, textFile.content, textFile.truncated)))
                    }
                    // Text last so the final prompt token follows the media (gallery practice).
                    add(Content.Text(prompt))
                }
                engine.generate(contentList, _template.value).collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        _messages.update { list ->
                            val lastIndex = list.size - 1
                            if (lastIndex >= 0 && list[lastIndex].role == ChatRole.ASSISTANT
                                && list[lastIndex].streaming
                            ) {
                                list.toMutableList().also {
                                    val msg = list[lastIndex]
                                    it[lastIndex] = msg.copy(text = msg.text + chunk)
                                }
                            } else {
                                list
                            }
                        }
                    }
                    delay(0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.update { list ->
                    list.toMutableList().also {
                        val last = it.size - 1
                        val msg = it[last]
                        if (msg.role == ChatRole.ASSISTANT) {
                            it[last] = msg.copy(text = msg.text + "\n\n[error] ${e.message}")
                        }
                    }
                }
            } finally {
                _isGenerating.value = false
                _messages.update { list ->
                    list.map { msg ->
                        if (msg.role == ChatRole.ASSISTANT && msg.streaming) {
                            msg.copy(streaming = false)
                        } else {
                            msg
                        }
                    }
                }
            }
        }
    }

    private val tempImageFiles = mutableListOf<File>()

    private fun resolveImagePath(uri: Uri): String? {
        val context = getApplication<Application>()
        return try {
            val tempFile = File(context.cacheDir, "img_${System.nanoTime()}.jpg")
            tempImageFiles.add(tempFile)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { out -> input.copyTo(out) }
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to resolve image path", e)
            null
        }
    }

    private fun cleanupTempImages() {
        for (file in tempImageFiles) {
            runCatching { if (file.exists()) file.delete() }
        }
        tempImageFiles.clear()
    }

    fun stop() {
        Log.d("MainViewModel", "Stopping generation")
        engine.cancel()
        generationJob?.cancel()
    }

    fun clearChat() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    engine.clearHistory()
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Failed to clear engine history", e)
                }
            }
        }
        _messages.value = emptyList()
    }

    override fun onCleared() {
        cleanupTempImages()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                container.openAiApiServer.stop()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to stop API server on clear", e)
            }
            try {
                engine.unload()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to unload engine on clear", e)
            }
        }
        super.onCleared()
    }
}