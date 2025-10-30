package com.peerchat.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.engine.ServiceRegistry
import com.peerchat.app.engine.EngineStreamEvent
import com.peerchat.app.engine.StreamingEngine
import com.peerchat.app.engine.StoredEngineConfig
import com.peerchat.app.engine.PromptComposer
import com.peerchat.app.engine.ModelStateCache
import com.peerchat.app.util.optFloatOrNull
import com.peerchat.app.util.optIntOrNull
import com.peerchat.app.util.optStringOrNull
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import com.peerchat.templates.TemplateCatalog
import com.peerchat.app.docs.OcrService
import com.peerchat.rag.RagService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

@OptIn(FlowPreview::class)
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext

    // Services from registry
    private val modelService = ServiceRegistry.modelService
    private val documentService = ServiceRegistry.documentService
    private val chatService = ServiceRegistry.chatService
    private val searchService = ServiceRegistry.searchService

    // Direct repository access for fine-grained DB operations
    private val repository: PeerChatRepository = PeerChatRepository.from(appContext)

    // Cache model KV state per chat to enable fast context restore
    private val modelCache = ModelStateCache(appContext)

    private val templateOptions = TemplateCatalog.descriptors().map {
        TemplateOption(
            id = it.id,
            label = it.displayName,
            stopSequences = it.stopSequences
        )
    }

    private val _uiState = MutableStateFlow(HomeUiState(templates = templateOptions))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>()
    val events = _events.asSharedFlow()

    private val selectedFolderId = MutableStateFlow<Long?>(null)
    private val activeChatId = MutableStateFlow<Long?>(null)
    private val searchQuery = MutableStateFlow("")

    init {
        observeFolders()
        observeChats()
        observeSearch()
        observeEngine()
        observeManifests()
        observeMessages()
        restoreModelConfig()
    }

    private fun observeFolders() {
        viewModelScope.launch {
            repository.observeFolders()
                .collectLatest { folders ->
                    _uiState.update { it.copy(folders = folders) }
                }
        }
    }

    private fun observeChats() {
        viewModelScope.launch {
            selectedFolderId
                .flatMapLatest { repository.observeChats(it) }
                .collectLatest { chats ->
                    val currentActive = activeChatId.value
                    val resolvedActive = when {
                        chats.isEmpty() -> null
                        currentActive == null || chats.none { it.id == currentActive } -> chats.first().id
                        else -> currentActive
                    }
                    val activeChanged = resolvedActive != null && resolvedActive != currentActive
                    activeChatId.value = resolvedActive
                    _uiState.update {
                        it.copy(
                            chats = chats,
                            activeChatId = resolvedActive,
                            selectedFolderId = selectedFolderId.value
                        )
                    }
                    if (activeChanged) {
                        loadChatSettings(resolvedActive!!)
                    }
                    if (chats.isEmpty()) {
                        ensureSeedChat()
                    }
                }
        }
    }

    private fun observeSearch() {
        viewModelScope.launch {
            searchQuery
                .debounce(200)
                .distinctUntilChanged()
                .collectLatest { query ->
                    val trimmed = query.trim()
                    val results = if (trimmed.isEmpty()) {
                        emptyList()
                    } else {
                        searchService.search(trimmed, 50)
                            .map { result ->
                                when (result.type) {
                                    com.peerchat.app.engine.SearchService.SearchResultType.MESSAGE ->
                                        "Msg:#${result.chatId ?: 0}: ${result.content}"
                                    com.peerchat.app.engine.SearchService.SearchResultType.DOCUMENT_CHUNK ->
                                        "Doc: ${result.content}"
                                }
                            }
                    }
                    _uiState.update { it.copy(searchQuery = query, searchResults = results) }
                }
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            activeChatId
                .flatMapLatest { id ->
                    if (id == null) kotlinx.coroutines.flow.flowOf(emptyList())
                    else repository.database().messageDao().observeByChat(id)
                }
                .collectLatest { msgs ->
                    _uiState.update { it.copy(messages = msgs) }
                }
        }
    }

    private fun loadChatSettings(chatId: Long) {
        viewModelScope.launch {
            val chat = repository.getChat(chatId) ?: return@launch
            val settings = runCatching { JSONObject(chat.settingsJson) }.getOrNull()
            val templateId = settings?.optStringOrNull("templateId")
                ?.takeIf { candidate -> templateOptions.any { it.id == candidate } }
            val temperature = settings?.optFloatOrNull("temperature")
            val topP = settings?.optFloatOrNull("topP")
            val topK = settings?.optIntOrNull("topK")
            val maxTokens = settings?.optIntOrNull("maxTokens")
            _uiState.update { state ->
                state.copy(
                    sysPrompt = chat.systemPrompt,
                    temperature = temperature ?: state.temperature,
                    topP = topP ?: state.topP,
                    topK = topK ?: state.topK,
                    maxTokens = maxTokens ?: state.maxTokens,
                    selectedTemplateId = templateId
                )
            }
        }
    }

    private fun observeEngine() {
        viewModelScope.launch {
            EngineRuntime.status.collect { status ->
                _uiState.update { it.copy(engineStatus = status) }
            }
        }
        viewModelScope.launch {
            EngineRuntime.metrics.collect { metrics ->
                _uiState.update { it.copy(engineMetrics = metrics) }
            }
        }
        viewModelScope.launch {
            EngineRuntime.modelMeta.collect { meta ->
                _uiState.update { it.copy(modelMeta = meta) }
            }
        }
    }

    private fun observeManifests() {
        viewModelScope.launch {
            modelService.getManifestsFlow().collectLatest { manifests ->
                val state = _uiState.value
                val targetPath = state.storedConfig?.modelPath
                    ?: state.modelPath.takeIf { it.isNotBlank() }
                val activeManifest = targetPath?.let { path ->
                    manifests.firstOrNull { it.filePath == path }
                }
                val detectedTemplate = activeManifest?.let { modelService.getDetectedTemplateId(it) }
                _uiState.update {
                    it.copy(
                        manifests = manifests,
                        detectedTemplateId = detectedTemplate
                    )
                }
            }
        }
    }

    private fun restoreModelConfig() {
        val config = ModelConfigStore.load(appContext)
        if (config != null) {
            _uiState.update {
                it.copy(
                    storedConfig = config,
                    modelPath = config.modelPath,
                    threadText = config.threads.toString(),
                    contextText = config.contextLength.toString(),
                    gpuText = config.gpuLayers.toString(),
                    useVulkan = config.useVulkan
                )
            }
            viewModelScope.launch {
                loadModelInternal(config, showToast = false)
            }
        }
    }

    private fun ensureSeedChat() {
        viewModelScope.launch {
            val id = repository.createChat(
                title = "New Chat",
                folderId = selectedFolderId.value,
                systemPrompt = _uiState.value.sysPrompt,
                modelId = _uiState.value.storedConfig?.modelPath ?: "default"
            )
            activeChatId.value = id
            _uiState.update { it.copy(activeChatId = id) }
            loadChatSettings(id)
        }
    }

    fun selectFolder(folderId: Long?) {
        selectedFolderId.value = folderId
        _uiState.update { it.copy(selectedFolderId = folderId) }
    }

    fun selectChat(chatId: Long) {
        activeChatId.value = chatId
        _uiState.update { it.copy(activeChatId = chatId) }
        loadChatSettings(chatId)
        viewModelScope.launch { _events.emit(HomeEvent.Toast("Opened chat #$chatId")) }
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun updateSysPrompt(value: String) {
        _uiState.update { it.copy(sysPrompt = value) }
        persistChatSettings()
    }

    fun updateTemperature(value: Float) {
        _uiState.update { it.copy(temperature = value) }
        persistChatSettings()
    }

    fun updateTopP(value: Float) {
        _uiState.update { it.copy(topP = value) }
        persistChatSettings()
    }

    fun updateTopK(value: Int) {
        _uiState.update { it.copy(topK = value) }
        persistChatSettings()
    }

    fun updateMaxTokens(value: Int) {
        _uiState.update { it.copy(maxTokens = value) }
        persistChatSettings()
    }

    fun updateTemplate(templateId: String?) {
        val normalized = templateId
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { candidate -> templateOptions.any { it.id == candidate } }
        _uiState.update { it.copy(selectedTemplateId = normalized) }
        persistChatSettings()
    }

    private fun persistChatSettings() {
        val chatId = activeChatId.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dao = repository.database().chatDao()
                val base = dao.getById(chatId) ?: return@withContext
                val state = _uiState.value
                val settings = JSONObject().apply {
                    put("temperature", state.temperature)
                    put("topP", state.topP)
                    put("topK", state.topK)
                    put("maxTokens", state.maxTokens)
                    state.selectedTemplateId?.let { put("templateId", it) }
                }.toString()
                dao.upsert(
                    base.copy(
                        systemPrompt = state.sysPrompt,
                        settingsJson = settings,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim().ifEmpty { "Folder" }
            repository.createFolder(trimmed)
        }
    }

    fun createChat(name: String) {
        viewModelScope.launch {
            val result = chatService.createChat(
                title = name,
                folderId = selectedFolderId.value,
                systemPrompt = _uiState.value.sysPrompt,
                modelId = _uiState.value.storedConfig?.modelPath ?: "default"
            )
            if (result.success && result.chatId != null) {
                activeChatId.value = result.chatId
                _uiState.update { it.copy(activeChatId = result.chatId) }
                loadChatSettings(result.chatId)
            }
            _events.emit(HomeEvent.Toast(result.message))
        }
    }

    fun renameChat(chatId: Long, title: String) {
        viewModelScope.launch {
            val result = chatService.renameChat(chatId, title)
            _events.emit(HomeEvent.Toast(result.message))
        }
    }

    fun moveChat(chatId: Long, folderId: Long?) {
        viewModelScope.launch {
            val result = chatService.moveChat(chatId, folderId)
            _events.emit(HomeEvent.Toast(result.message))
        }
    }

    fun forkChat(chatId: Long) {
        viewModelScope.launch {
            val result = chatService.forkChat(chatId)
            if (result.success && result.chatId != null) {
                activeChatId.value = result.chatId
                _uiState.update { it.copy(activeChatId = result.chatId) }
                loadChatSettings(result.chatId)
            }
            _events.emit(HomeEvent.Toast(result.message))
        }
    }

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            val result = chatService.deleteChat(chatId)
            _events.emit(HomeEvent.Toast(result.message))
        }
    }

    fun deleteFolder(folderId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteFolder(folderId) }
                .onSuccess { _events.emit(HomeEvent.Toast("Folder deleted")) }
                .onFailure { _events.emit(HomeEvent.Toast("Delete error: ${'$'}{it.message}")) }
        }
    }

    fun updateModelPath(path: String) {
        _uiState.update { it.copy(modelPath = path) }
    }

    fun updateThreadText(value: String) {
        _uiState.update { it.copy(threadText = value) }
    }

    fun updateContextText(value: String) {
        _uiState.update { it.copy(contextText = value) }
    }

    fun updateGpuText(value: String) {
        _uiState.update { it.copy(gpuText = value) }
    }

    fun updateUseVulkan(value: Boolean) {
        _uiState.update { it.copy(useVulkan = value) }
    }

    fun loadModelFromInputs() {
        if (_uiState.value.importingModel) return
        val state = _uiState.value
        val threads = state.threadText.toIntOrNull()
        val contextLength = state.contextText.toIntOrNull()
        val gpuLayers = state.gpuText.toIntOrNull()
        if (state.modelPath.isBlank() || threads == null || contextLength == null || gpuLayers == null) {
            viewModelScope.launch {
                _events.emit(HomeEvent.Toast("Invalid model configuration"))
            }
            return
        }
        val config = StoredEngineConfig(
            modelPath = state.modelPath,
            threads = threads,
            contextLength = contextLength,
            gpuLayers = gpuLayers,
            useVulkan = state.useVulkan
        )
        viewModelScope.launch {
            _uiState.update { it.copy(importingModel = true) }
            val result = modelService.loadModel(config)
            _uiState.update { it.copy(importingModel = false) }
            _events.emit(HomeEvent.Toast(result.message))
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(importingModel = true) }
            try {
                val message = modelService.unloadModel()
                _uiState.update {
                    it.copy(
                        storedConfig = null,
                        modelPath = "",
                        threadText = "6",
                        contextText = "4096",
                        gpuText = "20",
                        detectedTemplateId = null
                    )
                }
                _events.emit(HomeEvent.Toast(message))
            } finally {
                _uiState.update { it.copy(importingModel = false) }
            }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(importingModel = true) }
            try {
                val result = modelService.importModel(uri)
                if (result.success) {
                    // Update path immediately for visibility
                    result.manifest?.filePath?.let { path ->
                        _uiState.update { it.copy(modelPath = path) }
                    }
                    // Try to load the model
                    result.manifest?.let { manifest ->
                        val loadResult = modelService.activateManifest(manifest)
                        _events.emit(HomeEvent.Toast(loadResult.message))
                    } ?: _events.emit(HomeEvent.Toast(result.message))
                } else {
                    _events.emit(HomeEvent.Toast(result.message))
                }
            } finally {
                _uiState.update { it.copy(importingModel = false) }
            }
        }
    }

    fun deleteManifest(manifest: ModelManifest) {
        viewModelScope.launch {
            val message = modelService.deleteManifest(manifest, removeFile = true)
            if (_uiState.value.storedConfig?.modelPath == manifest.filePath) {
                _uiState.update { it.copy(storedConfig = null, modelPath = "") }
            }
            _events.emit(HomeEvent.Toast(message))
        }
    }

    fun activateManifest(manifest: ModelManifest) {
        viewModelScope.launch {
            val result = modelService.activateManifest(manifest)
            if (result.success) {
                _uiState.update {
                    it.copy(
                        modelPath = manifest.filePath,
                        contextText = manifest.contextLength.takeIf { len -> len > 0 }?.toString() ?: it.contextText,
                        detectedTemplateId = modelService.getDetectedTemplateId(manifest)
                    )
                }
            }
            _events.emit(HomeEvent.Toast(result.message))
        }
    }

    fun verifyManifest(manifest: ModelManifest) {
        viewModelScope.launch {
            val verified = modelService.verifyManifest(manifest)
            _events.emit(HomeEvent.Toast(if (verified) "Checksum verified" else "File missing"))
        }
    }

    fun importDocument(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(indexing = true) }
            try {
                val result = documentService.importDocument(uri)
                _events.emit(HomeEvent.Toast(result.message))
            } finally {
                _uiState.update { it.copy(indexing = false) }
            }
        }
    }

    fun sendPrompt(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String, EngineMetrics) -> Unit
    ) {
        val chatId = activeChatId.value ?: return
        viewModelScope.launch {
            val restored = modelCache.restore(chatId)
            if (!restored) {
                EngineRuntime.clearState(false)
            }
            val state = _uiState.value
            val history = repository.listMessages(chatId)
            repository.insertMessage(
                com.peerchat.data.db.Message(
                    chatId = chatId,
                    role = "user",
                    contentMarkdown = prompt,
                    tokens = 0,
                    ttfsMs = 0,
                    tps = 0f,
                    contextUsedPct = 0f,
                    createdAt = System.currentTimeMillis(),
                    metaJson = "{}"
                )
            )

            val retrieved = RagService.retrieveHybrid(repository.database(), prompt, topK = 6)
            val ctx = RagService.buildContext(retrieved)
            val augmented = if (ctx.isNotBlank()) "$ctx\n\n$prompt" else prompt
            val composition = PromptComposer.compose(
                PromptComposer.Inputs(
                    systemPrompt = state.sysPrompt.takeIf { it.isNotBlank() },
                    history = history,
                    nextUserContent = augmented,
                    selectedTemplateId = state.selectedTemplateId,
                    detectedTemplateId = state.detectedTemplateId
                )
            )

            val builder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            var inReasoningRegion = false
            var success = false
            StreamingEngine.stream(
                prompt = composition.prompt.text,
                systemPrompt = null,
                template = composition.template.id,
                temperature = state.temperature,
                topP = state.topP,
                topK = state.topK,
                maxTokens = state.maxTokens,
                stop = composition.prompt.stopSequences.toTypedArray()
            ).collect { event ->
                when (event) {
                    is EngineStreamEvent.Token -> {
                        val t = event.text
                        // Detect reasoning regions
                        if (!inReasoningRegion && (t.contains("<think>") || t.contains("<reasoning>") || t.contains("<|startofthink|>"))) {
                            inReasoningRegion = true
                        }
                        if (inReasoningRegion) {
                            reasoningBuilder.append(t)
                            if (t.contains("</think>") || t.contains("</reasoning>") || t.contains("<|endofthink|>")) {
                                inReasoningRegion = false
                            }
                        } else {
                            builder.append(t)
                            onToken(t)
                        }
                    }
                    is EngineStreamEvent.Terminal -> {
                        success = !event.metrics.isError
                        val finalText = builder.toString()
                        val reasoningText = reasoningBuilder.toString()
                        onComplete(finalText, event.metrics)
                        val meta = runCatching { JSONObject(event.metrics.rawJson) }.getOrElse { JSONObject() }
                        meta.put("templateId", composition.template.id)
                        if (reasoningText.isNotBlank()) {
                            meta.put("reasoning", reasoningText)
                        }
                        repository.insertMessage(
                            com.peerchat.data.db.Message(
                                chatId = chatId,
                                role = "assistant",
                                contentMarkdown = finalText,
                                tokens = 0,
                                ttfsMs = event.metrics.ttfsMs.toLong(),
                                tps = event.metrics.tps.toFloat(),
                                contextUsedPct = (event.metrics.contextUsedPct / 100.0).toFloat().coerceIn(0f, 1f),
                                createdAt = System.currentTimeMillis(),
                                metaJson = meta.toString()
                            )
                        )
                        if (success) {
                            modelCache.capture(chatId)
                        } else {
                            modelCache.clear(chatId)
                        }
                    }
                }
            }
        }
    }

}
