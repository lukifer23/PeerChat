package com.peerchat.app.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.engine.EngineStreamEvent
import com.peerchat.app.engine.ModelConfigStore
import com.peerchat.app.engine.ModelManifestService
import com.peerchat.app.engine.ModelStateCache
import com.peerchat.app.engine.ModelStorage
import com.peerchat.app.engine.StreamingEngine
import com.peerchat.app.engine.StoredEngineConfig
import com.peerchat.data.db.Document
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
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
    private val repository = PeerChatRepository.from(appContext)
    private val manifestService = ModelManifestService(appContext)
    private val modelCache = ModelStateCache(appContext)

    private val _uiState = MutableStateFlow(HomeUiState())
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
                    activeChatId.value = resolvedActive
                    _uiState.update {
                        it.copy(
                            chats = chats,
                            activeChatId = resolvedActive,
                            selectedFolderId = selectedFolderId.value
                        )
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
                        val messages = repository.searchMessages(trimmed, 20)
                            .map { "Msg: " + it.contentMarkdown }
                        val chunks = repository.searchChunks(trimmed, 20)
                            .map { "Doc: " + it.text }
                        (messages + chunks).take(50)
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
            manifestService.manifestsFlow().collectLatest { manifests ->
                _uiState.update { it.copy(manifests = manifests) }
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
        }
    }

    fun selectFolder(folderId: Long?) {
        selectedFolderId.value = folderId
        _uiState.update { it.copy(selectedFolderId = folderId) }
    }

    fun selectChat(chatId: Long, navController: NavHostController) {
        activeChatId.value = chatId
        _uiState.update { it.copy(activeChatId = chatId) }
        navController.navigate("chat/$chatId")
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

    private fun persistChatSettings() {
        val chatId = activeChatId.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dao = repository.database().chatDao()
                val base = dao.getById(chatId) ?: return@withContext
                val settings = JSONObject().apply {
                    put("temperature", _uiState.value.temperature)
                    put("topP", _uiState.value.topP)
                    put("topK", _uiState.value.topK)
                    put("maxTokens", _uiState.value.maxTokens)
                }.toString()
                dao.upsert(
                    base.copy(
                        systemPrompt = _uiState.value.sysPrompt,
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
            val trimmed = name.trim().ifEmpty { "New Chat" }
            val id = repository.createChat(
                title = trimmed,
                folderId = selectedFolderId.value,
                systemPrompt = _uiState.value.sysPrompt,
                modelId = _uiState.value.storedConfig?.modelPath ?: "default"
            )
            activeChatId.value = id
            _uiState.update { it.copy(activeChatId = id) }
        }
    }

    fun renameChat(chatId: Long, title: String) {
        viewModelScope.launch {
            repository.renameChat(chatId, title.trim().ifEmpty { "Untitled" })
        }
    }

    fun moveChat(chatId: Long, folderId: Long?) {
        viewModelScope.launch {
            repository.moveChat(chatId, folderId)
        }
    }

    fun forkChat(chatId: Long) {
        viewModelScope.launch {
            val base = repository.getChat(chatId) ?: return@launch
            val newChatId = repository.createChat(
                title = base.title + " (copy)",
                folderId = base.folderId,
                systemPrompt = base.systemPrompt,
                modelId = base.modelId
            )
            val messages = repository.listMessages(chatId)
            withContext(Dispatchers.IO) {
                messages.forEach { message ->
                    repository.insertMessage(
                        message.copy(
                            id = 0,
                            chatId = newChatId,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            activeChatId.value = newChatId
            _uiState.update { it.copy(activeChatId = newChatId) }
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
            loadModelInternal(config, showToast = true)
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(importingModel = true) }
            try {
                EngineRuntime.unload()
                EngineRuntime.clearState(true)
                modelCache.clearAll()
                ModelConfigStore.clear(appContext)
                _uiState.update {
                    it.copy(
                        storedConfig = null,
                        modelPath = "",
                        threadText = "6",
                        contextText = "4096",
                        gpuText = "20"
                    )
                }
                _events.emit(HomeEvent.Toast("Model unloaded"))
            } finally {
                _uiState.update { it.copy(importingModel = false) }
            }
        }
    }

    private suspend fun loadModelInternal(config: StoredEngineConfig, showToast: Boolean) {
        _uiState.update { it.copy(importingModel = true) }
        try {
            EngineRuntime.unload()
            EngineRuntime.clearState(true)
            modelCache.clearAll()
            val loaded = EngineRuntime.load(config.toEngineConfig())
            if (loaded) {
                ModelConfigStore.save(appContext, config)
                manifestService.ensureManifestFor(config.modelPath, EngineRuntime.currentModelMeta())
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
                if (showToast) _events.emit(HomeEvent.Toast("Model loaded"))
            } else {
                ModelConfigStore.clear(appContext)
                _uiState.update { it.copy(storedConfig = null) }
                _events.emit(HomeEvent.Toast("Failed to load model"))
            }
        } finally {
            _uiState.update { it.copy(importingModel = false) }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(importingModel = true) }
            try {
                val path = ModelStorage.importModel(appContext, uri)
                if (!path.isNullOrEmpty()) {
                    _uiState.update { it.copy(modelPath = path) }
                    manifestService.ensureManifestFor(path)
                    _events.emit(HomeEvent.Toast("Model imported"))
                } else {
                    _events.emit(HomeEvent.Toast("Import failed"))
                }
            } finally {
                _uiState.update { it.copy(importingModel = false) }
            }
        }
    }

    fun deleteManifest(manifest: ModelManifest) {
        viewModelScope.launch {
            manifestService.deleteManifest(manifest, removeFile = true)
            if (_uiState.value.storedConfig?.modelPath == manifest.filePath) {
                ModelConfigStore.clear(appContext)
                _uiState.update { it.copy(storedConfig = null, modelPath = "") }
            }
        }
    }

    fun activateManifest(manifest: ModelManifest) {
        _uiState.update {
            it.copy(
                modelPath = manifest.filePath,
                contextText = manifest.contextLength.takeIf { len -> len > 0 }?.toString() ?: it.contextText
            )
        }
    }

    fun importDocument(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(indexing = true) }
            try {
                val resolver = appContext.contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val name = resolveDisplayName(resolver, uri) ?: "Document"
                val text = when {
                    mime == "application/pdf" -> extractPdfText(resolver, uri)
                    mime.startsWith("text/") -> resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    mime.startsWith("image/") -> ""
                    else -> ""
                }
                val document = Document(
                    uri = uri.toString(),
                    title = name,
                    hash = sha256(text),
                    mime = mime,
                    textBytes = text.toByteArray(),
                    createdAt = System.currentTimeMillis(),
                    metaJson = "{}"
                )
                val id = repository.upsertDocument(document)
                RagService.indexDocument(
                    repository.database(),
                    document.copy(id = id),
                    text
                )
                _events.emit(HomeEvent.Toast("Document indexed"))
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

            val retrieved = RagService.retrieve(repository.database(), prompt, topK = 6)
            val ctx = RagService.buildContext(retrieved)
            val augmented = if (ctx.isNotBlank()) "$ctx\n\n$prompt" else prompt

            val builder = StringBuilder()
            var success = false
            StreamingEngine.stream(
                prompt = augmented,
                systemPrompt = _uiState.value.sysPrompt,
                template = null,
                temperature = _uiState.value.temperature,
                topP = _uiState.value.topP,
                topK = _uiState.value.topK,
                maxTokens = _uiState.value.maxTokens,
                stop = emptyArray()
            ).collect { event ->
                when (event) {
                    is EngineStreamEvent.Token -> {
                        builder.append(event.text)
                        onToken(event.text)
                    }
                    is EngineStreamEvent.Terminal -> {
                        success = !event.metrics.isError
                        val finalText = builder.toString()
                        onComplete(finalText, event.metrics)
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
                                metaJson = event.metrics.rawJson
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

    private fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index)
                }
            }
        }
        return null
    }

    private fun extractPdfText(resolver: ContentResolver, uri: Uri): String {
        resolver.openInputStream(uri).use { input ->
            if (input != null) {
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
                    val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                    return stripper.getText(doc) ?: ""
                }
            }
        }
        return ""
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(value.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
