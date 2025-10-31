package com.peerchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.OperationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow

/**
 * Base ViewModel class providing common functionality for error handling,
 * loading states, and job management.
 */
abstract class BaseViewModel : ViewModel() {

    // Active jobs for proper cancellation
    private val activeJobs = mutableSetOf<Job>()
    
    // Loading state tracking
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Launch a coroutine job with automatic cleanup
     */
    protected fun launchCancellable(block: suspend CoroutineScope.() -> Unit): Job {
        return viewModelScope.launch(block = block).also { job ->
            activeJobs.add(job)
            job.invokeOnCompletion { activeJobs.remove(job) }
        }
    }
    
    /**
     * Launch a coroutine with loading state management
     */
    protected fun <T> launchWithLoading(
        operation: suspend CoroutineScope.() -> T,
        onSuccess: (T) -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ): Job {
        return launchCancellable {
            try {
                _isLoading.value = true
                val result = operation()
                onSuccess(result)
            } catch (e: Exception) {
                onError(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Cancel all currently active jobs launched via [launchCancellable].
     */
    protected fun cancelActiveJobs() {
        val snapshot = activeJobs.toList()
        snapshot.forEach { it.cancel() }
        activeJobs.removeAll(snapshot)
    }

    /**
     * Handle OperationResult with standardized error handling
     */
    protected fun <T> handleOperation(
        result: OperationResult<T>,
        successMessage: String? = null,
        failureMessage: String? = null,
        onSuccess: (T) -> Unit = {}
    ) {
        when (result) {
            is OperationResult.Success -> {
                onSuccess(result.data)
                successMessage?.let { emitToast(it, false) }
            }
            is OperationResult.Failure -> {
                val message = failureMessage ?: result.error
                emitToast(message, true)
            }
        }
    }

    /**
     * Execute an operation with automatic error handling and loading state
     */
    protected fun <T> executeOperation(
        operation: suspend () -> OperationResult<T>,
        successMessage: String? = null,
        failureMessage: String? = null,
        onSuccess: (T) -> Unit = {},
        showLoading: Boolean = false
    ) {
        launchCancellable {
            if (showLoading) _isLoading.value = true
            try {
                val result = operation()
                handleOperation(result, successMessage, failureMessage, onSuccess)
            } finally {
                if (showLoading) _isLoading.value = false
            }
        }
    }

    /**
     * Try an operation with automatic error wrapping and network awareness
     */
    protected suspend fun <T> tryOperation(
        operation: suspend () -> T,
        successMessage: String = "Success",
        errorPrefix: String = "Operation failed"
    ): OperationResult<T> {
        return try {
            val result = operation()
            OperationResult.Success(result, successMessage)
        } catch (e: Exception) {
            val userFriendlyMessage = when {
                isNetworkError(e) -> "$errorPrefix: Check your internet connection"
                isStorageError(e) -> "$errorPrefix: Storage access denied"
                isPermissionError(e) -> "$errorPrefix: Permission required"
                else -> "$errorPrefix: ${e.message ?: "Unknown error"}"
            }
            OperationResult.Failure(userFriendlyMessage)
        }
    }

    /**
     * Execute operation with retry logic and exponential backoff
     */
    protected fun <T> executeWithRetry(
        operation: suspend () -> OperationResult<T>,
        maxRetries: Int = 3,
        baseDelayMs: Long = 1000,
        onRetry: ((attempt: Int, error: String) -> Unit)? = null,
        onSuccess: (T) -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        launchCancellable {
            var lastError = ""
            for (attempt in 0..maxRetries) {
                val result = operation()
                when (result) {
                    is OperationResult.Success -> {
                        onSuccess(result.data)
                        return@launchCancellable
                    }
                    is OperationResult.Failure -> {
                        lastError = result.error
                        if (attempt < maxRetries) {
                            onRetry?.invoke(attempt + 1, lastError)
                            val delay = baseDelayMs * (2.0.pow(attempt.toDouble())).toLong()
                            delay(min(delay, 30000)) // Max 30 seconds
                        }
                    }
                }
            }
            onFailure(lastError)
        }
    }

    /**
     * Execute operation with offline fallback
     */
    protected fun <T> executeWithOfflineFallback(
        onlineOperation: suspend () -> OperationResult<T>,
        offlineFallback: (suspend () -> OperationResult<T>)? = null,
        onOffline: () -> Unit = {},
        onSuccess: (T, isOffline: Boolean) -> Unit = { _, _ -> },
        onFailure: (String) -> Unit = {}
    ) {
        launchCancellable {
            val result = onlineOperation()
            when (result) {
                is OperationResult.Success -> {
                    onSuccess(result.data, false)
                }
                is OperationResult.Failure -> {
                    if (isNetworkError(Exception(result.error))) {
                        onOffline()
                        if (offlineFallback != null) {
                            val offlineResult = offlineFallback()
                            when (offlineResult) {
                                is OperationResult.Success -> onSuccess(offlineResult.data, true)
                                is OperationResult.Failure -> onFailure("Offline unavailable: ${offlineResult.error}")
                            }
                        } else {
                            onFailure("Offline unavailable")
                        }
                    } else {
                        onFailure(result.error)
                    }
                }
            }
        }
    }

    /**
     * Check if exception is network-related
     */
    protected fun isNetworkError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("network") ||
               message.contains("connection") ||
               message.contains("timeout") ||
               message.contains("unreachable") ||
               message.contains("unknown host") ||
               message.contains("socket") ||
               e is java.net.SocketException ||
               e is java.net.UnknownHostException ||
               e is java.net.ConnectException ||
               e is java.io.IOException && message.contains("http")
    }

    /**
     * Check if exception is storage-related
     */
    protected fun isStorageError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("storage") ||
               message.contains("permission denied") ||
               message.contains("access denied") ||
               message.contains("readonly") ||
               message.contains("disk") ||
               e is java.io.IOException && !isNetworkError(e)
    }

    /**
     * Check if exception is permission-related
     */
    protected fun isPermissionError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("permission") ||
               message.contains("denied") ||
               message.contains("not allowed") ||
               e is SecurityException
    }

    /**
     * Emit a toast event - should be implemented by subclasses
     */
    protected abstract fun emitToast(message: String, isError: Boolean = false)

    /**
     * Cancel all active jobs
     */
    fun cancelAllOperations() {
        activeJobs.forEach { it.cancel() }
        activeJobs.clear()
    }

    override fun onCleared() {
        super.onCleared()
        cancelAllOperations()
    }
}
