
package com.peerchat.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    docImportInProgress: Boolean,
    modelImportInProgress: Boolean,
    onNewChat: () -> Unit,
    onImportDoc: () -> Unit,
    onImportModel: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDocuments: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("PeerChat") },
        actions = {
            Button(onClick = onNewChat) {
                Text("New Chat")
            }
            IconButton(onClick = { showMenu = !showMenu }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Import Document") },
                    onClick = {
                        onImportDoc()
                        showMenu = false
                    },
                    enabled = !docImportInProgress
                )
                DropdownMenuItem(
                    text = { Text("Import Model") },
                    onClick = {
                        onImportModel()
                        showMenu = false
                    },
                    enabled = !modelImportInProgress
                )
                DropdownMenuItem(
                    text = { Text("Models") },
                    onClick = {
                        onOpenModels()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Documents") },
                    onClick = {
                        onOpenDocuments()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = {
                        onOpenSettings()
                        showMenu = false
                    }
                )
            }
        }
    )
}
