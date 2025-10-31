package com.peerchat.app.ui.screens

import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.peerchat.app.ui.DocumentViewModel
import com.peerchat.app.ui.components.EmptyListHint
import com.peerchat.app.ui.components.InlineLoadingIndicator
import com.peerchat.app.ui.components.SectionCard
import com.peerchat.data.db.Document
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    onBack: () -> Unit,
    viewModel: DocumentViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.importDocument(uri) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DocumentViewModel.DocumentEvent.Toast -> {
                    Toast.makeText(
                        context,
                        event.message,
                        if (event.isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        importLauncher.launch(arrayOf("application/pdf", "text/*", "image/*"))
                    }) { Text("Import") }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionCard(title = "Imported Documents") {
                    if (uiState.documents.isEmpty()) {
                        EmptyListHint("No documents indexed yet.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            uiState.documents.forEach { document ->
                                val reindexing = document.id in uiState.reindexingIds
                                val deleting = document.id in uiState.deletingIds
                                DocumentRow(
                                    document = document,
                                    isReindexing = reindexing,
                                    isDeleting = deleting,
                                    onReindex = { viewModel.reindex(document) },
                                    onDelete = { viewModel.delete(document) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { importLauncher.launch(arrayOf("application/pdf", "text/*", "image/*")) },
                        enabled = !uiState.importInProgress,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (uiState.importInProgress) "Importing…" else "Import from file")
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentRow(
    document: Document,
    isReindexing: Boolean,
    isDeleting: Boolean,
    onReindex: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.material3.ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(document.title.ifBlank { "Untitled document" })
            Text("Mime: ${document.mime}")
            Text("Added: ${formatDate(document.createdAt)}")
            Text("Size: ${com.peerchat.app.util.FormatUtils.formatBytes(document.textBytes.size.toLong())}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onReindex,
                    enabled = !isReindexing && !isDeleting
                ) { Text(if (isReindexing) "Reindexing…" else "Reindex") }
                TextButton(
                    onClick = onDelete,
                    enabled = !isDeleting && !isReindexing
                ) { Text(if (isDeleting) "Deleting…" else "Delete") }
            }
            if (isReindexing || isDeleting) {
                InlineLoadingIndicator(message = if (isReindexing) "Reindexing…" else "Deleting…")
            }
        }
    }
}

@Deprecated("Use FormatUtils.formatBytes instead")
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun formatDate(timestamp: Long): String {
    return DateFormat.format("yyyy-MM-dd HH:mm", Date(timestamp)).toString()
}
