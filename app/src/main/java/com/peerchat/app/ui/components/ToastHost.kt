package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Global toast/snackbar host for the app
 */
@Composable
fun AppToastHost(
    toastManager: ToastManager = GlobalToastManager,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val toastState by remember { mutableStateOf(toastManager.toastState) }

    LaunchedEffect(toastState) {
        toastState?.let { state ->
            val result = snackbarHostState.showSnackbar(
                message = state.message,
                actionLabel = if (state.isError) "OK" else null
            )

            when (result) {
                SnackbarResult.Dismissed -> {
                    toastManager.dismissToast()
                }
                SnackbarResult.ActionPerformed -> {
                    toastManager.dismissToast()
                }
            }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier.padding(16.dp)
    ) { snackbarData ->
        Snackbar(
            snackbarData = snackbarData,
            actionColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Extension function to show toast from Composable
 */
@Composable
fun showToast(
    message: String,
    isError: Boolean = false,
    toastManager: ToastManager = GlobalToastManager
) {
    LaunchedEffect(message, isError) {
        toastManager.showToast(message, isError)
    }
}

/**
 * Toast utilities for ViewModels
 */
fun showSuccessToast(message: String, toastManager: ToastManager = GlobalToastManager) {
    toastManager.showToast(message, isError = false)
}

fun showErrorToast(message: String, toastManager: ToastManager = GlobalToastManager) {
    toastManager.showToast(message, isError = true)
}

/**
 * Auto-dismissing toast for temporary messages
 */
@Composable
fun AutoDismissToast(
    message: String,
    isError: Boolean = false,
    durationMillis: Long = 3000,
    toastManager: ToastManager = GlobalToastManager
) {
    LaunchedEffect(message) {
        toastManager.showToast(message, isError)
        delay(durationMillis)
        toastManager.dismissToast()
    }
}
