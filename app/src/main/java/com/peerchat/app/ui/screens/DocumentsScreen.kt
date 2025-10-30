package com.peerchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.peerchat.app.engine.ServiceRegistry
import com.peerchat.data.db.Document
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val repository = com.peerchat.app.data.PeerChatRepository.from(androidx.compose.ui.platform.LocalContext.current)
    val docsFlow = repository.observeDocuments()
    val documents by docsFlow.collectAsState(initial = emptyList())
    val docService = ServiceRegistry.documentService

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(androidx.compose.material.icons.Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (documents.isEmpty()) {
                item { Text("No documents yet.") }
            } else {
                items(documents) { doc: Document ->
                    Text(doc.title, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    Text(
                        doc.mime + " • " + (doc.textBytes.size) + " bytes",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            // Re-index
                            scope.launch { docService.reindexDocument(doc) }
                        }) { Text("Re-index") }
                        TextButton(onClick = {
                            scope.launch { docService.deleteDocument(doc.id) }
                        }) { Text("Delete") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
