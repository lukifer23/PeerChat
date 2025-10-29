package com.peerchat.app.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

object PeerMotion {
    private const val Short = 160
    private const val Medium = 240

    fun sharedAxisXIn(forward: Boolean): EnterTransition =
        fadeIn(animationSpec = tween(Medium, easing = EaseInOut)) +
            (if (forward)
                AnimatedContentTransitionScope.SlideDirection.Left
            else AnimatedContentTransitionScope.SlideDirection.Right).let { dir ->
                AnimatedContentTransitionScope.SlideIn(tween(Medium, easing = EaseInOut), dir)
            }

    fun sharedAxisXOut(forward: Boolean): ExitTransition =
        fadeOut(animationSpec = tween(Short, easing = EaseInOut)) +
            (if (forward)
                AnimatedContentTransitionScope.SlideDirection.Left
            else AnimatedContentTransitionScope.SlideDirection.Right).let { dir ->
                AnimatedContentTransitionScope.SlideOut(tween(Short, easing = EaseInOut), dir)
            }
}


