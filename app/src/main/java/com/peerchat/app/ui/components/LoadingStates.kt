package com.peerchat.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Full screen loading overlay
 */
@Composable
fun LoadingOverlay(
    message: String? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp
            )

            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Inline loading indicator with text
 */
@Composable
fun InlineLoadingIndicator(
    message: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp
        )

        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Linear progress bar for determinate progress
 */
@Composable
fun LinearProgressWithLabel(
    progress: Float,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 4.dp)
        )
    }
}

/**
 * Shimmer loading effect with animated gradient overlay
 */
@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )

    Box(modifier = modifier) {
        content()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val shimmerWidth = size.width * 0.5f
                    val gradient = Brush.linearGradient(
                        colors = shimmerColors,
                        start = Offset(shimmerOffset - shimmerWidth, 0f),
                        end = Offset(shimmerOffset, size.height)
                    )
                    drawRect(brush = gradient)
                }
        )
    }
}

/**
 * Retry button with loading state
 */
@Composable
fun RetryButton(
    onRetry: () -> Unit,
    isLoading: Boolean,
    text: String = "Retry",
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Button(
        onClick = onRetry,
        enabled = !isLoading,
        modifier = modifier
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(text)
        }
    }
}

/**
 * Content with loading state and retry capability
 */
@Composable
fun <T> ContentWithLoading(
    data: T?,
    loading: Boolean,
    error: Throwable?,
    onRetry: () -> Unit,
    emptyContent: @Composable () -> Unit = { Text("No data available") },
    loadingContent: @Composable () -> Unit = { LoadingOverlay() },
    errorContent: @Composable (Throwable) -> Unit = { err ->
        ErrorFallback(error = err, onRetry = onRetry)
    },
    content: @Composable (T) -> Unit
) {
    when {
        error != null -> errorContent(error)
        loading -> loadingContent()
        data != null -> content(data)
        else -> emptyContent()
    }
}
