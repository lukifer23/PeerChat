package com.peerchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.hilt.navigation.compose.hiltViewModel
import com.peerchat.app.engine.DefaultModels
import com.peerchat.app.engine.ModelDownloadManager
import com.peerchat.app.ui.components.EmptyListHint
import com.peerchat.app.ui.components.ModelCatalogRow
import com.peerchat.app.ui.components.rememberDownloadInfo
import com.peerchat.app.ui.ModelViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    viewModel: ModelViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(androidx.compose.material.icons.Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Catalog", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
            items(DefaultModels.list) { model ->
                val workInfo = rememberDownloadInfo(model)
                val manifest = uiState.manifests.firstOrNull {
                    File(it.filePath).name.equals(model.suggestedFileName, ignoreCase = true)
                }
                Column(Modifier.fillMaxWidth()) {
                    ModelCatalogRow(
                        model = model,
                        manifest = manifest,
                        workInfo = workInfo,
                        onDownload = { ModelDownloadManager.enqueue(context, model) },
                        onActivate = manifest?.let { m -> { viewModel.activateManifest(m) } },
                        onOpenCard = { com.peerchat.app.ui.components.openUrl(context, model.cardUrl) }
                    )
                }
            }
            item {
                HorizontalDivider()
                Text("Installed", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
            if (uiState.manifests.isEmpty()) {
                item {
                    when {
                        uiState.isImporting -> Text("Importing model…")
                        uiState.isLoading -> Text("Loading model…")
                        else -> EmptyListHint("No installed models yet.")
                    }
                }
            } else {
                items(uiState.manifests) { manifest ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(manifest.name, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                        Text(
                            manifest.filePath,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.activateManifest(manifest) }) { Text("Activate") }
                            TextButton(onClick = { viewModel.verifyManifest(manifest) }) { Text("Verify") }
                            TextButton(onClick = { viewModel.deleteManifest(manifest, removeFile = true) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
