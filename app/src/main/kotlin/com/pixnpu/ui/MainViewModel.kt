package com.pixnpu.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Content
import com.pixnpu.engine.GenerationParams
import com.pixnpu.engine.LiteRTLMEngine
import com.pixnpu.engine.PromptTemplate
import com.pixnpu.engine.PromptTemplates
import com.pixnpu.model.DownloadState
import com.pixnpu.model.LocalModel
import com.pixnpu.model.ModelLoadStatus
import com.pixnpu.model.ModelManager
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = ModelManager(application)
    private val engine = LiteRTLMEngine(application)

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

    private val _engineMessage = MutableStateFlow<String?>(null)
    val engineMessage: StateFlow<String?> = _engineMessage.asStateFlow()

    private var generationJob: Job? = null
    private val messageId = AtomicLong(0)

    init {
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
    }

    fun updateSystemPrompt(value: String) {
        _systemPrompt.value = value
    }

    fun setTemplate(template: PromptTemplate) {
        _template.value = template
    }

    fun downloadModel(url: String, expectedSha256: String?) {
        Log.d("MainViewModel", "Download requested: $url")
        manager.startDownload(url, expectedSha256)
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

    fun loadModel(model: LocalModel) {
        Log.d("MainViewModel", "Loading model: ${model.name}")
        viewModelScope.launch {
            _engineMessage.value = null
            _isLoadingModel.value = model.name
            _modelLoadStatus.value = _modelLoadStatus.value + (model.name to ModelLoadStatus.Loading)
            try {
                withContext(Dispatchers.IO) {
                    if (engine.isLoaded) engine.unload()
                    engine.load(model.absolutePath, _params.value)
                    engine.clearHistory()
                }
                _selectedModel.value = model
                _modelLoadStatus.value = _modelLoadStatus.value + (model.name to ModelLoadStatus.Success)
                clearChat()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load model: ${model.name}", e)
                _selectedModel.value = null
                _engineMessage.value = e.message ?: "Failed to load model"
                _modelLoadStatus.value = _modelLoadStatus.value + (model.name to ModelLoadStatus.Failed)
            } finally {
                _isLoadingModel.value = null
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.unload() }
            _selectedModel.value?.let { model ->
                _modelLoadStatus.value = _modelLoadStatus.value + (model.name to ModelLoadStatus.Idle)
            }
            _selectedModel.value = null
        }
    }

     fun deleteModel(model: LocalModel) {
        viewModelScope.launch {
            if (_selectedModel.value?.absolutePath == model.absolutePath) {
                withContext(Dispatchers.IO) { engine.unload() }
                _selectedModel.value = null
            }
            _modelLoadStatus.value = _modelLoadStatus.value - model.name
            manager.delete(model)
        }
    }

     fun verifyModel(model: LocalModel) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    manager.verify(model, isCancelled = { manager.isCancelled() })
                }
            } catch (e: Exception) {
                _engineMessage.value = e.message ?: "Verification failed"
            }
        }
    }

    fun setPendingImage(uri: Uri?) {
        _pendingImageUri.value = uri
    }

    fun send(text: String) {
        val prompt = text.trim()
        val imageUri = _pendingImageUri.value
        if (prompt.isEmpty() && imageUri == null) return
        if (_isGenerating.value) {
            Log.w("MainViewModel", "send() called while generating, ignoring")
            return
        }
        if (!engine.isLoaded) {
            _engineMessage.value = "No model loaded. Select a model from Models tab."
            return
        }

        Log.d("MainViewModel", "Sending prompt (${prompt.length} chars, image=${imageUri != null})")
        val userMessage = ChatMessage(
            id = messageId.incrementAndGet(),
            role = ChatRole.USER,
            text = prompt,
            imageUri = imageUri,
        )
        val assistantMessage =
            ChatMessage(messageId.incrementAndGet(), ChatRole.ASSISTANT, "", streaming = true)
        _messages.update { it + userMessage + assistantMessage }
        _pendingImageUri.value = null
        _isGenerating.value = true

        generationJob = viewModelScope.launch {
            try {
                val contentList = buildList {
                    add(Content.Text(prompt))
                    if (imageUri != null) {
                        val path = withContext(Dispatchers.IO) {
                            resolveImagePath(imageUri)
                        }
                        if (path != null) {
                            add(Content.ImageFile(path))
                        }
                    }
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
                engine.unload()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to unload engine on clear", e)
            }
        }
        super.onCleared()
    }
}