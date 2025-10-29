
package com.peerchat.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.engine.ServiceRegistry
import com.peerchat.app.engine.EngineStreamEvent
import com.peerchat.app.engine.StreamingEngine
import com.peerchat.app.engine.PromptComposer
import com.peerchat.data.db.Message
import com.peerchat.engine.EngineMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.peerchat.app.util.optFloatOrNull
import com.peerchat.app.util.optIntOrNull
import com.peerchat.app.util.optStringOrNull

class ChatViewModel(application: Application, private val chatId: Long) : AndroidViewModel(application) {

    private val repository = ServiceRegistry.chatRepository
    private val modelCache = ServiceRegistry.modelCache

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.database().messageDao().observeByChat(chatId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
        loadChatSettings()
    }

    private fun loadChatSettings() {
        viewModelScope.launch {
            val chat = repository.getChat(chatId) ?: return@launch
            val settings = runCatching { JSONObject(chat.settingsJson) }.getOrNull()
            val templateId = settings?.optStringOrNull("templateId")
            val temperature = settings?.optFloatOrNull("temperature")
            val topP = settings?.optFloatOrNull("topP")
            val topK = settings?.optIntOrNull("topK")
            val maxTokens = settings?.optIntOrNull("maxTokens")
            _uiState.update { state ->
                state.copy(
                    systemPrompt = chat.systemPrompt,
                    temperature = temperature ?: state.temperature,
                    topP = topP ?: state.topP,
                    topK = topK ?: state.topK,
                    maxTokens = maxTokens ?: state.maxTokens,
                    selectedTemplateId = templateId
                )
            }
        }
    }

    fun sendPrompt(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String, EngineMetrics) -> Unit
    ) {
        viewModelScope.launch {
            val restored = modelCache.restore(chatId)
            if (!restored) {
                com.peerchat.engine.EngineRuntime.clearState(false)
            }
            val state = uiState.value
            val history = repository.listMessages(chatId)
            repository.insertMessage(
                Message(
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

            val retrieved = com.peerchat.app.rag.RagService.retrieveHybrid(repository.database(), prompt, topK = 6)
            val ctx = com.peerchat.app.rag.RagService.buildContext(retrieved)
            val augmented = if (ctx.isNotBlank()) "$ctx\n\n$prompt" else prompt
            val composition = PromptComposer.compose(
                PromptComposer.Inputs(
                    systemPrompt = state.systemPrompt.takeIf { it.isNotBlank() },
                    history = history,
                    nextUserContent = augmented,
                    selectedTemplateId = state.selectedTemplateId,
                    detectedTemplateId = null // TODO: get from model manifest
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
                            Message(
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
