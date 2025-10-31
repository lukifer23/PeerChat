package com.peerchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.OperationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Base ViewModel class providing common functionality for error handling,
 * loading states, and job management.
 */
abstract class BaseViewModel : ViewModel() {

    // Active jobs for proper cancellation
    private val activeJobs = mutableSetOf<Job>()

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
     * Execute an operation with automatic error handling
     */
    protected fun <T> executeOperation(
        operation: suspend () -> OperationResult<T>,
        successMessage: String? = null,
        failureMessage: String? = null,
        onSuccess: (T) -> Unit = {}
    ) {
        launchCancellable {
            val result = operation()
            handleOperation(result, successMessage, failureMessage, onSuccess)
        }
    }

    /**
     * Try an operation with automatic error wrapping
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
            OperationResult.Failure("$errorPrefix: ${e.message ?: "Unknown error"}")
        }
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
