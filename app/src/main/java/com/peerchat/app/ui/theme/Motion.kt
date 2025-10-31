package com.peerchat.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically

object PeerMotion {
    private const val Short = 160
    private const val Medium = 240
    private const val Long = 320
    
    fun sharedAxisXIn(forward: Boolean): EnterTransition =
        slideInHorizontally(
            initialOffsetX = { if (forward) it else -it },
            animationSpec = tween(Medium)
        ) + fadeIn(animationSpec = tween(Medium))

    fun sharedAxisXOut(forward: Boolean): ExitTransition =
        slideOutHorizontally(
            targetOffsetX = { if (forward) -it else it },
            animationSpec = tween(Short)
        ) + fadeOut(animationSpec = tween(Short))

    fun slideInFromBottom(): EnterTransition =
        slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(Medium)
        ) + fadeIn(animationSpec = tween(Medium))

    fun slideOutToBottom(): ExitTransition =
        slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(Short)
        ) + fadeOut(animationSpec = tween(Short))

    fun toastEnter(): EnterTransition =
        slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = 0.8f,
                stiffness = 400f
            )
        ) + fadeIn(animationSpec = tween(200))

    fun toastExit(): ExitTransition =
        slideOutVertically(
            targetOffsetY = { it },
            animationSpec = spring(
                dampingRatio = 0.8f,
                stiffness = 400f
            )
        ) + fadeOut(animationSpec = tween(150))
}
