package com.peerchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.peerchat.app.data.OperationResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import com.peerchat.app.engine.DefaultModels
import com.peerchat.app.engine.ModelDownloadManager
import com.peerchat.app.engine.ServiceRegistry
import com.peerchat.app.ui.components.EmptyListHint
import com.peerchat.app.ui.components.ModelCatalogRow
import com.peerchat.app.ui.components.rememberDownloadInfo
import com.peerchat.data.db.ModelManifest
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val modelService = ServiceRegistry.modelService
    val manifests by modelService.getManifestsFlow().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val snackbarMessage = remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    // Handle snackbar messages
    val currentMessage = snackbarMessage.value
    if (currentMessage != null) {
        androidx.compose.runtime.LaunchedEffect(currentMessage) {
            snackbar.showSnackbar(currentMessage)
            snackbarMessage.value = null
        }
    }

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
        snackbarHost = { SnackbarHost(hostState = snackbar) }
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
                val manifest = manifests.firstOrNull { java.io.File(it.filePath).name.equals(model.suggestedFileName, ignoreCase = true) }
                Column(Modifier.fillMaxWidth()) {
                    ModelCatalogRow(
                        model = model,
                        manifest = manifest,
                        workInfo = workInfo,
                        onDownload = { ModelDownloadManager.enqueue(context, model) },
                        onActivate = manifest?.let { m -> {
                            scope.launch {
                                val result = modelService.activateManifest(m)
                                val message = when (result) {
                                    is OperationResult.Success -> result.message
                                    is OperationResult.Failure -> result.error
                                }
                                snackbar.showSnackbar(message)
                            }
                        } },
                        onOpenCard = { com.peerchat.app.ui.components.openUrl(context, model.cardUrl) }
                    )
                }
            }
            item {
                HorizontalDivider()
                Text("Installed", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
            if (manifests.isEmpty()) {
                item { EmptyListHint("No installed models yet.") }
            } else {
                items(manifests) { manifest: ModelManifest ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(manifest.name, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                        Text(
                            manifest.filePath,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                scope.launch {
                                    val result = modelService.activateManifest(manifest)
                                    val message = when (result) {
                                        is OperationResult.Success -> result.message
                                        is OperationResult.Failure -> result.error
                                    }
                                    snackbarMessage.value = message
                                }
                            }) { Text("Activate") }
                            TextButton(onClick = {
                                scope.launch {
                                    val ok = modelService.verifyManifest(manifest)
                                    snackbarMessage.value = if (ok) "Checksum verified" else "File missing"
                                }
                            }) { Text("Verify") }
                            TextButton(onClick = {
                                scope.launch {
                                    val msg = modelService.deleteManifest(manifest, removeFile = true)
                                    snackbarMessage.value = msg
                                }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
