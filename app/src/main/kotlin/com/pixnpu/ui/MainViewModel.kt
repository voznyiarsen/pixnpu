package com.pixnpu.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Content
import com.pixnpu.engine.GenerationParams
import com.pixnpu.engine.LiteRTLMEngine
import com.pixnpu.engine.PromptTemplate
import com.pixnpu.engine.PromptTemplates
import com.pixnpu.model.DownloadState
import com.pixnpu.model.LocalModel
import com.pixnpu.model.ModelManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
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
                    if (engine.isLoaded) engine.reconfigure(params, systemPrompt)
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
        manager.startDownload(url, expectedSha256)
    }

    fun pauseDownload() = manager.pause()

    fun cancelDownload() = manager.cancel()

    fun loadModel(model: LocalModel) {
        viewModelScope.launch {
            _engineMessage.value = null
            _isLoadingModel.value = model.name
            try {
                if (engine.isLoaded) engine.unload()
                engine.load(model.absolutePath, _params.value)
                _selectedModel.value = model
            } catch (t: Throwable) {
                _selectedModel.value = null
                _engineMessage.value = t.message ?: "Failed to load model"
            } finally {
                _isLoadingModel.value = null
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            engine.unload()
            _selectedModel.value = null
        }
    }

    fun deleteModel(model: LocalModel) {
        viewModelScope.launch {
            if (_selectedModel.value?.absolutePath == model.absolutePath) {
                engine.unload()
                _selectedModel.value = null
            }
            manager.delete(model)
        }
    }

    fun verifyModel(model: LocalModel) {
        viewModelScope.launch {
            try {
                manager.verify(model)
            } catch (t: Throwable) {
                _engineMessage.value = t.message ?: "Verification failed"
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
        if (_isGenerating.value) return
        if (!engine.isLoaded) {
            _engineMessage.value = "No model loaded. Select a model from Models tab."
            return
        }

        val wrapped = PromptTemplates.wrap(prompt, _template.value, _systemPrompt.value)
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
                    add(Content.Text(wrapped))
                    if (imageUri != null) {
                        val path = withContext(Dispatchers.IO) {
                            resolveImagePath(imageUri)
                        }
                        if (path != null) {
                            add(Content.ImageFile(path))
                        }
                    }
                }
                engine.generate(contentList).collect { chunk ->
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

    private fun resolveImagePath(uri: Uri): String? {
        val context = getApplication<Application>()
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val tempFile = java.io.File(context.cacheDir, "img_${System.nanoTime()}.jpg")
            tempFile.outputStream().use { out -> inputStream.copyTo(out) }
            inputStream.close()
            tempFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun stop() {
        engine.cancel()
        generationJob?.cancel()
    }

    fun clearChat() {
        _messages.value = emptyList()
    }

    override fun onCleared() {
        try {
            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    engine.unload()
                }
            }
        } catch (_: Throwable) {
        }
        super.onCleared()
    }
}