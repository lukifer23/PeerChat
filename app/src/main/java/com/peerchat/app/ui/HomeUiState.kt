package com.peerchat.app.ui

import com.peerchat.app.engine.ModelRepository
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
    val detectedTemplateId: String? = null,
    val storedConfig: StoredEngineConfig? = null,
    val modelPath: String = "",
    val threadText: String = "6",
    val contextText: String = "4096",
    val gpuText: String = "20",
    val useVulkan: Boolean = true,
    val importingModel: Boolean = false,
    val cacheStats: ModelRepository.CacheStats = ModelRepository.CacheStats(),
    val isOfflineMode: Boolean = false,
    val errorMessage: String? = null
)

data class DocumentState(
    val indexing: Boolean = false
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
    val dialogState: DialogState = DialogState.None
)

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
