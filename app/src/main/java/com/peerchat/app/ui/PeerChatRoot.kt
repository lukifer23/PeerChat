@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.peerchat.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.work.WorkInfo
import com.peerchat.app.engine.DefaultModel
import com.peerchat.app.engine.DefaultModels
import com.peerchat.app.engine.EngineStreamEvent
import com.peerchat.app.engine.ModelConfigStore
import com.peerchat.app.engine.ModelDownloadManager
import com.peerchat.app.engine.ModelManifestService
import com.peerchat.app.engine.ModelStateCache
import com.peerchat.app.engine.ModelStorage
import com.peerchat.app.engine.StreamingEngine
import com.peerchat.app.engine.StoredEngineConfig
import com.peerchat.app.db.AppDatabase
import com.peerchat.data.db.Chat
import com.peerchat.data.db.Folder
import com.peerchat.data.db.Message
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import com.peerchat.rag.RagService
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern

private const val ROUTE_HOME = "home"

@Composable
fun PeerChatRoot() {
    val navController = rememberNavController()
    Surface(color = MaterialTheme.colorScheme.background) {
        NavHost(navController = navController, startDestination = ROUTE_HOME) {
            composable(ROUTE_HOME) {
                HomeScreen(navController = navController)
            }
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun HomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    val lifecycleScope = remember(lifecycleOwner) { lifecycleOwner?.lifecycleScope }
    val db = remember(context) { AppDatabase.get(context) }

    val chatIdState = remember { mutableStateOf<Long?>(null) }
    val selectedFolder = remember { mutableStateOf<Long?>(null) }
    val foldersFlow = remember { db.folderDao().observeAll() }
    val folders by foldersFlow.collectAsState(initial = emptyList())
    val chatsFlow = remember(selectedFolder.value) { db.chatDao().observeByFolder(selectedFolder.value) }
    val chats by chatsFlow.collectAsState(initial = emptyList())

    val coroutineScope = rememberCoroutineScope()
    val engineStatus by EngineRuntime.status.collectAsState(initial = EngineRuntime.EngineStatus.Uninitialized)
    val engineMetrics by EngineRuntime.metrics.collectAsState(initial = EngineMetrics.empty())
    val modelMeta by EngineRuntime.modelMeta.collectAsState(initial = null)

    val modelCache = remember { ModelStateCache(context) }
    val manifestService = remember { ModelManifestService(context) }
    val manifestsFlow = remember { manifestService.manifestsFlow() }
    val manifests by manifestsFlow.collectAsState(initial = emptyList())

    var storedConfig by remember { mutableStateOf(ModelConfigStore.load(context)) }
    var modelPath by remember { mutableStateOf(storedConfig?.modelPath ?: "") }
    var threadText by remember { mutableStateOf((storedConfig?.threads ?: 6).toString()) }
    var contextText by remember { mutableStateOf((storedConfig?.contextLength ?: 4096).toString()) }
    var gpuText by remember { mutableStateOf((storedConfig?.gpuLayers ?: 20).toString()) }
    var useVulkan by remember { mutableStateOf(storedConfig?.useVulkan ?: true) }
    var isLoadingModel by remember { mutableStateOf(false) }
    var importingModel by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var searchResults by remember { mutableStateOf(listOf<String>()) }
    var sysPrompt by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf(0.8f) }
    var topP by remember { mutableStateOf(0.9f) }
    var topK by remember { mutableStateOf(40) }
    var maxTokens by remember { mutableStateOf(512) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showNewChat by remember { mutableStateOf(false) }
    var tempName by remember { mutableStateOf(TextFieldValue("")) }
    var indexing by rememberSaveable { mutableStateOf(false) }

    val documentImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && lifecycleScope != null) {
            lifecycleScope.launch {
                indexing = true
                try {
                    val cr = context.contentResolver
                    val mime = cr.getType(uri) ?: "application/octet-stream"
                    val name = getDisplayName(context, uri) ?: "Document"
                    val text = when {
                        mime == "application/pdf" -> extractPdfText(context, uri)
                        mime.startsWith("text/") -> cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                        mime.startsWith("image/") -> ""
                        else -> ""
                    }
                    val docId = db.documentDao().upsert(
                        com.peerchat.data.db.Document(
                            uri = uri.toString(),
                            title = name,
                            hash = sha256(text),
                            mime = mime,
                            textBytes = text.toByteArray(),
                            createdAt = System.currentTimeMillis(),
                            metaJson = "{}"
                        )
                    )
                    val savedId = db.documentDao().upsert(
                        com.peerchat.data.db.Document(
                            id = docId,
                            uri = uri.toString(),
                            title = name,
                            hash = sha256(text),
                            mime = mime,
                            textBytes = text.toByteArray(),
                            createdAt = System.currentTimeMillis(),
                            metaJson = "{}"
                        )
                    )
                    val saved = com.peerchat.data.db.Document(
                        id = savedId,
                        uri = uri.toString(),
                        title = name,
                        hash = sha256(text),
                        mime = mime,
                        textBytes = text.toByteArray(),
                        createdAt = System.currentTimeMillis(),
                        metaJson = "{}"
                    )
                    RagService.indexDocument(db, saved, text)
                } finally {
                    indexing = false
                }
            }
        }
    }

    val modelImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                importingModel = true
                try {
                    val path = ModelStorage.importModel(context, uri)
                    if (!path.isNullOrEmpty()) {
                        modelPath = path
                        manifestService.ensureManifestFor(path)
                    }
                } finally {
                    importingModel = false
                }
            }
        }
    }

    val renameTargetId = remember { mutableStateOf<Long?>(null) }
    val moveTargetId = remember { mutableStateOf<Long?>(null) }
    val forkTargetId = remember { mutableStateOf<Long?>(null) }
    val showRenameDialog = remember { mutableStateOf(false) }
    val showMoveDialog = remember { mutableStateOf(false) }
    val showForkDialog = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (chatIdState.value == null) {
            val id = db.chatDao().upsert(
                Chat(
                    title = "New Chat",
                    folderId = null,
                    systemPrompt = sysPrompt,
                    modelId = "default",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    settingsJson = "{}"
                )
            )
            chatIdState.value = id
        }
    }

    var currentChat by remember { mutableStateOf<Chat?>(null) }

    LaunchedEffect(chatIdState.value) {
        val id = chatIdState.value ?: return@LaunchedEffect
        val chat = db.chatDao().getById(id) ?: return@LaunchedEffect
        currentChat = chat
        runCatching {
            val jo = JSONObject(chat.settingsJson)
            temperature = jo.optDouble("temperature", temperature.toDouble()).toFloat()
            topP = jo.optDouble("topP", topP.toDouble()).toFloat()
            topK = jo.optInt("topK", topK)
            maxTokens = jo.optInt("maxTokens", maxTokens)
            sysPrompt = chat.systemPrompt
        }
    }

    LaunchedEffect(storedConfig?.modelPath, engineStatus) {
        val config = storedConfig ?: return@LaunchedEffect
        if (config.modelPath.isBlank()) return@LaunchedEffect
        if (engineStatus !is EngineRuntime.EngineStatus.Uninitialized && engineStatus !is EngineRuntime.EngineStatus.Idle) return@LaunchedEffect
        val file = File(config.modelPath)
        if (!file.exists()) {
            storedConfig = null
            ModelConfigStore.clear(context)
            modelPath = ""
            return@LaunchedEffect
        }
        isLoadingModel = true
        val loaded = EngineRuntime.load(config.toEngineConfig())
        isLoadingModel = false
        if (loaded) {
            manifestService.ensureManifestFor(config.modelPath, EngineRuntime.currentModelMeta())
            modelCache.clearAll()
        } else {
            storedConfig = null
            ModelConfigStore.clear(context)
        }
    }

    LaunchedEffect(searchQuery.text) {
        val q = searchQuery.text.trim()
        if (q.isNotEmpty()) {
            val msgs = db.messageDao().searchText(q, 20).map { "Msg: " + it.contentMarkdown }
            val chunks = db.ragDao().searchChunks(q, 20).map { "Doc: " + it.text }
            searchResults = (msgs + chunks).take(50)
        } else {
            searchResults = emptyList()
        }
    }

    val bodySpacing = 16.dp

    Scaffold(
        topBar = {
            HomeTopBar(
                docImportInProgress = indexing,
                modelImportInProgress = importingModel,
                onNewChat = {
                    tempName = TextFieldValue("")
                    showNewChat = true
                },
                onImportDoc = {
                    documentImportLauncher.launch(arrayOf("application/pdf", "text/*", "image/*"))
                },
                onImportModel = {
                    modelImportLauncher.launch(arrayOf("application/octet-stream", "model/gguf", "application/x-gguf", "*/*"))
                },
                onOpenModels = { showModels = true },
                onOpenSettings = { showSettings = true }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isCompact = maxWidth < 720.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(bodySpacing)
            ) {
                StatusRow(status = engineStatus, metrics = engineMetrics)
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        singleLine = true,
                        placeholder = { Text("Search messages and docs…") }
                    )
                }
                if (searchResults.isNotEmpty()) {
                    SectionCard(title = "Search Results", actionLabel = null, onAction = null) {
                        searchResults.forEach { result ->
                            Text(result, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (isCompact) {
                    Column(verticalArrangement = Arrangement.spacedBy(bodySpacing)) {
                        SectionCard(
                            title = "Folders",
                            actionLabel = "New",
                            onAction = {
                                tempName = TextFieldValue("")
                                showNewFolder = true
                            }
                        ) {
                            if (folders.isEmpty()) {
                                EmptyListHint("No folders yet.")
                            } else {
                                folders.forEach { folder ->
                                    HomeListRow(
                                        title = folder.name,
                                        subtitle = if (selectedFolder.value == folder.id) "Selected" else null,
                                        actions = listOf(
                                            "Open" to { selectedFolder.value = folder.id }
                                        )
                                    )
                                }
                            }
                        }
                        SectionCard(
                            title = "Chats",
                            actionLabel = "New",
                            onAction = {
                                tempName = TextFieldValue("")
                                showNewChat = true
                            }
                        ) {
                            if (chats.isEmpty()) {
                                EmptyListHint("No chats yet.")
                            } else {
                                chats.forEach { chat ->
                                    HomeListRow(
                                        title = chat.title,
                                        actions = listOf(
                                            "Open" to { chatIdState.value = chat.id },
                                            "Rename" to {
                                                tempName = TextFieldValue(chat.title)
                                                renameTargetId.value = chat.id
                                                showRenameDialog.value = true
                                            },
                                            "Move" to {
                                                moveTargetId.value = chat.id
                                                showMoveDialog.value = true
                                            },
                                            "Fork" to {
                                                forkTargetId.value = chat.id
                                                showForkDialog.value = true
                                            }
                                        )
                                    )
                                }
                            }
                        }
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 360.dp)
                        ) {
                            val currentChatId = chatIdState.value ?: 0L
                            if (currentChatId > 0) {
                                ChatScreen(
                                    chatId = currentChatId,
                                    db = db,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    onSend = { prompt, appendToken, onDone ->
                                    coroutineScope.launch {
                                        val chatId = chatIdState.value ?: return@launch
                                        val restored = modelCache.restore(chatId)
                                        if (!restored) {
                                            EngineRuntime.clearState(false)
                                        }
                                        db.messageDao().insert(
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
                                        val retrieved = RagService.retrieve(db, prompt, topK = 6)
                                        val ctx = RagService.buildContext(retrieved)
                                        val augmented = if (ctx.isNotBlank()) ctx + "\n\n" + prompt else prompt

                                        var success = false
                                        StreamingEngine.stream(
                                            prompt = augmented,
                                            systemPrompt = sysPrompt,
                                            template = null,
                                            temperature = temperature,
                                            topP = topP,
                                            topK = topK,
                                            maxTokens = maxTokens,
                                            stop = emptyArray()
                                        ).collectLatest { event ->
                                            when (event) {
                                                is EngineStreamEvent.Token -> appendToken(event.text)
                                                is EngineStreamEvent.Terminal -> {
                                                    success = !event.metrics.isError
                                                    onDone(event.metrics)
                                                }
                                            }
                                        }
                                        if (success) {
                                            modelCache.capture(chatId)
                                        } else {
                                            modelCache.clear(chatId)
                                        }
                                    }
                                },
                                onFinalize = { finalText, metrics ->
                                    coroutineScope.launch {
                                        val chatId = chatIdState.value ?: return@launch
                                        val ttfs = metrics.ttfsMs.toLong()
                                        val tpsValue = metrics.tps.toFloat()
                                        val contextUsedPct = (metrics.contextUsedPct / 100.0).toFloat().coerceIn(0f, 1f)
                                        db.messageDao().insert(
                                            Message(
                                                chatId = chatId,
                                                role = "assistant",
                                                contentMarkdown = finalText,
                                                tokens = 0,
                                                ttfsMs = ttfs,
                                                tps = tpsValue,
                                                contextUsedPct = contextUsedPct,
                                                createdAt = System.currentTimeMillis(),
                                                metaJson = metrics.rawJson
                                            )
                                        )
                                    }
                                }
                            )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Select a chat to start", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(bodySpacing)
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 360.dp)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(bodySpacing)
                        ) {
                            SectionCard(
                                title = "Folders",
                                actionLabel = "New",
                                onAction = {
                                    tempName = TextFieldValue("")
                                    showNewFolder = true
                                }
                            ) {
                                if (folders.isEmpty()) {
                                    EmptyListHint("No folders yet.")
                                } else {
                                    folders.forEach { folder ->
                                        HomeListRow(
                                            title = folder.name,
                                            subtitle = if (selectedFolder.value == folder.id) "Selected" else null,
                                            actions = listOf(
                                                "Open" to { selectedFolder.value = folder.id }
                                            )
                                        )
                                    }
                                }
                            }
                            SectionCard(
                                title = "Chats",
                                actionLabel = "New",
                                onAction = {
                                    tempName = TextFieldValue("")
                                    showNewChat = true
                                }
                            ) {
                                if (chats.isEmpty()) {
                                    EmptyListHint("No chats yet.")
                                } else {
                                    chats.forEach { chat ->
                                        HomeListRow(
                                            title = chat.title,
                                            actions = listOf(
                                                "Open" to { chatIdState.value = chat.id },
                                                "Rename" to {
                                                    tempName = TextFieldValue(chat.title)
                                                    renameTargetId.value = chat.id
                                                    showRenameDialog.value = true
                                                },
                                                "Move" to {
                                                    moveTargetId.value = chat.id
                                                    showMoveDialog.value = true
                                                },
                                                "Fork" to {
                                                    forkTargetId.value = chat.id
                                                    showForkDialog.value = true
                                                }
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        ElevatedCard(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            val currentChatId2 = chatIdState.value ?: 0L
                            if (currentChatId2 > 0) {
                                ChatScreen(
                                    chatId = currentChatId2,
                                    db = db,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    onSend = { prompt, appendToken, onDone ->
                                    coroutineScope.launch {
                                        val chatId = chatIdState.value ?: return@launch
                                        val restored = modelCache.restore(chatId)
                                        if (!restored) {
                                            EngineRuntime.clearState(false)
                                        }
                                        db.messageDao().insert(
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
                                        val retrieved = RagService.retrieve(db, prompt, topK = 6)
                                        val ctx = RagService.buildContext(retrieved)
                                        val augmented = if (ctx.isNotBlank()) ctx + "\n\n" + prompt else prompt

                                        var success = false
                                        StreamingEngine.stream(
                                            prompt = augmented,
                                            systemPrompt = sysPrompt,
                                            template = null,
                                            temperature = temperature,
                                            topP = topP,
                                            topK = topK,
                                            maxTokens = maxTokens,
                                            stop = emptyArray()
                                        ).collectLatest { event ->
                                            when (event) {
                                                is EngineStreamEvent.Token -> appendToken(event.text)
                                                is EngineStreamEvent.Terminal -> {
                                                    success = !event.metrics.isError
                                                    onDone(event.metrics)
                                                }
                                            }
                                        }
                                        if (success) {
                                            modelCache.capture(chatId)
                                        } else {
                                            modelCache.clear(chatId)
                                        }
                                    }
                                },
                                onFinalize = { finalText, metrics ->
                                    coroutineScope.launch {
                                        val chatId = chatIdState.value ?: return@launch
                                        val ttfs = metrics.ttfsMs.toLong()
                                        val tpsValue = metrics.tps.toFloat()
                                        val contextUsedPct = (metrics.contextUsedPct / 100.0).toFloat().coerceIn(0f, 1f)
                                        db.messageDao().insert(
                                            Message(
                                                chatId = chatId,
                                                role = "assistant",
                                                contentMarkdown = finalText,
                                                tokens = 0,
                                                ttfsMs = ttfs,
                                                tps = tpsValue,
                                                contextUsedPct = contextUsedPct,
                                                createdAt = System.currentTimeMillis(),
                                                metaJson = metrics.rawJson
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            confirmButton = { TextButton(onClick = { showModels = false }) { Text("Close") } },
            title = { Text("Models") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DefaultModels.list.forEach { model ->
                        val workState = rememberDownloadState(model)
                        val manifest = manifests.firstOrNull { File(it.filePath).name.equals(model.suggestedFileName, ignoreCase = true) }
                        ModelCatalogRow(
                            model = model,
                            manifest = manifest,
                            workState = workState,
                            onDownload = { ModelDownloadManager.enqueue(context, model) },
                            onActivate = manifest?.let {
                                {
                                    modelPath = it.filePath
                                    if (it.contextLength > 0) contextText = it.contextLength.toString()
                                }
                            },
                            onOpenCard = { openUrl(context, model.cardUrl) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        )
    }

    if (showRenameDialog.value && renameTargetId.value != null && lifecycleScope != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog.value = false },
            confirmButton = {
                TextButton(onClick = {
                    val id = renameTargetId.value ?: return@TextButton
                    val title = tempName.text.trim()
                    lifecycleScope.launch {
                        db.chatDao().rename(id, if (title.isEmpty()) "Untitled" else title, System.currentTimeMillis())
                        showRenameDialog.value = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog.value = false }) { Text("Cancel") } },
            title = { Text("Rename Chat") },
            text = { OutlinedTextField(value = tempName, onValueChange = { tempName = it }) }
        )
    }

    if (showMoveDialog.value && moveTargetId.value != null && lifecycleScope != null) {
        AlertDialog(
            onDismissRequest = { showMoveDialog.value = false },
            confirmButton = { TextButton(onClick = { showMoveDialog.value = false }) { Text("Close") } },
            title = { Text("Move Chat to Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val id = moveTargetId.value ?: return@TextButton
                        lifecycleScope.launch {
                            db.chatDao().moveToFolder(id, null, System.currentTimeMillis())
                            showMoveDialog.value = false
                        }
                    }) { Text("No folder") }
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(folders) { folder ->
                            TextButton(onClick = {
                                val id = moveTargetId.value ?: return@TextButton
                                lifecycleScope.launch {
                                    db.chatDao().moveToFolder(id, folder.id, System.currentTimeMillis())
                                    showMoveDialog.value = false
                                }
                            }) { Text(folder.name) }
                        }
                    }
                }
            }
        )
    }

    if (showForkDialog.value && forkTargetId.value != null && lifecycleScope != null) {
        AlertDialog(
            onDismissRequest = { showForkDialog.value = false },
            confirmButton = {
                TextButton(onClick = {
                    val id = forkTargetId.value ?: return@TextButton
                    lifecycleScope.launch {
                        val base = db.chatDao().getById(id) ?: return@launch
                        val newChatId = db.chatDao().upsert(
                            base.copy(
                                id = 0,
                                title = base.title + " (copy)",
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        val messages = db.messageDao().listByChat(id)
                        for (msg in messages) {
                            db.messageDao().insert(
                                msg.copy(
                                    id = 0,
                                    chatId = newChatId,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                        }
                        showForkDialog.value = false
                        chatIdState.value = newChatId
                    }
                }) { Text("Fork") }
            },
            dismissButton = { TextButton(onClick = { showForkDialog.value = false }) { Text("Cancel") } },
            title = { Text("Fork Chat") },
            text = { Text("Create a duplicate conversation including existing messages?") }
        )
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            confirmButton = {
                TextButton(onClick = {
                    val name = tempName.text.trim().ifEmpty { "Folder" }
                    coroutineScope.launch {
                        db.folderDao().upsert(
                            Folder(
                                name = name,
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        showNewFolder = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel") } },
            title = { Text("New Folder") },
            text = { OutlinedTextField(value = tempName, onValueChange = { tempName = it }, placeholder = { Text("Folder name") }) }
        )
    }

    if (showNewChat) {
        AlertDialog(
            onDismissRequest = { showNewChat = false },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        val id = db.chatDao().upsert(
                            Chat(
                                title = tempName.text.trim().ifEmpty { "New Chat" },
                                folderId = selectedFolder.value,
                                systemPrompt = sysPrompt,
                                modelId = storedConfig?.modelPath ?: "default",
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                                settingsJson = "{}"
                            )
                        )
                        chatIdState.value = id
                        showNewChat = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewChat = false }) { Text("Cancel") } },
            title = { Text("New Chat") },
            text = { OutlinedTextField(value = tempName, onValueChange = { tempName = it }, placeholder = { Text("Chat title") }) }
        )
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            modelPath = modelPath,
            onModelPathChange = { modelPath = it },
            threadText = threadText,
            onThreadChange = { threadText = it },
            contextText = contextText,
            onContextChange = { contextText = it },
            gpuText = gpuText,
            onGpuChange = { gpuText = it },
            useVulkan = useVulkan,
            onUseVulkanChange = { useVulkan = it },
            isLoadingModel = isLoadingModel,
            manifests = manifests,
            storedConfig = storedConfig,
            engineStatus = engineStatus,
            engineMetrics = engineMetrics,
            modelMeta = modelMeta,
            onLoadModel = { config ->
                coroutineScope.launch {
                    isLoadingModel = true
                    try {
                        EngineRuntime.unload()
                        EngineRuntime.clearState(true)
                        modelCache.clearAll()
                        val loaded = EngineRuntime.load(config.toEngineConfig())
                        if (loaded) {
                            storedConfig = config
                            ModelConfigStore.save(context, config)
                            manifestService.ensureManifestFor(config.modelPath, EngineRuntime.currentModelMeta())
                        } else {
                            storedConfig = null
                            ModelConfigStore.clear(context)
                        }
                    } finally {
                        isLoadingModel = false
                    }
                }
            },
            onUnloadModel = {
                coroutineScope.launch {
                    isLoadingModel = true
                    try {
                        EngineRuntime.unload()
                        EngineRuntime.clearState(true)
                        modelCache.clearAll()
                        storedConfig = null
                        ModelConfigStore.clear(context)
                        modelPath = ""
                    } finally {
                        isLoadingModel = false
                    }
                }
            },
            onSelectManifest = { manifest ->
                modelPath = manifest.filePath
                if (manifest.contextLength > 0) {
                    contextText = manifest.contextLength.toString()
                }
            },
            onDeleteManifest = { manifest ->
                coroutineScope.launch {
                    manifestService.deleteManifest(manifest, removeFile = true)
                    if (storedConfig?.modelPath == manifest.filePath) {
                        storedConfig = null
                        ModelConfigStore.clear(context)
                        modelPath = ""
                    }
                }
            }
        )
    }
}

@Composable
private fun HomeTopBar(
    docImportInProgress: Boolean,
    modelImportInProgress: Boolean,
    onNewChat: () -> Unit,
    onImportDoc: () -> Unit,
    onImportModel: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = { Text("PeerChat") },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HomeActionChip(label = "New Chat", onClick = onNewChat)
                HomeActionChip(
                    label = if (docImportInProgress) "Importing…" else "Import Doc",
                    enabled = !docImportInProgress,
                    onClick = onImportDoc
                )
                HomeActionChip(
                    label = if (modelImportInProgress) "Importing…" else "Import Model",
                    enabled = !modelImportInProgress,
                    onClick = onImportModel
                )
                HomeActionChip(label = "Models", onClick = onOpenModels)
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }
    )
}

@Composable
private fun RowScope.HomeActionChip(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(label) })
}

@Composable
private fun StatusRow(status: EngineRuntime.EngineStatus, metrics: EngineMetrics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SuggestionChip(onClick = {}, enabled = false, label = { Text("Status: ${statusLabel(status)}") })
        SuggestionChip(onClick = {}, enabled = false, label = { Text("TTFS ${metrics.ttfsMs.toInt()} ms") })
        SuggestionChip(onClick = {}, enabled = false, label = { Text("TPS ${String.format(Locale.US, "%.2f", metrics.tps)}") })
    }
}

@Composable
private fun SectionCard(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
            content()
        }
    }
}

@Composable
private fun EmptyListHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.HomeListRow(
    title: String,
    subtitle: String? = null,
    actions: List<Pair<String, () -> Unit>>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions.forEach { (label, handler) ->
                TextButton(onClick = handler) { Text(label) }
            }
        }
    }
}

@Composable
private fun ChatScreen(
    chatId: Long,
    db: com.peerchat.data.db.PeerDatabase,
    modifier: Modifier = Modifier,
    onSend: (String, (String) -> Unit, (EngineMetrics) -> Unit) -> Unit,
    onFinalize: (String, EngineMetrics) -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val messagesFlow = remember(chatId) { db.messageDao().observeByChat(chatId) }
    val messages by messagesFlow.collectAsState(initial = emptyList())
    var streaming by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var metrics by remember { mutableStateOf<EngineMetrics?>(null) }
    val clipboard = LocalClipboardManager.current
    var reasoning by remember { mutableStateOf("") }
    var showReasoning by remember { mutableStateOf(false) }
    var inReasoning by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LazyColumn(Modifier.weight(1f)) {
            items(messages) { msg ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(
                    containerColor = if (msg.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )) {
                    Column(Modifier.padding(12.dp)) {
                        Text(msg.role.uppercase(java.util.Locale.US), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        MarkdownText(msg.contentMarkdown)
                    }
                }
            }
            item {
                if (reasoning.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Reasoning", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { showReasoning = !showReasoning }) { Text(if (showReasoning) "Hide" else "Show") }
                        }
                        if (showReasoning) {
                            Text(reasoning)
                        }
                    }
                }
                if (current.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        MarkdownText(current)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(current)) }) { Text("Copy") }
                        }
                        val codeBlocks = remember(current) { extractCodeBlocks(current) }
                        if (codeBlocks.isNotEmpty()) {
                            Column(Modifier.fillMaxWidth()) {
                                codeBlocks.forEachIndexed { idx, block ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(block)) }) {
                                            Text("Copy code #${idx + 1}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        metrics?.let { metric ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("TTFS: ${metric.ttfsMs.toInt()} ms")
                Text("TPS: ${"%.2f".format(metric.tps) }")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") }
            )
            Button(enabled = !streaming && input.text.isNotBlank(), onClick = {
                val prompt = input.text
                input = TextFieldValue("")
                current = "Assistant: "
                metrics = null
                reasoning = ""
                inReasoning = false
                streaming = true
                onSend(prompt, { token ->
                    val t = token
                    if (!inReasoning && (t.contains("<think>") || t.contains("<reasoning>") || t.contains("<|startofthink|>"))) {
                        inReasoning = true
                    }
                    if (inReasoning) {
                        reasoning += t
                    } else {
                        current += t
                    }
                    if (inReasoning && (t.contains("</think>") || t.contains("</reasoning>") || t.contains("<|endofthink|>"))) {
                        inReasoning = false
                    }
                }, { m ->
                    streaming = false
                    metrics = m
                    val finalText = current.removePrefix("Assistant: ").trimStart()
                    current = ""
                    onFinalize(finalText, m)
                })
            }) { Text("Send") }
        }
    }
}

@Composable
private fun rememberDownloadState(model: DefaultModel): WorkInfo.State? {
    val context = LocalContext.current
    val flow = remember(model.id) { ModelDownloadManager.observe(context, model) }
    val infos by flow.collectAsState(initial = emptyList())
    return infos.firstOrNull()?.state
}

@Composable
private fun ModelCatalogRow(
    model: DefaultModel,
    manifest: com.peerchat.data.db.ModelManifest?,
    workState: WorkInfo.State?,
    onDownload: () -> Unit,
    onActivate: (() -> Unit)?,
    onOpenCard: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(model.name, style = MaterialTheme.typography.bodyMedium)
        Text(model.description, style = MaterialTheme.typography.bodySmall)
        val status = when {
            workState == WorkInfo.State.RUNNING -> "Downloading…"
            workState == WorkInfo.State.ENQUEUED -> "Waiting to download"
            workState == WorkInfo.State.SUCCEEDED -> "Downloaded"
            workState == WorkInfo.State.FAILED -> "Download failed"
            else -> null
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        manifest?.let {
            Text("Installed at ${File(it.filePath).name}", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenCard) { Text("Model Card") }
            val isDownloading = workState == WorkInfo.State.RUNNING || workState == WorkInfo.State.ENQUEUED
            TextButton(onClick = onDownload, enabled = !isDownloading) { Text(if (isDownloading) "Downloading…" else "Download") }
            if (manifest != null && onActivate != null) {
                TextButton(onClick = onActivate) { Text("Activate") }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    onDismiss: () -> Unit,
    modelPath: String,
    onModelPathChange: (String) -> Unit,
    threadText: String,
    onThreadChange: (String) -> Unit,
    contextText: String,
    onContextChange: (String) -> Unit,
    gpuText: String,
    onGpuChange: (String) -> Unit,
    useVulkan: Boolean,
    onUseVulkanChange: (Boolean) -> Unit,
    isLoadingModel: Boolean,
    manifests: List<com.peerchat.data.db.ModelManifest>,
    storedConfig: StoredEngineConfig?,
    engineStatus: EngineRuntime.EngineStatus,
    engineMetrics: EngineMetrics,
    modelMeta: String?,
    onLoadModel: (StoredEngineConfig) -> Unit,
    onUnloadModel: () -> Unit,
    onSelectManifest: (com.peerchat.data.db.ModelManifest) -> Unit,
    onDeleteManifest: (com.peerchat.data.db.ModelManifest) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Engine", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = modelPath,
                    onValueChange = onModelPathChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model Path") },
                    singleLine = true
                )
                NumericField("Threads", threadText) { onThreadChange(it) }
                NumericField("Context", contextText) { onContextChange(it) }
                NumericField("GPU Layers", gpuText) { onGpuChange(it) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Use Vulkan")
                    Switch(checked = useVulkan, onCheckedChange = onUseVulkanChange)
                }
                StatusRow(engineStatus, engineMetrics)
                modelMeta?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val threads = threadText.toIntOrNull() ?: return@Button
                            val contextLen = contextText.toIntOrNull() ?: return@Button
                            val gpuLayers = gpuText.toIntOrNull() ?: return@Button
                            if (modelPath.isBlank()) return@Button
                            onLoadModel(
                                StoredEngineConfig(
                                    modelPath = modelPath,
                                    threads = threads,
                                    contextLength = contextLen,
                                    gpuLayers = gpuLayers,
                                    useVulkan = useVulkan
                                )
                            )
                        },
                        enabled = !isLoadingModel && modelPath.isNotBlank()
                    ) { Text(if (isLoadingModel) "Loading…" else "Load Model") }
                    Button(onClick = onUnloadModel, enabled = !isLoadingModel && storedConfig != null) { Text("Unload") }
                }
                HorizontalDivider()
                Text("Available Models", style = MaterialTheme.typography.titleMedium)
                if (manifests.isEmpty()) {
                    EmptyListHint("No manifests recorded yet.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(manifests) { manifest ->
                            val isActive = manifest.filePath == storedConfig?.modelPath
                            val fileExists = File(manifest.filePath).exists()
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(
                                    manifest.name + if (isActive) " (active)" else "",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "${manifest.family} • ${formatBytes(manifest.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (!fileExists) {
                                    Text(
                                        "File missing",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onSelectManifest(manifest) }) { Text("Activate") }
                                    TextButton(onClick = { onDeleteManifest(manifest) }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun NumericField(
    label: String,
    text: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

private fun statusLabel(status: EngineRuntime.EngineStatus): String = when (status) {
    EngineRuntime.EngineStatus.Uninitialized -> "Uninitialized"
    EngineRuntime.EngineStatus.Idle -> "Idle"
    is EngineRuntime.EngineStatus.Loading -> "Loading"
    is EngineRuntime.EngineStatus.Loaded -> "Loaded"
    is EngineRuntime.EngineStatus.Error -> "Error"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return "${String.format(Locale.US, "%.2f", bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.startActivity(intent)
        } else {
            context.startActivity(intent)
        }
    }
}

private fun extractCodeBlocks(markdown: String): List<String> {
    val pattern = Pattern.compile("```[a-zA-Z0-9_-]*\\n([\\s\\S]*?)```", Pattern.MULTILINE)
    val matcher = pattern.matcher(markdown)
    val out = mutableListOf<String>()
    while (matcher.find()) {
        out.add(matcher.group(1) ?: "")
    }
    return out
}

private fun extractPdfText(context: Context, uri: Uri): String {
    context.contentResolver.openInputStream(uri).use { input ->
        if (input != null) {
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper()
                return stripper.getText(doc) ?: ""
            }
        }
    }
    return ""
}

private fun getDisplayName(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return it.getString(idx)
        }
    }
    return null
}

private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(value.toByteArray())
    return hash.joinToString("") { "%02x".format(it) }
}

private fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is android.content.ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}
