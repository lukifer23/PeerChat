package com.peerchat.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissText: String? = null,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            when {
                onConfirm != null && !confirmText.isNullOrBlank() ->
                    TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmText) }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (!dismissText.isNullOrBlank()) {
                TextButton(onClick = onDismiss) { Text(dismissText) }
            }
        },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = content,
    )
}


