package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error as ErrorIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peerchat.app.engine.BenchmarkService
import com.peerchat.app.ui.theme.LocalSpacing
import com.peerchat.data.db.BenchmarkResult
import com.peerchat.data.db.ModelManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * State for the benchmark dialog
 */
sealed class BenchmarkDialogState {
    object Closed : BenchmarkDialogState()
    object SelectingModel : BenchmarkDialogState()
    data class Running(
        val manifest: ModelManifest,
        val progress: BenchmarkService.BenchmarkProgress,
        val results: List<BenchmarkService.BenchmarkResultData> = emptyList()
    ) : BenchmarkDialogState()
    data class Completed(
        val manifest: ModelManifest,
        val results: List<BenchmarkService.BenchmarkResultData>
    ) : BenchmarkDialogState()
    data class Error(val message: String) : BenchmarkDialogState()
}

/**
 * Benchmark dialog composable
 */
@Composable
fun BenchmarkDialog(
    state: BenchmarkDialogState,
    onDismiss: () -> Unit,
    onStartBenchmark: (ModelManifest) -> Unit,
    onCancelBenchmark: () -> Unit,
    availableModels: List<ModelManifest> = emptyList()
) {
    val spacing = LocalSpacing.current

    when (state) {
        is BenchmarkDialogState.Closed -> {
            // Nothing to show
        }

        is BenchmarkDialogState.SelectingModel -> {
            ModelSelectionDialog(
                availableModels = availableModels,
                onDismiss = onDismiss,
                onModelSelected = onStartBenchmark
            )
        }

        is BenchmarkDialogState.Running -> {
            BenchmarkRunningDialog(
                manifest = state.manifest,
                progress = state.progress,
                results = state.results,
                onCancel = onCancelBenchmark
            )
        }

        is BenchmarkDialogState.Completed -> {
            BenchmarkResultsDialog(
                manifest = state.manifest,
                results = state.results,
                onDismiss = onDismiss
            )
        }

        is BenchmarkDialogState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Benchmark Error") },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

/**
 * Dialog for selecting a model to benchmark
 */
@Composable
private fun ModelSelectionDialog(
    availableModels: List<ModelManifest>,
    onDismiss: () -> Unit,
    onModelSelected: (ModelManifest) -> Unit
) {
    val spacing = LocalSpacing.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Model for Benchmark") },
        text = {
            Column {
                Text("Choose a model to run automated performance benchmarks. The benchmark will test multiple prompts and measure key metrics like TTFT, TPS, and latency.")
                Spacer(Modifier.height(spacing.medium))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.small)
                ) {
                    items(availableModels) { manifest ->
                        ModelSelectionItem(
                            manifest = manifest,
                            onClick = { onModelSelected(manifest) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Individual model selection item
 */
@Composable
private fun ModelSelectionItem(
    manifest: ModelManifest,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = manifest.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${manifest.sizeBytes / (1024 * 1024)}MB • ${manifest.family}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Dialog showing benchmark progress
 */
@Composable
private fun BenchmarkRunningDialog(
    manifest: ModelManifest,
    progress: BenchmarkService.BenchmarkProgress,
    results: List<BenchmarkService.BenchmarkResultData>,
    onCancel: () -> Unit
) {
    val spacing = LocalSpacing.current

    AlertDialog(
        onDismissRequest = {}, // Don't allow dismissal during benchmark
        title = { Text("Benchmarking ${manifest.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.medium)
            ) {
                // Progress bar
                LinearProgressIndicator(
                    progress = { progress.progress },
                    modifier = Modifier.fillMaxWidth()
                )

                // Current stage
                Text(
                    text = progress.stage,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )

                // Current message
                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show partial results
                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(spacing.small))
                    Text(
                        text = "Completed: ${results.size} tests",
                        style = MaterialTheme.typography.bodySmall
                    )

                    val avgTtft = results.map { it.ttftMs }.average()
                    val avgTps = results.map { it.tps }.average()
                    val successRate = results.count { it.errorMessage == null }.toFloat() / results.size * 100

                    Text(
                        text = "Avg TTFT: ${avgTtft.toInt()}ms | Avg TPS: ${avgTps.toInt()} | Success: ${successRate.toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
        dismissButton = {}
    )
}

/**
 * Dialog showing benchmark results
 */
@Composable
private fun BenchmarkResultsDialog(
    manifest: ModelManifest,
    results: List<BenchmarkService.BenchmarkResultData>,
    onDismiss: () -> Unit
) {
    val spacing = LocalSpacing.current
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    val metrics = BenchmarkService.calculateAverageMetrics(
        results.map { result ->
            BenchmarkResult(
                id = 0,
                manifestId = manifest.id,
                promptText = result.promptText,
                promptTokens = result.promptTokens,
                generatedTokens = result.generatedTokens,
                ttftMs = result.ttftMs,
                totalMs = result.totalMs,
                tps = result.tps,
                contextUsedPct = result.contextUsedPct,
                errorMessage = result.errorMessage,
                runAt = System.currentTimeMillis(),
                deviceInfo = result.deviceInfo
            )
        }
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Benchmark Results: ${manifest.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.medium)
            ) {
                // Summary metrics
                MetricSummaryCard(metrics)

                Spacer(Modifier.height(spacing.small))

                // Device info
                if (results.isNotEmpty()) {
                    Text(
                        text = "Device: ${results.first().deviceInfo}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Individual test results
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.small)
                ) {
                    items(results) { result ->
                        TestResultItem(result)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Summary card showing aggregate metrics
 */
@Composable
private fun MetricSummaryCard(metrics: BenchmarkService.BenchmarkMetrics) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Summary",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricItem("TTFT", "${metrics.averageTtftMs}ms")
            MetricItem("TPS", "${metrics.averageTps.toInt()}")
            MetricItem("Context", "${metrics.averageContextUsedPct.toInt()}%")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricItem("Total Tests", metrics.totalTests.toString())
            MetricItem("Successful", metrics.successfulTests.toString())
            if (metrics.failedTests > 0) {
                MetricItem("Failed", metrics.failedTests.toString(), Color.Red)
            }
        }
    }
}

/**
 * Individual metric display
 */
@Composable
private fun MetricItem(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Individual test result item
 */
@Composable
private fun TestResultItem(result: BenchmarkService.BenchmarkResultData) {
    val spacing = LocalSpacing.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.small)
            ) {
                Icon(
                    imageVector = if (result.errorMessage == null) Icons.Default.CheckCircle else ErrorIcon,
                    contentDescription = if (result.errorMessage == null) "Success" else "Error",
                    tint = if (result.errorMessage == null) Color.Green else Color.Red,
                    modifier = Modifier.width(16.dp)
                )
                Text(
                    text = "TTFT: ${result.ttftMs}ms | TPS: ${result.tps.toInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Text(
            text = result.promptText.take(60) + if (result.promptText.length > 60) "..." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        result.errorMessage?.let { error ->
            Text(
                text = "Error: $error",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.Red
            )
        }
    }
}
