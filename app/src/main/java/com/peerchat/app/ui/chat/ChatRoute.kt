package com.peerchat.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jeziellago.compose.markdowntext.MarkdownText

@Composable
fun ChatRoute(chatId: Long) {
    val vm: ChatViewModel = viewModel(factory = androidx.lifecycle.viewmodel.initializer {
        val app = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current!!
        val androidApp = (app as androidx.lifecycle.ViewModelStoreOwner)
        @Suppress("UNCHECKED_CAST")
        ChatViewModel(
            application = (androidApp as android.app.Activity).application,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("chatId" to chatId.toString()))
        )
    })
    val state by vm.uiState.collectAsState()
    ChatScreen(state = state, onSend = vm::sendPrompt)
}

@Composable
private fun ChatScreen(state: ChatUiState, onSend: (String, (String) -> Unit, (com.peerchat.engine.EngineMetrics) -> Unit) -> Unit) {
    var input = remember { mutableStateOf(TextFieldValue("")) }
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LazyColumn(Modifier.weight(1f)) {
            items(state.messages) { msg ->
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    val roleLabel = if (msg.role == "user") "You:" else "Assistant:"
                    Column(modifier = Modifier.weight(1f)) {
                        Text(roleLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MarkdownText(msg.contentMarkdown)
                    }
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.contentMarkdown)) }) { Text("Copy") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input.value,
                onValueChange = { input.value = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") }
            )
            Button(
                enabled = input.value.text.isNotBlank() && !state.streaming,
                onClick = {
                    val prompt = input.value.text
                    input.value = TextFieldValue("")
                    onSend(prompt, { _ -> }, { _ -> })
                }
            ) { Text(if (state.streaming) "Generating…" else "Send") }
        }
    }
}


