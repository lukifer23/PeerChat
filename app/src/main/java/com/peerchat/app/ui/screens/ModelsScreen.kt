package com.peerchat.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.peerchat.app.engine.DefaultModels
import com.peerchat.app.engine.ModelDownloadManager
import com.peerchat.app.ui.ModelsViewModel
import com.peerchat.app.ui.components.EmptyListHint
import com.peerchat.app.ui.components.ModelCatalogRow
import com.peerchat.app.ui.components.ModelManifestRow
import com.peerchat.app.ui.components.SectionCard
import com.peerchat.app.ui.components.openUrl
import com.peerchat.app.ui.components.rememberDownloadInfo
import com.peerchat.app.ui.components.GlobalToastManager
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    viewModel: ModelsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.import(uri) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ModelsViewModel.ModelsEvent.Toast -> {
                    GlobalToastManager.showToast(event.message, event.isError)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        importLauncher.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
                    }) {
                        Text("Import")
                    }
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
                SectionCard(title = "Installed Models") {
                    if (uiState.manifests.isEmpty()) {
                        EmptyListHint("No models installed yet.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            uiState.manifests.forEach { manifest ->
                                val isActive = manifest.id == uiState.activeManifestId
                                val isBusy = manifest.id == uiState.activatingId ||
                                    manifest.id in uiState.verifyingIds ||
                                    manifest.id in uiState.deletingIds
                                ModelManifestRow(
                                    manifest = manifest,
                                    isActive = isActive,
                                    isBusy = isBusy,
                                    onLoad = { viewModel.activate(manifest) },
                                    onVerify = { viewModel.verify(manifest) },
                                    onDelete = { viewModel.delete(manifest, removeFile = false) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*")) },
                        enabled = !uiState.importInProgress,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (uiState.importInProgress) "Importing…" else "Import from file")
                    }
                }
            }

            item {
                SectionCard(title = "Default Catalog") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DefaultModels.list.forEach { defaultModel ->
                            val manifest = uiState.manifests.firstOrNull { manifest ->
                                File(manifest.filePath).name.equals(defaultModel.suggestedFileName, ignoreCase = true)
                            }
                            val workInfo = rememberDownloadInfo(defaultModel)
                            ModelCatalogRow(
                                model = defaultModel,
                                manifest = manifest,
                                workInfo = workInfo,
                                onDownload = { ModelDownloadManager.enqueue(context, defaultModel) },
                                onActivate = manifest?.let { { viewModel.activate(it) } },
                                onOpenCard = { openUrl(context, defaultModel.cardUrl) }
                            )
                        }
                    }
                }
            }
        }
    }
}
