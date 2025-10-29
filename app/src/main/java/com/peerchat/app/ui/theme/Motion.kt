package com.peerchat.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

object PeerMotion {
    private const val Short = 160
    private const val Medium = 240

    fun sharedAxisXIn(@Suppress("UNUSED_PARAMETER") forward: Boolean): EnterTransition =
        fadeIn(animationSpec = tween(Medium))

    fun sharedAxisXOut(@Suppress("UNUSED_PARAMETER") forward: Boolean): ExitTransition =
        fadeOut(animationSpec = tween(Short))
}


