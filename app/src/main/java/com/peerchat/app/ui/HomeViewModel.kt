package com.peerchat.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.OperationResult
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.engine.DocumentService
import com.peerchat.app.engine.ModelConfigStore
import com.peerchat.app.engine.ModelRepository
import com.peerchat.app.engine.ModelService
import com.peerchat.app.engine.ModelStateCache
import com.peerchat.app.engine.SearchService
import com.peerchat.app.engine.StoredEngineConfig
import com.peerchat.app.ui.HomeEvent.Toast
import com.peerchat.app.ui.DocumentState
import com.peerchat.app.ui.HomeUiState
import com.peerchat.app.ui.ModelState
import com.peerchat.app.ui.SearchResultItem
import com.peerchat.app.ui.SearchResultItem.ResultType
import com.peerchat.data.db.Chat
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: PeerChatRepository,
    private val searchService: SearchService,
    private val modelRepository: ModelRepository,
    private val modelService: ModelService,
    private val modelCache: ModelStateCache,
    private val documentService: DocumentService,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>()
    val events = _events.asSharedFlow()

    private val _searchQuery = MutableStateFlow("")
    private val selectedFolderId = MutableStateFlow<Long?>(null)

    override fun emitToast(message: String, isError: Boolean) {
        _events.tryEmit(Toast(message, isError))
    }

    init {
        observeFolders()
        observeSearch()
        observeChats()
        observeEngine()
        observeManifests()
        observeCacheStats()
        restoreStoredConfig()
    }

    // ------------------------------------------------------------------------
    // Observers
    // ------------------------------------------------------------------------

    private fun observeFolders() {
        viewModelScope.launch {
            repository.observeFolders().collectLatest { folders ->
                val currentSelected = selectedFolderId.value
                val resolvedSelected = when {
                    folders.isEmpty() -> null
                    currentSelected != null && folders.any { it.id == currentSelected } -> currentSelected
                    else -> folders.first().id
                }
                if (selectedFolderId.value != resolvedSelected) {
                    selectedFolderId.value = resolvedSelected
                }
                _uiState.update {
                    it.copy(
                        navigation = it.navigation.copy(
                            folders = folders,
                            selectedFolderId = resolvedSelected
                        )
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeChats() {
        viewModelScope.launch {
            selectedFolderId
                .flatMapLatest { folderId -> repository.observeChats(folderId) }
                .collectLatest { chats ->
                    val previousActive = _uiState.value.navigation.activeChatId
                    val resolvedActive = resolveActiveChat(previousActive, chats)
                    _uiState.update { state ->
                        state.copy(
                            navigation = state.navigation.copy(
                                chats = chats,
                                activeChatId = resolvedActive
                            )
                        )
                    }
                    if (resolvedActive != null && resolvedActive != previousActive) {
                        _events.emit(HomeEvent.SelectChat(resolvedActive))
                    }
                }
        }
    }

    private fun resolveActiveChat(current: Long?, chats: List<Chat>): Long? {
        if (chats.isEmpty()) return null
        if (current != null && chats.any { it.id == current }) return current
        return chats.first().id
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { query ->
                    val trimmed = query.trim()
                    if (trimmed.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                search = it.search.copy(
                                    searchQuery = query,
                                    searchResults = emptyList()
                                )
                            )
                        }
                    } else {
                        val results = performSearch(trimmed)
                        _uiState.update {
                            it.copy(
                                search = it.search.copy(
                                    searchQuery = query,
                                    searchResults = results
                                )
                            )
                        }
                    }
                }
        }
    }

    private fun observeEngine() {
        viewModelScope.launch {
            EngineRuntime.status.collect { status ->
                updateModelState {
                    when (status) {
                        is EngineRuntime.EngineStatus.Error -> it.copy(
                            engineStatus = status,
                            isOfflineMode = isNetworkError(Exception(status.message)),
                            errorMessage = status.message
                        )
                        else -> it.copy(
                            engineStatus = status,
                            isOfflineMode = false,
                            errorMessage = null
                        )
                    }
                }

                // Handle offline mode transitions
                if (status is EngineRuntime.EngineStatus.Error && isNetworkError(Exception(status.message))) {
                    enableOfflineMode()
                } else if (status is EngineRuntime.EngineStatus.Loaded) {
                    disableOfflineMode()
                }
            }
        }
        viewModelScope.launch {
            EngineRuntime.metrics.collect { metrics ->
                updateModelState { it.copy(engineMetrics = metrics) }
            }
        }
        viewModelScope.launch {
            EngineRuntime.modelMeta.collect { meta ->
                updateModelState { it.copy(modelMeta = meta) }
            }
        }
    }

    private fun observeManifests() {
        viewModelScope.launch {
            modelService.getManifestsFlow().collectLatest { manifests ->
                val activePath = uiState.value.model.storedConfig?.modelPath
                    ?: uiState.value.model.modelPath

                val activeManifest = manifests.firstOrNull { it.filePath == activePath }

                val detectedTemplate = activeManifest?.let {
                    modelService.getDetectedTemplateId(it)
                }

                updateModelState {
                    it.copy(
                        manifests = manifests,
                        detectedTemplateId = detectedTemplate
                    )
                }
            }
        }
    }

    private fun observeCacheStats() {
        viewModelScope.launch {
            modelCache.stats().collectLatest { stats ->
                updateModelState { it.copy(cacheStats = stats) }
            }
        }
    }

    private fun restoreStoredConfig() {
        val stored = ModelConfigStore.load(appContext) ?: return
        updateModelState {
            it.copy(
                storedConfig = stored,
                modelPath = stored.modelPath,
                threadText = stored.threads.toString(),
                contextText = stored.contextLength.toString(),
                gpuText = stored.gpuLayers.toString(),
                useVulkan = stored.useVulkan
            )
        }
        viewModelScope.launch { loadModelInternal(stored) }
    }

    // ------------------------------------------------------------------------
    // Search / folders API
    // ------------------------------------------------------------------------

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private suspend fun performSearch(query: String): List<SearchResultItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                searchService.search(query, 50).map { result ->
                    when (result.type) {
                        SearchService.SearchResultType.MESSAGE -> {
                            SearchResultItem(
                                type = ResultType.MESSAGE,
                                title = result.title,
                                preview = result.content,
                                chatId = result.chatId,
                                messageId = result.messageId
                            )
                        }

                        SearchService.SearchResultType.DOCUMENT_CHUNK -> {
                            SearchResultItem(
                                type = ResultType.DOCUMENT_CHUNK,
                                title = result.title,
                                preview = result.content,
                                documentId = result.documentId,
                                chunkId = result.chunkId
                            )
                        }
                    }
                }
            }.getOrElse {
                listOf(
                    SearchResultItem(
                        type = ResultType.MESSAGE,
                        title = "Error",
                        preview = it.message ?: "Unknown error"
                    )
                )
            }
        }

    fun createFolder(name: String) {
        executeOperation(
            operation = {
                tryOperation(
                    operation = {
                        val trimmed = name.trim().ifEmpty { "Folder" }
                        repository.createFolder(trimmed)
                    },
                    successMessage = "Folder created",
                    errorPrefix = "Failed to create folder"
                )
            },
            onSuccess = {
                selectedFolderId.value = it
            }
        )
    }

    fun renameFolder(folderId: Long, name: String) {
        executeOperation(
            operation = { repository.renameFolderResult(folderId, name) }
        )
    }

    fun deleteFolder(folderId: Long) {
        executeOperation(
            operation = {
                tryOperation(
                    operation = {
                        repository.deleteFolder(folderId)
                    },
                    successMessage = "Folder deleted",
                    errorPrefix = "Failed to delete folder"
                )
            },
            onSuccess = {
                if (selectedFolderId.value == folderId) {
                    selectedFolderId.value = null
                }
            }
        )
    }

    // ------------------------------------------------------------------------
    // Dialog / navigation helpers
    // ------------------------------------------------------------------------

    fun showNewChatDialog() {
        _uiState.update { it.copy(dialogState = DialogState.NewChat) }
    }

    fun showSettingsDialog() {
        _uiState.update { it.copy(dialogState = DialogState.Settings) }
    }

    fun showNewFolderDialog() {
        _uiState.update { it.copy(dialogState = DialogState.NewFolder) }
    }

    fun showRenameFolderDialog(folderId: Long, currentName: String) {
        _uiState.update { it.copy(dialogState = DialogState.RenameFolder(folderId, currentName)) }
    }

    fun showDeleteFolderDialog(folderId: Long, folderName: String) {
        _uiState.update { it.copy(dialogState = DialogState.DeleteFolder(folderId, folderName)) }
    }

    fun showRenameChatDialog(chatId: Long, currentTitle: String) {
        _uiState.update { it.copy(dialogState = DialogState.RenameChat(chatId, currentTitle)) }
    }

    fun showDeleteChatDialog(chatId: Long, currentTitle: String) {
        _uiState.update { it.copy(dialogState = DialogState.DeleteChat(chatId, currentTitle)) }
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

    fun selectFolder(folderId: Long?) {
        selectedFolderId.value = folderId
        _uiState.update {
            it.copy(
                navigation = it.navigation.copy(
                    selectedFolderId = folderId,
                    chats = emptyList(),
                    activeChatId = null
                )
            )
        }
    }

    fun focusChat(chatId: Long, folderId: Long? = null) {
        viewModelScope.launch {
            val resolvedFolder: Long? = if (folderId != null) {
                folderId
            } else {
                val chat = withContext(Dispatchers.IO) { repository.getChat(chatId) }
                if (chat == null) {
                    emitToast("Chat not found", true)
                    return@launch
                }
                chat.folderId
            }
            selectedFolderId.value = resolvedFolder
            _uiState.update {
                it.copy(
                    navigation = it.navigation.copy(
                        selectedFolderId = resolvedFolder,
                        activeChatId = chatId
                    )
                )
            }
            _events.emit(HomeEvent.SelectChat(chatId))
        }
    }

    fun importDocument(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            updateDocumentState { it.copy(indexing = true) }
            val result = documentService.importDocument(uri)
            updateDocumentState { it.copy(indexing = false) }
            when (result) {
                is OperationResult.Success -> emitToast(result.message, false)
                is OperationResult.Failure -> emitToast(result.error, true)
            }
        }
    }

    fun importModel(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            updateModelState { it.copy(importingModel = true) }
            val result = modelRepository.importModel(uri)
            updateModelState { it.copy(importingModel = false) }
            when (result) {
                is OperationResult.Success -> {
                    val manifest = result.data
                    updateModelState { state ->
                        state.copy(modelPath = manifest.filePath)
                    }
                    emitToast(result.message, false)
                }
                is OperationResult.Failure -> emitToast(result.error, true)
            }
        }
    }

    // ------------------------------------------------------------------------
    // Model management actions
    // ------------------------------------------------------------------------

    fun updateModelPath(path: String) = updateModelState { it.copy(modelPath = path) }

    fun updateThreadText(value: String) = updateModelState { it.copy(threadText = value) }

    fun updateContextText(value: String) = updateModelState { it.copy(contextText = value) }

    fun updateGpuText(value: String) = updateModelState { it.copy(gpuText = value) }

    fun updateUseVulkan(useVulkan: Boolean) =
        updateModelState { it.copy(useVulkan = useVulkan) }

    fun loadModel() {
        val config = parseConfigFromState() ?: return
        viewModelScope.launch { loadModelInternal(config) }
    }

    fun unloadModel() {
        viewModelScope.launch {
            updateModelState { it.copy(importingModel = true) }
            val message = runCatching { modelRepository.unloadModel() }
                .onFailure { emitToast("Failed to unload model: ${it.message}", true) }
                .getOrElse { "" }
            updateModelState {
                it.copy(
                    importingModel = false,
                    storedConfig = null,
                    modelPath = "",
                    threadText = "6",
                    contextText = "4096",
                    gpuText = "20"
                )
            }
            if (message.isNotBlank()) emitToast(message, false)
        }
    }

    fun activateManifest(manifest: ModelManifest) {
        viewModelScope.launch {
            updateModelState { it.copy(importingModel = true, modelPath = manifest.filePath) }
            val result = runCatching { modelRepository.activateManifest(manifest) }
                .getOrElse {
                    updateModelState { state -> state.copy(importingModel = false) }
                    emitToast("Failed to activate model: ${it.message}", true)
                    return@launch
                }

            when (result) {
                is OperationResult.Success -> {
                    refreshStoredConfig()
                    emitToast("Model loaded: ${result.data.name}", false)
                }
                is OperationResult.Failure -> emitToast(result.error, true)
            }

            updateModelState { it.copy(importingModel = false) }
        }
    }

    fun deleteManifest(manifest: ModelManifest, removeFile: Boolean = false) {
        viewModelScope.launch {
            val message = runCatching {
                modelRepository.deleteManifest(manifest, removeFile)
            }.onFailure {
                emitToast("Delete failed: ${it.message}", true)
            }.getOrNull()

            if (!message.isNullOrBlank()) emitToast(message, false)

            val currentPath = uiState.value.model.modelPath
            if (manifest.filePath == currentPath) {
                updateModelState {
                    it.copy(
                        modelPath = "",
                        storedConfig = null
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    private suspend fun loadModelInternal(config: StoredEngineConfig) {
        updateModelState { it.copy(importingModel = true) }
        val result = modelRepository.loadModel(config)
        updateModelState { it.copy(importingModel = false) }

        when (result) {
            is OperationResult.Success -> {
                refreshStoredConfig()
                emitToast(result.message, false)
            }
            is OperationResult.Failure -> emitToast(result.error, true)
        }
    }

    private fun refreshStoredConfig() {
        val stored = ModelConfigStore.load(appContext)
        if (stored != null) {
            updateModelState {
                it.copy(
                    storedConfig = stored,
                    modelPath = stored.modelPath,
                    threadText = stored.threads.toString(),
                    contextText = stored.contextLength.toString(),
                    gpuText = stored.gpuLayers.toString(),
                    useVulkan = stored.useVulkan
                )
            }
        } else {
            updateModelState {
                it.copy(
                    storedConfig = null,
                    modelPath = "",
                    threadText = "6",
                    contextText = "4096",
                    gpuText = "20"
                )
            }
        }
    }

    private fun enableOfflineMode() {
        updateModelState { it.copy(isOfflineMode = true) }
        emitToast("Offline mode enabled - some features may be limited", true)
    }

    private fun disableOfflineMode() {
        updateModelState { it.copy(isOfflineMode = false, errorMessage = null) }
        emitToast("Back online", false)
    }

    private fun parseConfigFromState(): StoredEngineConfig? {
        val state = uiState.value.model
        val path = state.modelPath.trim()
        if (path.isBlank()) {
            emitToast("Model path is required", true)
            return null
        }
        val threads = state.threadText.toIntOrNull()
        val context = state.contextText.toIntOrNull()
        val gpu = state.gpuText.toIntOrNull()

        if (threads == null || threads <= 0) {
            emitToast("Invalid thread count", true)
            return null
        }
        if (context == null || context <= 0) {
            emitToast("Invalid context length", true)
            return null
        }
        if (gpu == null || gpu < 0) {
            emitToast("Invalid GPU layer count", true)
            return null
        }
        if (!File(path).exists()) {
            emitToast("Model file not found", true)
            return null
        }
        return StoredEngineConfig(
            modelPath = path,
            threads = threads,
            contextLength = context,
            gpuLayers = gpu,
            useVulkan = state.useVulkan
        )
    }

    private inline fun updateModelState(
        crossinline reducer: (ModelState) -> ModelState
    ) {
        _uiState.update { current ->
            current.copy(model = reducer(current.model))
        }
    }

    private inline fun updateDocumentState(
        crossinline reducer: (DocumentState) -> DocumentState
    ) {
        _uiState.update { current ->
            current.copy(documents = reducer(current.documents))
        }
    }
}
