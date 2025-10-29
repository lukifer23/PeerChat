package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarChips(
    onNewChat: () -> Unit,
    onImportDoc: () -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 12.dp)
    ) {
        SuggestionChip(
            onClick = onNewChat,
            label = { Text("New Chat") },
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) }
        )
        AssistChip(
            onClick = onImportDoc,
            label = { Text("Import Doc") },
            leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) }
        )
        AssistChip(
            onClick = onModels,
            label = { Text("Models") },
            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) }
        )
        FilterChip(
            selected = false,
            onClick = onSettings,
            label = { Text("Settings") },
            leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) }
        )
    }
}


