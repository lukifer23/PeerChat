@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.peerchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.peerchat.app.engine.EngineStreamEvent
import com.peerchat.app.engine.StreamingEngine
import com.peerchat.app.db.AppDatabase
import com.peerchat.data.db.Chat
import com.peerchat.data.db.Folder
import com.peerchat.data.db.Message
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import com.peerchat.rag.RagService
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import android.graphics.BitmapFactory
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.regex.Pattern

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineRuntime.ensureInitialized()
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                val db = remember { AppDatabase.get(this) }
                val chatIdState = remember { mutableStateOf<Long?>(null) }
                val selectedFolder = remember { mutableStateOf<Long?>(null) }
                val foldersFlow = remember { db.folderDao().observeAll() }
                val folders by foldersFlow.collectAsState(initial = emptyList())
                val chatsFlow = remember(selectedFolder.value) { db.chatDao().observeByFolder(selectedFolder.value) }
                val chats by chatsFlow.collectAsState(initial = emptyList())
                var showSettings by remember { mutableStateOf(false) }
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
                // dialog states for chat actions (hoisted)
                // Document picker
                var indexing by rememberSaveable { mutableStateOf(false) }
                val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        lifecycleScope.launch {
                            indexing = true
                            try {
                                val cr = contentResolver
                                val mime = cr.getType(uri) ?: "application/octet-stream"
                                val name = getDisplayName(uri) ?: "Document"
                                val text = when {
                                    mime == "application/pdf" -> extractPdfText(uri)
                                    mime.startsWith("text/") -> cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                                    mime.startsWith("image/") -> "" // OCR to be enabled when tess-two is added
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
                                val full = db.documentDao().upsert(
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
                                val doc = db.documentDao().upsert(
                                    com.peerchat.data.db.Document(
                                        id = full,
                                        uri = uri.toString(),
                                        title = name,
                                        hash = sha256(text),
                                        mime = mime,
                                        textBytes = text.toByteArray(),
                                        createdAt = System.currentTimeMillis(),
                                        metaJson = "{}"
                                    )
                                )
                                // index
                                val saved = com.peerchat.data.db.Document(
                                    id = doc,
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
                val renameTargetId = remember { mutableStateOf<Long?>(null) }
                val moveTargetId = remember { mutableStateOf<Long?>(null) }
                val forkTargetId = remember { mutableStateOf<Long?>(null) }
                val showRenameDialog = remember { mutableStateOf(false) }
                val showMoveDialog = remember { mutableStateOf(false) }
                val showForkDialog = remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    // ensure a default chat exists
                    if (chatIdState.value == null) {
                        // ensure at least one folder exists (optional)
                        if (selectedFolder.value == null) {
                            selectedFolder.value = null
                        }
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
                // keep current chat in memory for settings persistence
                var currentChat by remember { mutableStateOf<Chat?>(null) }

                // when chat changes, load its settings
                LaunchedEffect(chatIdState.value) {
                    val id = chatIdState.value ?: return@LaunchedEffect
                    val chat = db.chatDao().getById(id) ?: return@LaunchedEffect
                    currentChat = chat
                    // parse settingsJson
                    runCatching {
                        val jo = JSONObject(chat.settingsJson)
                        temperature = jo.optDouble("temperature", temperature.toDouble()).toFloat()
                        topP = jo.optDouble("topP", topP.toDouble()).toFloat()
                        topK = jo.optInt("topK", topK)
                        maxTokens = jo.optInt("maxTokens", maxTokens)
                        sysPrompt = chat.systemPrompt
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    androidx.compose.material3.CenterAlignedTopAppBar(title = { Text("PeerChat") }, actions = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.width(200.dp),
                            singleLine = true,
                            placeholder = { Text("Search") },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val q = searchQuery.text.trim()
                                    if (q.isNotEmpty()) {
                                        lifecycleScope.launch {
                                            val msgs = db.messageDao().searchText(q, 20).map { "Msg: " + it.contentMarkdown }
                                            val chunks = db.ragDao().searchChunks(q, 20).map { "Doc: " + it.text }
                                            searchResults = (msgs + chunks).take(50)
                                        }
                                    } else searchResults = emptyList()
                                }) { Icon(Icons.Default.Search, contentDescription = null) }
                            }
                        )
                        IconButton(onClick = { importLauncher.launch(arrayOf("application/pdf", "text/*", "image/*")) }) { Text("Import") }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = null) }
                    })
                    if (searchResults.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                            items(searchResults) { item -> Text(item, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                        }
                    }
                    Row(Modifier.fillMaxSize()) {
                        // left pane: folders and chats
                        Column(Modifier.width(260.dp).fillMaxHeight().padding(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Folders", style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = { tempName = TextFieldValue(""); showNewFolder = true }) { Text("New") }
                            }
                            LazyColumn(Modifier.heightIn(max = 160.dp)) {
                                items(folders) { f ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Text(f.name, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { selectedFolder.value = f.id }) { Text("Open") }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Chats", style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = { tempName = TextFieldValue(""); showNewChat = true }) { Text("New") }
                            }
                            LazyColumn(Modifier.weight(1f)) {
                                items(chats) { c ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Text(c.title, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { chatIdState.value = c.id }) { Text("Open") }
                                        TextButton(onClick = {
                                            tempName = TextFieldValue(c.title)
                                            showNewChat = false
                                            showNewFolder = false
                                            renameTargetId.value = c.id
                                            showRenameDialog.value = true
                                        }) { Text("Rename") }
                                        TextButton(onClick = {
                                            moveTargetId.value = c.id
                                            showMoveDialog.value = true
                                        }) { Text("Move") }
                                        TextButton(onClick = {
                                            forkTargetId.value = c.id
                                            showForkDialog.value = true
                                        }) { Text("Fork") }
                                    }
                                }
                            }
                        }
                        // right: chat area
                        Box(Modifier.weight(1f)) {
                            ChatScreen(onSend = { prompt, appendToken, onDone ->
                    lifecycleScope.launch {
                        val chatId = chatIdState.value ?: return@launch
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
                        // RAG: retrieve and build context, then augment prompt
                        val retrieved = RagService.retrieve(db, prompt, topK = 6)
                        val ctx = RagService.buildContext(retrieved)
                        val augmented = if (ctx.isNotBlank()) ctx + "\n\n" + prompt else prompt

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
                                is EngineStreamEvent.Terminal -> onDone(event.metrics)
                            }
                        }
                    }
                }, onFinalize = { finalText, metrics ->
                    val chatId2 = chatIdState.value ?: return@ChatScreen
                    lifecycleScope.launch {
                        val ttfs = metrics.ttfsMs.toLong()
                        val tpsv = metrics.tps.toFloat()
                        val contextUsedPct = (metrics.contextUsedPct / 100.0).toFloat().coerceIn(0f, 1f)
                        db.messageDao().insert(
                            Message(
                                chatId = chatId2,
                                role = "assistant",
                                contentMarkdown = finalText,
                                tokens = 0,
                                ttfsMs = ttfs,
                                tps = tpsv,
                                contextUsedPct = contextUsedPct,
                                createdAt = System.currentTimeMillis(),
                                metaJson = metrics.rawJson
                            )
                        )
                    }
                })
                        }
                    }
                }
                if (showSettings) {
                    AlertDialog(
                        onDismissRequest = { showSettings = false },
                        confirmButton = { TextButton(onClick = {
                            // persist to current chat
                            val id = chatIdState.value
                            if (id != null) {
                                lifecycleScope.launch {
                                    val base = db.chatDao().getById(id)
                                    if (base != null) {
                                        val settings = JSONObject().apply {
                                            put("temperature", temperature)
                                            put("topP", topP)
                                            put("topK", topK)
                                            put("maxTokens", maxTokens)
                                        }.toString()
                                        db.chatDao().upsert(
                                            base.copy(
                                                systemPrompt = sysPrompt,
                                                settingsJson = settings,
                                                updatedAt = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                }
                            }
                            showSettings = false
                        }) { Text("Save") } },
                        title = { Text("Settings") },
                        text = {
                            Column {
                                OutlinedTextField(value = sysPrompt, onValueChange = { sysPrompt = it }, label = { Text("System Prompt") })
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = temperature.toString(), onValueChange = { it.toFloatOrNull()?.let { v -> temperature = v } }, label = { Text("Temperature") })
                                OutlinedTextField(value = topP.toString(), onValueChange = { it.toFloatOrNull()?.let { v -> topP = v } }, label = { Text("top_p") })
                                OutlinedTextField(value = topK.toString(), onValueChange = { it.toIntOrNull()?.let { v -> topK = v } }, label = { Text("top_k") })
                                OutlinedTextField(value = maxTokens.toString(), onValueChange = { it.toIntOrNull()?.let { v -> maxTokens = v } }, label = { Text("Max tokens") })
                            }
                        }
                    )
                }
                if (showRenameDialog.value && renameTargetId.value != null) {
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

                if (showMoveDialog.value && moveTargetId.value != null) {
                    AlertDialog(
                        onDismissRequest = { showMoveDialog.value = false },
                        confirmButton = { TextButton(onClick = { showMoveDialog.value = false }) { Text("Close") } },
                        title = { Text("Move Chat to Folder") },
                        text = {
                            Column {
                                TextButton(onClick = {
                                    val id = moveTargetId.value ?: return@TextButton
                                    lifecycleScope.launch {
                                        db.chatDao().moveToFolder(id, null, System.currentTimeMillis())
                                        showMoveDialog.value = false
                                    }
                                }) { Text("No folder") }
                                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                                    items(folders) { f ->
                                        TextButton(onClick = {
                                            val id = moveTargetId.value ?: return@TextButton
                                            lifecycleScope.launch {
                                                db.chatDao().moveToFolder(id, f.id, System.currentTimeMillis())
                                                showMoveDialog.value = false
                                            }
                                        }) { Text(f.name) }
                                    }
                                }
                            }
                        }
                    )
                }

                if (showForkDialog.value && forkTargetId.value != null) {
                    AlertDialog(
                        onDismissRequest = { showForkDialog.value = false },
                        confirmButton = {
                            TextButton(onClick = {
                                val srcId = forkTargetId.value ?: return@TextButton
                                lifecycleScope.launch {
                                    val src = db.chatDao().getById(srcId) ?: return@launch
                                    val newChatId = db.chatDao().upsert(
                                        src.copy(
                                            id = 0,
                                            title = src.title + " (fork)",
                                            createdAt = System.currentTimeMillis(),
                                            updatedAt = System.currentTimeMillis()
                                        )
                                    )
                                    val msgs = db.messageDao().listByChat(srcId)
                                    for (m in msgs) {
                                        db.messageDao().insert(
                                            m.copy(
                                                id = 0,
                                                chatId = newChatId,
                                                createdAt = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                    chatIdState.value = newChatId
                                    showForkDialog.value = false
                                }
                            }) { Text("Fork") }
                        },
                        dismissButton = { TextButton(onClick = { showForkDialog.value = false }) { Text("Cancel") } },
                        title = { Text("Fork Chat") },
                        text = { Text("Duplicate this chat and its messages?") }
                    )
                }
                if (showNewFolder) {
                    AlertDialog(
                        onDismissRequest = { showNewFolder = false },
                        confirmButton = {
                            TextButton(onClick = {
                                val name = tempName.text.trim()
                                if (name.isNotEmpty()) {
                                    lifecycleScope.launch {
                                        db.folderDao().upsert(
                                            Folder(
                                                name = name,
                                                createdAt = System.currentTimeMillis(),
                                                updatedAt = System.currentTimeMillis()
                                            )
                                        )
                                        showNewFolder = false
                                    }
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
                                val name = tempName.text.trim()
                                lifecycleScope.launch {
                                    val id = db.chatDao().upsert(
                                        Chat(
                                            title = if (name.isEmpty()) "New Chat" else name,
                                            folderId = selectedFolder.value,
                                            systemPrompt = sysPrompt,
                                            modelId = "default",
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
            }
        }
    }
}

@Composable
private fun ChatScreen(
    onSend: (String, (String) -> Unit, (EngineMetrics) -> Unit) -> Unit,
    onFinalize: (String, EngineMetrics) -> Unit
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var messages by remember { mutableStateOf(listOf<String>()) }
    var streaming by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var metrics by remember { mutableStateOf<EngineMetrics?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var reasoning by remember { mutableStateOf("") }
    var showReasoning by remember { mutableStateOf(false) }
    var inReasoning by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(Modifier.weight(1f)) {
            items(messages) { msg ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(msg, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg)) }) { Text("Copy") }
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
                        // per-code-block copy controls
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
                Text("TPS: ${"%.2f".format(metric.tps)}")
            }
        }
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") }
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = !streaming && input.text.isNotBlank(), onClick = {
                val prompt = input.text
                input = TextFieldValue("")
                messages = messages + ("You: " + prompt)
                current = "Assistant: "
                metrics = null
                reasoning = ""
                inReasoning = false
                streaming = true
                onSend(prompt, { token ->
                    // simple tag-based reasoning extraction for common models
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
                    messages = messages + current
                    current = ""
                    onFinalize(finalText, m)
                })
            }) { Text("Send") }
        }
    }
}

private fun extractCodeBlocks(markdown: String): List<String> {
    val pattern = Pattern.compile("```[a-zA-Z0-9_-]*\\n([\\s\\S]*?)```", Pattern.MULTILINE)
    val m = pattern.matcher(markdown)
    val out = mutableListOf<String>()
    while (m.find()) {
        out.add(m.group(1) ?: "")
    }
    return out
}

private fun MainActivity.extractPdfText(uri: android.net.Uri): String {
    contentResolver.openInputStream(uri).use { input ->
        if (input != null) {
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper()
                return stripper.getText(doc) ?: ""
            }
        }
    }
    return ""
}

private fun MainActivity.getDisplayName(uri: android.net.Uri): String? {
    val cursor = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return it.getString(idx)
        }
    }
    return null
}

private fun sha256(s: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val b = md.digest(s.toByteArray())
    return b.joinToString("") { "%02x".format(it) }
}

// OCR extraction (disabled until tess-two is added)
private fun MainActivity.extractOcrText(uri: android.net.Uri): String = ""
