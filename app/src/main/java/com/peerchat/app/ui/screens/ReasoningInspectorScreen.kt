package com.peerchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.peerchat.app.ui.theme.PeerChatTheme
import dev.jeziellago.compose.markdowntext.MarkdownText

@Composable
fun ReasoningInspectorScreen(chatId: Long, navController: NavHostController) {
    PeerChatTheme {
        val context = LocalContext.current
        val viewModel: ReasoningInspectorViewModel = viewModel {
            ReasoningInspectorViewModel(context.applicationContext as android.app.Application, chatId)
        }
        val uiState by viewModel.uiState.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Reasoning Inspector") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (uiState.messages.isEmpty()) {
                    EmptyReasoningView()
                } else {
                    ReasoningTimeline(messages = uiState.messages)
                }
            }
        }
    }
}

@Composable
private fun EmptyReasoningView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text(
            "No messages with reasoning yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Start a conversation to see the reasoning process",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReasoningTimeline(messages: List<com.peerchat.data.db.Message>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(messages.filter { it.role == "assistant" }) { message ->
            ReasoningCard(message = message)
        }
    }
}

@Composable
private fun ReasoningCard(message: com.peerchat.data.db.Message) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Assistant Response", style = MaterialTheme.typography.titleSmall)
                Text(
                    "TTFS: ${message.ttfsMs.toLong()}ms • TPS: ${String.format("%.1f", message.tps)} • Context: ${message.contextUsedPct}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Extract reasoning from message metadata if available
            val reasoning = extractReasoningFromMeta(message.metaJson)
            if (reasoning.isNotEmpty()) {
                Text("Reasoning Process:", style = MaterialTheme.typography.titleSmall)
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Text("Final Response:", style = MaterialTheme.typography.titleSmall)
            MarkdownText(message.contentMarkdown)
        }
    }
}

private fun extractReasoningFromMeta(metaJson: String): String {
    return try {
        val json = org.json.JSONObject(metaJson)
        json.optString("reasoning", "")
    } catch (e: Exception) {
        ""
    }
}
