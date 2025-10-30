package com.peerchat.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.OperationResult
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.engine.DocumentService
import com.peerchat.app.engine.EngineStreamEvent
import com.peerchat.app.engine.ModelConfigStore
import com.peerchat.app.engine.ModelRepository
import com.peerchat.app.engine.ModelService
import com.peerchat.app.engine.ModelStateCache
import com.peerchat.app.engine.PromptComposer
import com.peerchat.app.engine.StoredEngineConfig
import com.peerchat.app.engine.SearchService
import com.peerchat.app.ui.DialogState
import com.peerchat.app.ui.HomeEvent
import com.peerchat.app.ui.HomeUiState
import com.peerchat.app.ui.stream.ReasoningParser
import com.peerchat.app.util.optFloatOrNull
import com.peerchat.app.util.optIntOrNull
import com.peerchat.app.util.optStringOrNull
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import com.peerchat.templates.TemplateCatalog
import com.peerchat.rag.Retriever
import com.peerchat.rag.RagService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: PeerChatRepository,
    private val modelService: ModelService,
    private val modelRepository: ModelRepository,
    private val documentService: DocumentService,
    private val searchService: SearchService,
    private val modelCache: ModelStateCache,
    private val promptComposer: PromptComposer,
    private val retriever: Retriever
) : ViewModel() {

    // Memoization cache for expensive operations
    private val memoizationCache = mutableMapOf<String, Pair<Long, Any>>()
    private val MEMOIZATION_TTL: Long = 30000L // 30 seconds

    // Memoization helper
    private inline fun <T> memoize(key: String, block: () -> T): T {
        val currentTime = System.currentTimeMillis()
        val cached = memoizationCache[key]

        if (cached != null && (currentTime - cached.first) < MEMOIZATION_TTL) {
            @Suppress("UNCHECKED_CAST")
            return cached.second as T
        }

        val result = block()
        memoizationCache[key] = Pair(currentTime, result as Any)

        // Clean up old entries periodically
        if (memoizationCache.size > 100) {
            val cutoff = currentTime - MEMOIZATION_TTL
            memoizationCache.entries.removeIf { it.value.first < cutoff }
        }

        return result
    }

    private val templateOptions = memoize("template_options") {
        TemplateCatalog.descriptors().map {
            TemplateOption(
                id = it.id,
                label = it.displayName,
                stopSequences = it.stopSequences
            )
        }
    }

    private val _uiState = MutableStateFlow(HomeUiState(templates = templateOptions))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>()
    val events = _events.asSharedFlow()

    private val selectedFolderId = MutableStateFlow<Long?>(null)
    private val activeChatId = MutableStateFlow<Long?>(null)
    private val searchQuery = MutableStateFlow("")
    private var searchJob: kotlinx.coroutines.Job? = null

    // Race condition prevention - only one send operation at a time per chat
    private val activeSendJobs = mutableMapOf<Long, kotlinx.coroutines.Job>()

    init {
        observeFolders()
        observeChats()
        observeSearch()
        observeEngine()
        observeManifests()
        observeMessages()
        observeCacheStats()
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
                .debounce(300) // Increased debounce for better performance
                .distinctUntilChanged()
                .collectLatest { query ->
                    // Cancel any previous search
                    searchJob?.cancel()

                    val trimmed = query.trim()
                    if (trimmed.isEmpty()) {
                        _uiState.update { it.copy(searchQuery = query, searchResults = emptyList()) }
                    } else {
                        // Start new search job
                        searchJob = launch {
                            val results = performSearch(trimmed)
                            _uiState.update { it.copy(searchQuery = query, searchResults = results) }
                        }
                    }
                }
        }
    }

    private suspend fun performSearch(query: String): List<String> {
        return try {
            searchService.search(query, 50)
                .map { result ->
                    when (result.type) {
                        SearchService.SearchResultType.MESSAGE ->
                            "Msg:#${result.chatId ?: 0}: ${result.content.take(100)}${if (result.content.length > 100) "..." else ""}"
                        SearchService.SearchResultType.DOCUMENT_CHUNK ->
                            "Doc: ${result.content.take(100)}${if (result.content.length > 100) "..." else ""}"
                    }
                }
        } catch (e: Exception) {
            listOf("Search error: ${e.message}")
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            activeChatId
                .flatMapLatest { id ->
                    if (id == null) {
                        kotlinx.coroutines.flow.flowOf(emptyList())
                    } else {
                        // Lazy load messages - only load last 50 for performance
                        kotlinx.coroutines.flow.flow {
                            val messages = repository.listMessages(id)
                            // Sort by creation time and take last 50
                            val recentMessages = messages.sortedBy { it.createdAt }.takeLast(50)
                            emit(recentMessages)
                        }
                    }
                }
                .collectLatest { msgs ->
                    _uiState.update { it.copy(messages = msgs) }
                }
        }
    }

    private fun observeCacheStats() {
        viewModelScope.launch {
            modelCache.stats().collectLatest { stats ->
                _uiState.update { it.copy(cacheStats = stats) }
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
                val result = modelService.loadModel(config)
                // Handle result if needed
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
            val result = repository.createChatResult(
                title = name,
                folderId = selectedFolderId.value,
                systemPrompt = _uiState.value.sysPrompt,
                modelId = _uiState.value.storedConfig?.modelPath ?: "default"
            )
            when (result) {
                is OperationResult.Success -> {
                    activeChatId.value = result.data
                    _uiState.update { it.copy(activeChatId = result.data) }
                    loadChatSettings(result.data)
                    _events.emit(HomeEvent.Toast(result.message))
                }
                is OperationResult.Failure -> {
                    _events.emit(HomeEvent.Toast(result.error))
                }
            }
        }
    }

    fun renameChat(chatId: Long, title: String) {
        viewModelScope.launch {
            val result = repository.renameChatResult(chatId, title)
            when (result) {
                is OperationResult.Success -> _events.emit(HomeEvent.Toast(result.message))
                is OperationResult.Failure -> _events.emit(HomeEvent.Toast(result.error))
            }
        }
    }

    fun moveChat(chatId: Long, folderId: Long?) {
        viewModelScope.launch {
            val result = repository.moveChatResult(chatId, folderId)
            when (result) {
                is OperationResult.Success -> _events.emit(HomeEvent.Toast(result.message))
                is OperationResult.Failure -> _events.emit(HomeEvent.Toast(result.error))
            }
        }
    }

    fun forkChat(chatId: Long) {
        viewModelScope.launch {
            val result = repository.forkChatResult(chatId)
            when (result) {
                is OperationResult.Success -> {
                    activeChatId.value = result.data
                    _uiState.update { it.copy(activeChatId = result.data) }
                    loadChatSettings(result.data)
                    _events.emit(HomeEvent.Toast(result.message))
                }
                is OperationResult.Failure -> {
                    _events.emit(HomeEvent.Toast(result.error))
                }
            }
        }
    }

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            val result = repository.deleteChatResult(chatId)
            when (result) {
                is OperationResult.Success -> _events.emit(HomeEvent.Toast(result.message))
                is OperationResult.Failure -> _events.emit(HomeEvent.Toast(result.error))
            }
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
            when (result) {
                is OperationResult.Success -> _events.emit(HomeEvent.Toast(result.message))
                is OperationResult.Failure -> _events.emit(HomeEvent.Toast(result.error))
            }
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
                when (result) {
                    is OperationResult.Success -> {
                        // Update path immediately for visibility
                        result.data.filePath.let { path ->
                            _uiState.update { it.copy(modelPath = path) }
                        }
                        // Try to load the model
                        val loadResult = modelService.activateManifest(result.data)
                        when (loadResult) {
                            is OperationResult.Success -> _events.emit(HomeEvent.Toast(loadResult.message))
                            is OperationResult.Failure -> _events.emit(HomeEvent.Toast(loadResult.error))
                        }
                    }
                    is OperationResult.Failure -> {
                        _events.emit(HomeEvent.Toast(result.error))
                    }
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
            when (result) {
                is OperationResult.Success -> {
                    _uiState.update {
                        it.copy(
                            modelPath = manifest.filePath,
                            contextText = manifest.contextLength.takeIf { len -> len > 0 }?.toString() ?: it.contextText,
                            detectedTemplateId = modelService.getDetectedTemplateId(manifest)
                        )
                    }
                    _events.emit(HomeEvent.Toast(result.message))
                }
                is OperationResult.Failure -> {
                    _events.emit(HomeEvent.Toast(result.error))
                }
            }
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
            val result = documentService.importDocument(uri)
            when (result) {
                is OperationResult.Success -> _events.emit(HomeEvent.Toast(result.message))
                is OperationResult.Failure -> _events.emit(HomeEvent.Toast(result.error))
            }
            _uiState.update { it.copy(indexing = false) }
        }
    }

    fun sendPrompt(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String, EngineMetrics) -> Unit
    ) {
        val chatId = activeChatId.value ?: return

        // Prevent race conditions - only one send operation per chat at a time
        val existingJob = activeSendJobs[chatId]
        if (existingJob?.isActive == true) {
            // Silently ignore - user will see the existing operation complete
            return
        }

        val job = viewModelScope.launch {
            activeSendJobs[chatId] = this as kotlinx.coroutines.Job
            try {
                // KV state restore handled by repository during streaming

                val state = _uiState.value
                val history = runCatching { repository.listMessages(chatId) }.getOrDefault(emptyList())

                // Insert user message with error recovery
                runCatching {
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
                }.onFailure { e ->
                    _events.emit(HomeEvent.Toast("Failed to save message: ${e.message}"))
                    return@launch
                }

                // RAG retrieval with error recovery
                val retrieved = runCatching {
                    retriever.retrieve(repository.database(), prompt, topK = 6)
                }.getOrDefault(emptyList())

                val ctx = runCatching { RagService.buildContext(retrieved) }.getOrDefault("")
                val augmented = if (ctx.isNotBlank()) "$ctx\n\n$prompt" else prompt

                val composition = memoize("prompt_composition_${chatId}_${state.sysPrompt.hashCode()}_${history.size}_${augmented.hashCode()}") {
                    runCatching {
                        promptComposer.compose(
                            PromptComposer.Inputs(
                                systemPrompt = state.sysPrompt.takeIf { it.isNotBlank() },
                                history = history,
                                nextUserContent = augmented,
                                selectedTemplateId = state.selectedTemplateId,
                                detectedTemplateId = state.detectedTemplateId
                            )
                        )
                    }.getOrNull()
                }

                if (composition == null) {
                    _events.emit(HomeEvent.Toast("Failed to compose prompt"))
                    return@launch
                }

                val parser = ReasoningParser(onToken)

                runCatching {
                    modelRepository.streamWithCache(
                        chatId = chatId,
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
                            is EngineStreamEvent.Token -> parser.handle(event.text)
                            is EngineStreamEvent.Terminal -> {
                                val parseResult = parser.result()
                                val finalText = parseResult.visible
                                val reasoningText = parseResult.reasoning
                                onComplete(finalText, event.metrics)

                                runCatching {
                                    repository.insertMessage(
                                        com.peerchat.data.db.Message(
                                            chatId = chatId,
                                            role = "assistant",
                                            contentMarkdown = finalText,
                                            tokens = event.metrics.generationTokens,
                                            ttfsMs = event.metrics.ttfsMs.toLong(),
                                            tps = event.metrics.tps.toFloat(),
                                            contextUsedPct = (event.metrics.contextUsedPct / 100.0).toFloat().coerceIn(0f, 1f),
                                            createdAt = System.currentTimeMillis(),
                                            metaJson = buildAssistantMeta(
                                                templateId = composition.template.id,
                                                metrics = event.metrics,
                                                reasoning = reasoningText.takeIf { it.isNotBlank() },
                                                reasoningDurationMs = parseResult.reasoningDurationMs,
                                                reasoningChars = parseResult.reasoningChars
                                            )
                                        )
                                    )
                                }.onFailure { e ->
                                    _events.emit(HomeEvent.Toast("Failed to save response: ${e.message}"))
                                }
                            }
                        }
                    }
                }.onFailure { e ->
                    _events.emit(HomeEvent.Toast("Generation failed: ${e.message}"))
                    // Clear cache on error
                    runCatching { modelCache.clear(chatId) }
                }

            } catch (e: Exception) {
                _events.emit(HomeEvent.Toast("Unexpected error: ${e.message}"))
                // Clear cache on critical error
                runCatching { modelCache.clear(chatId) }
            } finally {
                // Clean up active job reference
                activeSendJobs.remove(chatId)
            }
        }

        // Store the job reference (outside the launch block)
        activeSendJobs[chatId] = job
    }

    private fun buildAssistantMeta(
        templateId: String,
        metrics: EngineMetrics,
        reasoning: String?,
        reasoningDurationMs: Long?,
        reasoningChars: Int
    ): String {
        val metricsJson = JSONObject().apply {
            put("promptTokens", metrics.promptTokens)
            put("generationTokens", metrics.generationTokens)
            put("ttfsMs", metrics.ttfsMs)
            put("tps", metrics.tps)
            put("contextUsedPct", metrics.contextUsedPct)
            put("stopReason", metrics.stopReason)
            put("stopSequence", metrics.stopSequence)
            put("truncated", metrics.truncated)
            put("reasoningChars", reasoningChars)
            reasoningDurationMs?.let { put("reasoningDurationMs", it) }
        }
        return JSONObject().apply {
            put("templateId", templateId)
            put("metrics", metricsJson)
            reasoning?.let { put("reasoning", it) }
        }.toString()
    }

    fun showNewChatDialog() {
        _uiState.update { it.copy(dialogState = DialogState.NewChat) }
    }

    fun showSettingsDialog() {
        _uiState.update { it.copy(dialogState = DialogState.Settings) }
    }

    fun showNewFolderDialog() {
        _uiState.update { it.copy(dialogState = DialogState.NewFolder) }
    }

    fun showRenameChatDialog(chatId: Long, currentTitle: String) {
        _uiState.update { it.copy(dialogState = DialogState.RenameChat(chatId, currentTitle)) }
    }

    fun showMoveChatDialog(chatId: Long) {
        _uiState.update { it.copy(dialogState = DialogState.MoveChat(chatId)) }
    }

    fun showForkChatDialog(chatId: Long) {
        _uiState.update { it.copy(dialogState = DialogState.ForkChat(chatId)) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(dialogState = DialogState.None) }
    }

    // Memory leak prevention - cleanup when ViewModel is destroyed
    override fun onCleared() {
        super.onCleared()
        // Cancel all active send jobs
        activeSendJobs.values.forEach { it.cancel() }
        activeSendJobs.clear()
        // Cancel search job if active
        searchJob?.cancel()
        // Clear memoization cache
        memoizationCache.clear()
    }
}
