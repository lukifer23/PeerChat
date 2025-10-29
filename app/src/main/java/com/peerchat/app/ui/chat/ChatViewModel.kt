package com.peerchat.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.engine.EngineStreamEvent
import com.peerchat.app.engine.ModelStateCache
import com.peerchat.app.engine.StreamingEngine
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import com.peerchat.rag.RagService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val chatId: Long,
    val messages: List<com.peerchat.data.db.Message> = emptyList(),
    val streaming: Boolean = false,
)

class ChatViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = PeerChatRepository.from(appContext)
    private val modelCache = ModelStateCache(appContext)

    private val chatId: Long = checkNotNull(savedStateHandle.get<String>("chatId")).toLong()

    private val _uiState = MutableStateFlow(ChatUiState(chatId = chatId))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.database().messageDao().observeByChat(chatId).collectLatest { msgs ->
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    fun sendPrompt(prompt: String, onToken: (String) -> Unit, onComplete: (String, EngineMetrics) -> Unit) {
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
            _uiState.update { it.copy(streaming = true) }
            StreamingEngine.stream(
                prompt = augmented,
                systemPrompt = null,
                template = null,
                temperature = 0.8f,
                topP = 0.9f,
                topK = 40,
                maxTokens = 512,
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
                        _uiState.update { it.copy(streaming = false) }
                    }
                }
            }
        }
    }
}


