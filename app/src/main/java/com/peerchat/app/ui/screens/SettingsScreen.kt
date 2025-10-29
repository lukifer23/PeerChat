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
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.peerchat.app.ui.theme.PeerChatTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(navController: NavHostController) {
    PeerChatTheme {
        val context = LocalContext.current
        val viewModel: SettingsViewModel = viewModel {
            SettingsViewModel(context.applicationContext as android.app.Application)
        }
        val uiState by viewModel.uiState.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("Chat Settings", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = uiState.sysPrompt,
                                onValueChange = viewModel::updateSysPrompt,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("System Prompt") }
                            )
                            NumericField(
                                label = "Temperature",
                                text = uiState.temperature.toString(),
                                onValueChange = { value -> value.toFloatOrNull()?.let(viewModel::updateTemperature) }
                            )
                            NumericField(
                                label = "Top P",
                                text = uiState.topP.toString(),
                                onValueChange = { value -> value.toFloatOrNull()?.let(viewModel::updateTopP) }
                            )
                            NumericField(
                                label = "Top K",
                                text = uiState.topK.toString(),
                                onValueChange = { value -> value.toIntOrNull()?.let(viewModel::updateTopK) }
                            )
                            NumericField(
                                label = "Max Tokens",
                                text = uiState.maxTokens.toString(),
                                onValueChange = { value -> value.toIntOrNull()?.let(viewModel::updateMaxTokens) }
                            )
                        }
                    }
                }

                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("Model Configuration", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = uiState.modelPath,
                                onValueChange = viewModel::updateModelPath,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Model Path") },
                                singleLine = true
                            )
                            NumericField("Threads", uiState.threadText, viewModel::updateThreadText)
                            NumericField("Context Length", uiState.contextText, viewModel::updateContextText)
                            NumericField("GPU Layers", uiState.gpuText, viewModel::updateGpuText)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Use Vulkan")
                                Switch(checked = uiState.useVulkan, onCheckedChange = viewModel::updateUseVulkan)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = viewModel::loadModelFromInputs, enabled = uiState.modelPath.isNotBlank()) {
                                    Text("Load Model")
                                }
                                Button(onClick = viewModel::unloadModel, enabled = uiState.storedConfig != null) {
                                    Text("Unload")
                                }
                            }
                        }
                    }
                }

                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("Available Models", style = MaterialTheme.typography.titleMedium)
                            if (uiState.manifests.isEmpty()) {
                                Text(
                                    "No manifests recorded yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                uiState.manifests.forEach { manifest ->
                                    val isActive = manifest.filePath == uiState.storedConfig?.modelPath
                                    val fileExists = File(manifest.filePath).exists()
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            manifest.name + if (isActive) " (active)" else "",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "${manifest.family} • ${formatBytes(manifest.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (!fileExists) {
                                            Text(
                                                "File missing",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = { viewModel.activateManifest(manifest) }) { Text("Activate") }
                                            TextButton(onClick = { viewModel.deleteManifest(manifest) }) { Text("Delete") }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumericField(
    label: String,
    text: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return "${String.format(Locale.US, "%.2f", bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
}
