package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Global error boundary that catches and displays errors gracefully
 */
@Composable
fun ErrorBoundary(
    modifier: Modifier = Modifier,
    fallback: @Composable (Throwable, () -> Unit) -> Unit = { error, retry ->
        ErrorFallback(error = error, onRetry = retry)
    },
    content: @Composable () -> Unit
) {
    // Note: Try-catch around composables is not supported in Compose
    // This is a simplified error boundary for demonstration
    // In production, use proper error handling in ViewModels and effects
    content()
}

/**
 * Default error fallback UI
 */
@Composable
fun ErrorFallback(
    error: Throwable,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error
        )

        Text(
            text = error.localizedMessage ?: "Unknown error occurred",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(onClick = onRetry) {
            Text("Try Again")
        }
    }
}

/**
 * Error dialog for recoverable errors
 */
@Composable
fun ErrorDialog(
    error: Throwable?,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    title: String = "Error",
    modifier: Modifier = Modifier
) {
    if (error == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(error.localizedMessage ?: "An unexpected error occurred")
        },
        confirmButton = {
            if (onRetry != null) {
                Button(onClick = {
                    onDismiss()
                    onRetry()
                }) {
                    Text("Retry")
                }
            } else {
                Button(onClick = onDismiss) {
                    Text("OK")
                }
            }
        },
        dismissButton = if (onRetry != null) {
            {
                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        } else null
    )
}

/**
 * Coroutine exception handler that can be used to catch errors in ViewModels
 */
class ErrorHandler(
    private val onError: (Throwable) -> Unit
) : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        onError(exception)
    }
}

/**
 * Loading state wrapper with error handling
 */
@Composable
fun <T> AsyncContent(
    data: T?,
    loading: Boolean,
    error: Throwable?,
    onRetry: (() -> Unit)? = null,
    loadingContent: @Composable () -> Unit = { LoadingIndicator() },
    errorContent: @Composable (Throwable, () -> Unit) -> Unit = { err, retry ->
        ErrorFallback(error = err, onRetry = retry)
    },
    content: @Composable (T) -> Unit
) {
    when {
        error != null -> errorContent(error, onRetry ?: {})
        loading -> loadingContent()
        data != null -> content(data)
        else -> content(null as T) // Handle null case in content
    }
}

/**
 * Simple loading indicator
 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Loading...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Toast/Snackbar state management
 */
data class ToastState(
    val message: String,
    val isError: Boolean = false
)

class ToastManager {
    private var currentToast by mutableStateOf<ToastState?>(null)

    fun showToast(message: String, isError: Boolean = false) {
        currentToast = ToastState(message, isError)
    }

    fun dismissToast() {
        currentToast = null
    }

    val toastState: ToastState? get() = currentToast
}

// Global toast manager instance
val GlobalToastManager = ToastManager()
