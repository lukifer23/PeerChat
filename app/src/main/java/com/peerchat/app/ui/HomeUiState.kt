package com.peerchat.app.ui

import com.peerchat.app.engine.ModelRepository
import com.peerchat.app.engine.ModelPreloader
import com.peerchat.app.engine.ModelLoadManager
import com.peerchat.app.engine.StoredEngineConfig
import com.peerchat.data.db.Chat
import com.peerchat.data.db.Folder
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime

/**
 * Focused state classes for better separation of concerns
 */
data class NavigationState(
    val folders: List<Folder> = emptyList(),
    val selectedFolderId: Long? = null,
    val chats: List<Chat> = emptyList(),
    val activeChatId: Long? = null
)

data class SearchState(
    val searchQuery: String = "",
    val searchResults: List<SearchResultItem> = emptyList()
)

data class ModelState(
    val engineStatus: EngineRuntime.EngineStatus = EngineRuntime.EngineStatus.Uninitialized,
    val engineMetrics: EngineMetrics = EngineMetrics.empty(),
    val modelMeta: String? = null,
    val manifests: List<ModelManifest> = emptyList(),
    val activeManifestId: Long? = null,
    val detectedTemplateId: String? = null,
    val storedConfig: StoredEngineConfig? = null,
    val modelPath: String = "",
    val threadText: String = "6",
    val contextText: String = "4096",
    val gpuText: String = "20",
    val useVulkan: Boolean = true,
    val useGpuMode: Boolean = true, // true = GPU, false = CPU
    val importingModel: Boolean = false,
    val cacheStats: ModelRepository.CacheStats = ModelRepository.CacheStats(),
    val isOfflineMode: Boolean = false,
    val errorMessage: String? = null,
    // Robust loading system
    val isLoadingModel: Boolean = false,
    val loadProgress: ModelLoadManager.ModelLoadProgress? = null,
    val preloadStatuses: Map<String, ModelPreloader.PreloadStatus> = emptyMap(),
    val preloadStats: ModelPreloader.PreloadStats = ModelPreloader.PreloadStats(
        activeLoads = 0, queuedRequests = 0, preloadedModels = 0, maxPreloadedModels = 3,
        recentModels = emptyList(), preloadStatuses = emptyMap()
    )
)

data class DocumentState(
    val indexing: Boolean = false
)

data class RagRuntimeState(
    val totalDocuments: Int = 0,
    val totalChunks: Int = 0,
    val totalEmbeddings: Int = 0,
    val averageChunkTokens: Float = 0f,
    val docScoreCacheSize: Int = 0,
    val docScoreCacheMax: Int = 2000
)

data class SearchResultItem(
    val type: ResultType,
    val title: String,
    val preview: String,
    val chatId: Long? = null,
    val messageId: Long? = null,
    val documentId: Long? = null,
    val chunkId: Long? = null,
) {
    enum class ResultType {
        MESSAGE,
        DOCUMENT_CHUNK
    }
}

/**
 * Simplified HomeUiState with focused state classes
 */
data class HomeUiState(
    val navigation: NavigationState = NavigationState(),
    val search: SearchState = SearchState(),
    val model: ModelState = ModelState(),
    val documents: DocumentState = DocumentState(),
    val rag: RagRuntimeState = RagRuntimeState(),
    val dialogState: DialogState = DialogState.None
) {
    /**
     * Validate state consistency
     */
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()
        
        // Validate navigation state
        if (navigation.activeChatId != null && 
            !navigation.chats.any { it.id == navigation.activeChatId }) {
            errors.add("Active chat ID (${navigation.activeChatId}) not found in chats list")
        }
        
        if (navigation.selectedFolderId != null &&
            !navigation.folders.any { it.id == navigation.selectedFolderId }) {
            errors.add("Selected folder ID (${navigation.selectedFolderId}) not found in folders list")
        }
        
        // Validate model state
        if (model.isLoadingModel && model.loadProgress == null) {
            errors.add("Loading flag is true but no progress information available")
        }
        
        if (model.loadProgress != null && !model.isLoadingModel) {
            errors.add("Load progress exists but loading flag is false")
        }
        
        // Validate search state
        if (search.searchQuery.isNotBlank() && search.searchResults.isEmpty() && 
            model.engineStatus is EngineRuntime.EngineStatus.Loaded) {
            // This is OK - search might be in progress or no results
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
    
    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val errors: List<String>) : ValidationResult()
    }
}

data class StreamingUiState(
    val isStreaming: Boolean = false,
    val visibleText: String = "",
    val reasoningText: String = "",
    val reasoningChars: Int = 0,
    val reasoningDurationMs: Long? = null,
    val metrics: EngineMetrics? = null
)

// Supporting data classes
data class TemplateOption(
    val id: String,
    val label: String,
    val stopSequences: List<String>,
)

/**
 * Unified dialog state management
 */
sealed class DialogState {
    data object None : DialogState()
    data object Settings : DialogState()
    data object NewFolder : DialogState()
    data object NewChat : DialogState()
    data class RenameFolder(val folderId: Long, val currentName: String) : DialogState()
    data class DeleteFolder(val folderId: Long, val folderName: String) : DialogState()
    data class RenameChat(val chatId: Long, val currentTitle: String) : DialogState()
    data class DeleteChat(val chatId: Long, val chatTitle: String) : DialogState()
    data class MoveChat(val chatId: Long) : DialogState()
    data class ForkChat(val chatId: Long) : DialogState()
}
