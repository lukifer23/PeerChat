package com.peerchat.app.ui

import com.peerchat.app.engine.StoredEngineConfig
import com.peerchat.data.db.Chat
import com.peerchat.data.db.Folder
import com.peerchat.data.db.Message
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import com.peerchat.app.engine.ModelRepository

/**
 * Original HomeUiState with improved internal organization
 * We keep the same API but add better documentation and grouping
 */
data class HomeUiState(
    // Navigation
    val folders: List<com.peerchat.data.db.Folder> = emptyList(),
    val chats: List<com.peerchat.data.db.Chat> = emptyList(),
    val selectedFolderId: Long? = null,
    val activeChatId: Long? = null,

    // Chat
    val messages: List<com.peerchat.data.db.Message> = emptyList(),

    // Search
    val searchQuery: String = "",
    val searchResults: List<String> = emptyList(),

    // Engine/Model
    val engineStatus: com.peerchat.engine.EngineRuntime.EngineStatus = com.peerchat.engine.EngineRuntime.EngineStatus.Uninitialized,
    val engineMetrics: com.peerchat.engine.EngineMetrics = com.peerchat.engine.EngineMetrics.empty(),
    val modelMeta: String? = null,
    val manifests: List<com.peerchat.data.db.ModelManifest> = emptyList(),

    // Settings
    val storedConfig: com.peerchat.app.engine.StoredEngineConfig? = null,
    val modelPath: String = "",
    val threadText: String = "6",
    val contextText: String = "4096",
    val gpuText: String = "20",
    val useVulkan: Boolean = true,
    val sysPrompt: String = "",
    val temperature: Float = 0.8f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
    val templates: List<TemplateOption> = emptyList(),
    val selectedTemplateId: String? = null,
    val detectedTemplateId: String? = null,

    // Loading states
    val indexing: Boolean = false,
    val importingModel: Boolean = false,

    // Metrics
    val cacheStats: ModelRepository.CacheStats = ModelRepository.CacheStats(),

    // Dialog state
    val dialogState: DialogState = DialogState.None
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
    data class RenameChat(val chatId: Long, val currentTitle: String) : DialogState()
    data class MoveChat(val chatId: Long) : DialogState()
    data class ForkChat(val chatId: Long) : DialogState()
}
