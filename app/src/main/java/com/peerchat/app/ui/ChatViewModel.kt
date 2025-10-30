package com.peerchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.engine.ModelStateCache
import com.peerchat.app.engine.PromptComposer
import com.peerchat.app.engine.StreamingEngine
import com.peerchat.app.ui.components.GlobalToastManager
import com.peerchat.data.db.Message
import com.peerchat.engine.EngineMetrics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isStreaming: Boolean = false,
    val currentStreamingText: String = "",
    val streamingMetrics: EngineMetrics? = null,
    val showReasoning: Boolean = false,
    val reasoningText: String = ""
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: PeerChatRepository,
    private val streamingEngine: StreamingEngine,
    private val promptComposer: PromptComposer,
    private val modelCache: ModelStateCache
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var activeChatId: Long? = null

    fun loadChat(chatId: Long) {
        activeChatId = chatId
        viewModelScope.launch {
            val messages = repository.listMessages(chatId)
            _uiState.update { it.copy(messages = messages) }
        }
    }

    fun sendMessage(prompt: String) {
        val chatId = activeChatId ?: return

        viewModelScope.launch {
            try {
                // Save user message
                val userMessage = com.peerchat.data.db.Message(
                    id = 0, // Auto-generated
                    chatId = chatId,
                    role = "user",
                    contentMarkdown = prompt,
                    tokens = 0, // Will be updated when streaming completes
                    ttfsMs = 0L,
                    tps = 0f,
                    contextUsedPct = 0f,
                    createdAt = System.currentTimeMillis(),
                    metaJson = "{}"
                )
                repository.insertMessage(userMessage)

                // Restore model state
                modelCache.restore(chatId)

                // Get chat history for context
                val messages = repository.listMessages(chatId)
                val inputs = com.peerchat.app.engine.PromptComposer.Inputs(
                    systemPrompt = null, // TODO: Get from chat settings
                    history = messages,
                    nextUserContent = prompt,
                    selectedTemplateId = null, // TODO: Get from settings
                    detectedTemplateId = null // TODO: Get from model
                )
                val result = promptComposer.compose(inputs)
                val promptText = result.prompt.text

                // Start streaming response
                _uiState.update { it.copy(isStreaming = true, currentStreamingText = "") }

                val streamFlow = streamingEngine.stream(
                    prompt = promptText,
                    systemPrompt = null, // TODO: Get from settings
                    template = result.template.id,
                    temperature = 0.8f, // TODO: Get from settings
                    topP = 0.9f, // TODO: Get from settings
                    topK = 40, // TODO: Get from settings
                    maxTokens = 512, // TODO: Get from settings
                    stop = emptyArray() // TODO: Get from template
                )

                streamFlow.collect { event ->
                    when (event) {
                        is com.peerchat.app.engine.EngineStreamEvent.Token -> {
                            _uiState.update { state ->
                                state.copy(currentStreamingText = state.currentStreamingText + event.text)
                            }
                        }
                        is com.peerchat.app.engine.EngineStreamEvent.Terminal -> {
                            val finalText = _uiState.value.currentStreamingText

                            // Save assistant message
                            val assistantMessage = com.peerchat.data.db.Message(
                                id = 0, // Auto-generated
                                chatId = chatId,
                                role = "assistant",
                                contentMarkdown = finalText,
                                tokens = event.metrics.promptTokens + event.metrics.generationTokens,
                                ttfsMs = event.metrics.ttfsMs.toLong(),
                                tps = event.metrics.tps.toFloat(),
                                contextUsedPct = event.metrics.contextUsedPct.toFloat(),
                                createdAt = System.currentTimeMillis(),
                                metaJson = "{}" // TODO: Add reasoning and metrics
                            )
                            repository.insertMessage(assistantMessage)

                            // Save model state
                            modelCache.capture(chatId)

                            // Update UI
                            _uiState.update { it.copy(
                                isStreaming = false,
                                currentStreamingText = "",
                                streamingMetrics = event.metrics ?: com.peerchat.engine.EngineMetrics.empty()
                            )}

                            // Streaming completed successfully
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isStreaming = false, currentStreamingText = "") }
                GlobalToastManager.showToast("Error sending message: ${e.message}", isError = true)
            }
        }
    }

    fun toggleReasoning() {
        _uiState.update { it.copy(showReasoning = !it.showReasoning) }
    }

    fun clearStreamingState() {
        _uiState.update { it.copy(
            isStreaming = false,
            currentStreamingText = "",
            streamingMetrics = com.peerchat.engine.EngineMetrics.empty()
        )}
    }
}
