package com.peerchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.OperationResult
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.engine.EngineStreamEvent
import com.peerchat.app.engine.ModelRepository
import com.peerchat.app.engine.PromptComposer
import com.peerchat.app.ui.components.GlobalToastManager
import com.peerchat.app.util.InputSanitizer
import com.peerchat.app.util.Logger
import com.peerchat.app.util.optFloatOrNull
import com.peerchat.app.util.optIntOrNull
import com.peerchat.app.util.optStringOrNull
import com.peerchat.app.ui.stream.ReasoningParser
import com.peerchat.data.db.Message
import com.peerchat.engine.EngineMetrics
import com.peerchat.rag.Retriever
import com.peerchat.rag.RagService
import com.peerchat.templates.TemplateCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

data class ChatUiState(
    val activeChatId: Long? = null,
    val chatTitle: String = "",
    val messages: List<Message> = emptyList(),
    val streaming: StreamingUiState = StreamingUiState(),
    val sysPrompt: String = "",
    val temperature: Float = 0.8f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
    val templates: List<TemplateOption> = emptyList(),
    val selectedTemplateId: String? = null,
    val detectedTemplateId: String? = null
)

sealed class ChatEvent {
    data class Toast(val message: String, val isError: Boolean = false) : ChatEvent()
    data class ChatCreated(val chatId: Long, val folderId: Long?) : ChatEvent()
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: PeerChatRepository,
    private val modelRepository: ModelRepository,
    private val promptComposer: PromptComposer,
    private val retriever: Retriever,
    private val modelService: com.peerchat.app.engine.ModelService
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(templates = getTemplateOptions()))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>()
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    override fun emitToast(message: String, isError: Boolean) {
        _events.tryEmit(ChatEvent.Toast(message, isError))
    }

    init {
        observeMessages()
        observeModelTemplate()
    }

    private fun getTemplateOptions() = TemplateCatalog.descriptors().map {
        TemplateOption(
            id = it.id,
            label = it.displayName,
            stopSequences = it.stopSequences
        )
    }

    private fun observeMessages() {
        viewModelScope.launch {
            // Only react to chatId changes, not every state update
            _uiState
                .map { it.activeChatId }
                .distinctUntilChanged()
                .collectLatest { chatId ->
                    if (chatId != null) {
                        val messages = withContext(Dispatchers.IO) {
                            repository.listMessages(chatId)
                        }
                        _uiState.update { it.copy(messages = messages) }
                    } else {
                        _uiState.update { it.copy(messages = emptyList()) }
                    }
                }
        }
    }

    private fun observeModelTemplate() {
        // Observe model template from model service (single source of truth)
        viewModelScope.launch {
            modelService.getManifestsFlow()
                .flowOn(Dispatchers.IO)
                .collectLatest { manifests ->
                    val activeManifest = withContext(Dispatchers.IO) {
                        modelRepository.getActiveManifest()
                    } ?: manifests.firstOrNull { it.isDefault }
                    
                    val detectedTemplate = activeManifest?.let {
                        modelService.getDetectedTemplateId(it)
                    }
                    
                    _uiState.update { 
                        it.copy(detectedTemplateId = detectedTemplate) 
                    }
                }
        }
    }

    fun selectChat(chatId: Long) {
        viewModelScope.launch {
            val chat = withContext(Dispatchers.IO) {
                repository.getChat(chatId)
            } ?: return@launch
            val settings = runCatching { JSONObject(chat.settingsJson) }.getOrNull()
            val templateId = settings?.optStringOrNull("templateId")
                ?.takeIf { candidate -> _uiState.value.templates.any { it.id == candidate } }
            val temperature = settings?.optFloatOrNull("temperature")
            val topP = settings?.optFloatOrNull("topP")
            val topK = settings?.optIntOrNull("topK")
            val maxTokens = settings?.optIntOrNull("maxTokens")

            _uiState.update {
                it.copy(
                    activeChatId = chatId,
                    chatTitle = chat.title,
                    sysPrompt = chat.systemPrompt,
                    temperature = temperature ?: it.temperature,
                    topP = topP ?: it.topP,
                    topK = topK ?: it.topK,
                    maxTokens = maxTokens ?: it.maxTokens,
                    selectedTemplateId = templateId,
                    streaming = StreamingUiState()
                )
            }
            clearStreamingState()
        }
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
            ?.takeIf { candidate -> _uiState.value.templates.any { it.id == candidate } }
        _uiState.update { it.copy(selectedTemplateId = normalized) }
        persistChatSettings()
    }

    private fun persistChatSettings() {
        val chatId = _uiState.value.activeChatId ?: return
        viewModelScope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
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

    fun createChat(title: String, folderId: Long?, systemPrompt: String, modelId: String) {
        executeOperation(
            operation = {
                tryOperation(
                    operation = {
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            repository.createChat(
                                title = title.trim().ifEmpty { "New Chat" },
                                folderId = folderId,
                                systemPrompt = systemPrompt,
                                modelId = modelId
                            )
                        }
                    },
                    successMessage = "Chat created",
                    errorPrefix = "Failed to create chat"
                )
            },
            onSuccess = { chatId ->
                selectChat(chatId)
                _events.tryEmit(ChatEvent.ChatCreated(chatId, folderId))
            }
        )
    }

    fun renameChat(chatId: Long, title: String) {
        executeOperation(
            operation = {
                tryOperation(
                    operation = {
                        val trimmed = title.trim().ifEmpty { "Untitled" }
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            repository.renameChat(chatId, trimmed)
                        }
                    },
                    successMessage = "Chat renamed",
                    errorPrefix = "Failed to rename chat"
                )
            }
        )
    }

    fun moveChat(chatId: Long, folderId: Long?) {
        executeOperation(
            operation = {
                tryOperation(
                    operation = {
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            repository.moveChat(chatId, folderId)
                        }
                    },
                    successMessage = "Chat moved",
                    errorPrefix = "Failed to move chat"
                )
            }
        )
    }

    fun forkChat(chatId: Long) {
        executeOperation(
            operation = {
                tryOperation(
                    operation = {
                        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            repository.forkChatResult(chatId)
                        }
                        when (result) {
                            is com.peerchat.app.data.OperationResult.Success -> {
                                val base = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    repository.getChat(chatId)
                                }
                                result.data to (base?.folderId)
                            }
                            is com.peerchat.app.data.OperationResult.Failure -> {
                                throw Exception(result.error)
                            }
                        }
                    },
                    successMessage = "Chat forked",
                    errorPrefix = "Failed to fork chat"
                )
            },
            onSuccess = { (newChatId, folderId) ->
                selectChat(newChatId)
                _events.tryEmit(ChatEvent.ChatCreated(newChatId, folderId))
            }
        )
    }

    fun deleteChat(chatId: Long) {
        executeOperation(
            operation = {
                tryOperation(
                    operation = {
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            repository.deleteChat(chatId)
                        }
                    },
                    successMessage = "Chat deleted",
                    errorPrefix = "Failed to delete chat"
                )
            },
            onSuccess = {
                if (_uiState.value.activeChatId == chatId) {
                    _uiState.update { state ->
                        state.copy(
                            activeChatId = null,
                            chatTitle = "",
                            messages = emptyList(),
                            streaming = StreamingUiState()
                        )
                    }
                }
            }
        )
    }

    private fun updateStreamingState(transform: (StreamingUiState) -> StreamingUiState) {
        _uiState.update { state ->
            state.copy(streaming = transform(state.streaming))
        }
    }

    private fun clearStreamingState() {
        _uiState.update { it.copy(streaming = StreamingUiState()) }
    }

    fun sendPrompt(prompt: String) {
        val chatId = _uiState.value.activeChatId ?: return

        Logger.i(
            "sendPrompt: received",
            mapOf(
                "chatId" to chatId,
                "inputLength" to prompt.length
            )
        )

        // Sanitize user input for security
        val sanitizedPrompt = try {
            InputSanitizer.sanitizeChatInput(prompt)
        } catch (e: SecurityException) {
            Logger.w(
                "sendPrompt: sanitization_failed",
                mapOf("chatId" to chatId),
                e
            )
            emitToast("Input validation failed: ${e.message}", true)
            return
        }

        if (sanitizedPrompt.isEmpty()) {
            Logger.w(
                "sendPrompt: sanitized_prompt_empty",
                mapOf("chatId" to chatId)
            )
            emitToast("Input contains invalid content", true)
            return
        }

        Logger.i(
            "sendPrompt: sanitized",
            mapOf(
                "chatId" to chatId,
                "length" to sanitizedPrompt.length,
                "changed" to (sanitizedPrompt != prompt)
            )
        )

        executeWithRetry(
            operation = {
                performSendPrompt(chatId, sanitizedPrompt)
            },
            maxRetries = 2, // Allow one retry for transient failures
            baseDelayMs = 2000,
            onRetry = { attempt, error ->
                emitToast("Retry $attempt/2: $error", true)
                Logger.w(
                    "sendPrompt: retry",
                    mapOf("chatId" to chatId, "attempt" to attempt, "error" to error)
                )
            },
            onSuccess = { /* Success handled in performSendPrompt */ },
            onFailure = { error ->
                emitToast("Failed to send message: $error", true)
                updateStreamingState { StreamingUiState() }
                Logger.e(
                    "sendPrompt: failed",
                    mapOf("chatId" to chatId, "error" to error)
                )
            }
        )
    }

    fun cancelGeneration() {
        cancelActiveJobs()
        updateStreamingState { StreamingUiState() }
        emitToast("Generation cancelled", false)
    }

    private suspend fun performSendPrompt(chatId: Long, prompt: String): OperationResult<Unit> = coroutineScope {
        try {
            updateStreamingState { StreamingUiState(isStreaming = true) }

            val stateSnapshot = _uiState.value
            val history = runCatching {
                repository.listMessages(chatId)
            }.getOrDefault(emptyList())

            Logger.i(
                "performSendPrompt: prepared_state",
                mapOf(
                    "chatId" to chatId,
                    "historyCount" to history.size,
                    "temperature" to stateSnapshot.temperature,
                    "topP" to stateSnapshot.topP,
                    "topK" to stateSnapshot.topK,
                    "maxTokens" to stateSnapshot.maxTokens
                )
            )

            // Save user message and start RAG retrieval in parallel
            val userMessageDeferred = async(Dispatchers.IO) {
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
            }

            // RAG retrieval in background (non-blocking)
            val ragDeferred = async(Dispatchers.IO) {
                val startNs = System.nanoTime()
                val docs = runCatching {
                    retriever.retrieve(repository.database(), prompt, topK = 6)
                }.getOrDefault(emptyList())
                Logger.i(
                    "performSendPrompt: rag_fetch_completed",
                    mapOf(
                        "chatId" to chatId,
                        "results" to docs.size,
                        "durationMs" to ((System.nanoTime() - startNs) / 1_000_000)
                    )
                )
                docs
            }

            // Wait for user message save to complete
            userMessageDeferred.await()

            // Get RAG results (should be ready by now)
            val retrieved = ragDeferred.await()
            val ctx = runCatching { RagService.buildContext(retrieved) }.getOrDefault("")
            val augmented = if (ctx.isNotBlank()) "$ctx\n\n$prompt" else prompt

            val composition = promptComposer.compose(
                PromptComposer.Inputs(
                    systemPrompt = stateSnapshot.sysPrompt.takeIf { it.isNotBlank() },
                    history = history,
                    nextUserContent = augmented,
                    selectedTemplateId = stateSnapshot.selectedTemplateId,
                    detectedTemplateId = stateSnapshot.detectedTemplateId
                )
            )

            Logger.i(
                "performSendPrompt: prompt_composed",
                mapOf(
                    "chatId" to chatId,
                    "templateId" to composition.template.id,
                    "promptLength" to composition.prompt.text.length,
                    "stopSequences" to composition.prompt.stopSequences.size
                )
            )

            val parser = ReasoningParser(onVisibleToken = {})
            var loggedFirstToken = false

            Logger.i(
                "performSendPrompt: stream_start",
                mapOf(
                    "chatId" to chatId,
                    "promptLength" to composition.prompt.text.length
                )
            )

            modelRepository.streamWithCache(
                chatId = chatId,
                prompt = composition.prompt.text,
                systemPrompt = null,
                template = composition.template.id,
                temperature = stateSnapshot.temperature,
                topP = stateSnapshot.topP,
                topK = stateSnapshot.topK,
                maxTokens = stateSnapshot.maxTokens,
                stop = composition.prompt.stopSequences.toTypedArray()
            ).collect { event ->
                when (event) {
                    is EngineStreamEvent.Token -> {
                        if (!loggedFirstToken && event.text.isNotEmpty()) {
                            loggedFirstToken = true
                            Logger.i(
                                "performSendPrompt: first_token",
                                mapOf(
                                    "chatId" to chatId,
                                    "chars" to event.text.length
                                )
                            )
                        }
                        parser.handle(event.text)
                        val snapshot = parser.snapshot()
                        updateStreamingState {
                            it.copy(
                                isStreaming = true,
                                visibleText = snapshot.visible,
                                reasoningText = snapshot.reasoning,
                                reasoningChars = snapshot.reasoningChars,
                                reasoningDurationMs = null,
                                metrics = null
                            )
                        }
                    }

                    is EngineStreamEvent.Terminal -> {
                        Logger.i(
                            "performSendPrompt: terminal",
                            mapOf(
                                "chatId" to chatId,
                                "promptTokens" to event.metrics.promptTokens,
                                "generationTokens" to event.metrics.generationTokens,
                                "ttfsMs" to event.metrics.ttfsMs,
                                "totalMs" to event.metrics.totalMs,
                                "stopReason" to event.metrics.stopReason,
                                "truncated" to event.metrics.truncated
                            )
                        )
                        val parseResult = parser.result()
                        val finalText = parseResult.visible
                        val reasoningText = parseResult.reasoning

                        // Save assistant message on background thread
                        withContext(Dispatchers.IO) {
                            repository.insertMessage(
                                Message(
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
                        }

                        updateStreamingState {
                            it.copy(
                                isStreaming = false,
                                visibleText = "",
                                reasoningText = "",
                                reasoningChars = 0,
                                reasoningDurationMs = parseResult.reasoningDurationMs,
                                metrics = event.metrics
                            )
                        }
                    }
                }
            }
            Logger.i(
                "performSendPrompt: success",
                mapOf("chatId" to chatId)
            )
            OperationResult.Success(Unit)
        } catch (e: CancellationException) {
            updateStreamingState { StreamingUiState() }
            throw e
        } catch (e: Exception) {
            // Clean up on error
            runCatching { modelRepository.clearKv(chatId) }
            updateStreamingState { StreamingUiState() }
            Logger.e(
                "performSendPrompt: exception",
                mapOf(
                    "chatId" to chatId,
                    "error" to (e.message ?: "unknown")
                ),
                e
            )
            OperationResult.Failure("Message generation failed: ${e.message ?: "Unknown error"}")
        }
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
}
