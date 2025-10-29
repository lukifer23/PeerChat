package com.peerchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.ui.components.EmptyListHint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DocumentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = PeerChatRepository.from(context)
    val documents by repository.observeDocuments().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (documents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No documents indexed yet.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                documents.forEach { doc ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(doc.title, style = MaterialTheme.typography.titleMedium)
                            Text("${doc.mime} • ${formatBytes(doc.textBytes.size.toLong())}", style = MaterialTheme.typography.bodySmall)
                            Text("Indexed ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(doc.createdAt))}", style = MaterialTheme.typography.bodySmall)
                            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val text = String(doc.textBytes)
                                        com.peerchat.rag.RagService.indexDocument(repository.database(), doc, text)
                                    }
                                }) { Text("Re-index") }
                                TextButton(onClick = { showDeleteConfirm = doc.id }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    showDeleteConfirm?.let { id ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.deleteDocument(id)
                    }
                    showDeleteConfirm = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") } },
            title = { Text("Delete Document") },
            text = { Text("Remove this document and all its chunks?") }
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return "${String.format(Locale.US, "%.2f", bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
}
