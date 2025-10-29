package com.peerchat.app.ui

import com.peerchat.app.engine.StoredEngineConfig
import com.peerchat.data.db.Chat
import com.peerchat.data.db.Folder
import com.peerchat.data.db.Message
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime

data class HomeUiState(
    val folders: List<Folder> = emptyList(),
    val chats: List<Chat> = emptyList(),
    val messages: List<Message> = emptyList(),
    val selectedFolderId: Long? = null,
    val activeChatId: Long? = null,
    val searchQuery: String = "",
    val searchResults: List<String> = emptyList(),
    val engineStatus: EngineRuntime.EngineStatus = EngineRuntime.EngineStatus.Uninitialized,
    val engineMetrics: EngineMetrics = EngineMetrics.empty(),
    val modelMeta: String? = null,
    val manifests: List<ModelManifest> = emptyList(),
    val storedConfig: StoredEngineConfig? = null,
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
    val indexing: Boolean = false,
    val importingModel: Boolean = false
)
