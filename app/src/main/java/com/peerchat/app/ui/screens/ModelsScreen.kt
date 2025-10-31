package com.peerchat.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.peerchat.app.engine.DefaultModels
import com.peerchat.app.engine.ModelDownloadManager
import com.peerchat.app.ui.ModelsViewModel
import com.peerchat.app.ui.components.BenchmarkDialog
import com.peerchat.app.ui.components.EmptyListHint
import com.peerchat.app.ui.components.ModelCatalogRow
import com.peerchat.app.ui.components.ModelManifestRow
import com.peerchat.app.ui.components.SectionCard
import com.peerchat.app.ui.components.openUrl
import com.peerchat.app.ui.components.rememberDownloadInfo
import com.peerchat.app.ui.components.GlobalToastManager
import com.peerchat.app.ui.theme.LocalSpacing
import com.peerchat.data.db.ModelManifest
import androidx.compose.material3.OutlinedButton
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    viewModel: ModelsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val benchmarkState by viewModel.benchmarkState.collectAsState()
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

    val spacing = LocalSpacing.current

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showBenchmarkDialog() }) {
                        Icon(
                            imageVector = Icons.Filled.Science,
                            contentDescription = "Run Benchmark"
                        )
                    }
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
                .padding(horizontal = spacing.large, vertical = spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.large)
        ) {
            item {
                SectionCard(title = "Installed Models") {
                    if (uiState.manifests.isEmpty()) {
                        EmptyListHint("No models installed yet.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
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
                    Spacer(Modifier.height(spacing.small))
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
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
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

            // Benchmark results section
            item {
                BenchmarkResultsSection(
                    manifests = uiState.manifests,
                    viewModel = viewModel
                )
            }
        }

        // Benchmark dialog
        BenchmarkDialog(
            state = benchmarkState,
            onDismiss = { viewModel.dismissBenchmarkDialog() },
            onStartBenchmark = { manifest -> viewModel.startBenchmark(manifest) },
            onCancelBenchmark = { viewModel.cancelBenchmark() },
            availableModels = uiState.manifests
        )
    }
}

/**
 * Section showing recent benchmark results for each model
 */
@Composable
private fun BenchmarkResultsSection(
    manifests: List<ModelManifest>,
    viewModel: ModelsViewModel
) {
    val spacing = LocalSpacing.current

    SectionCard(title = "Benchmark Results") {
        if (manifests.isEmpty()) {
            EmptyListHint("No models available for benchmarking.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                manifests.forEach { manifest ->
                    BenchmarkResultRow(
                        manifest = manifest,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

/**
 * Individual row showing benchmark results for a model
 */
@Composable
private fun BenchmarkResultRow(
    manifest: ModelManifest,
    viewModel: ModelsViewModel
) {
    val spacing = LocalSpacing.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = manifest.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Tap to view benchmark history",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Placeholder for benchmark metrics - will be populated when we add history viewing
        OutlinedButton(
            onClick = { /* TODO: Show detailed benchmark history */ },
            enabled = false // Disabled for now, will enable when history viewing is implemented
        ) {
            Text("View Results")
        }
    }
}
