package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.Memory

@Composable
fun MetricsStrip(
    ttfsMs: Long?,
    tps: Double?,
    contextPct: Int?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ttfsMs?.let {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text("TTFS ${it}ms") },
                leadingIcon = {
                    Icon(Icons.Outlined.Timelapse, contentDescription = null)
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
        tps?.let {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text("${"%.1f".format(it)} tok/s") },
                leadingIcon = {
                    Icon(Icons.Outlined.Speed, contentDescription = null)
                }
            )
        }
        contextPct?.let {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text("ctx ${it}%") },
                leadingIcon = {
                    Icon(Icons.Outlined.Memory, contentDescription = null)
                }
            )
        }
    }
}


