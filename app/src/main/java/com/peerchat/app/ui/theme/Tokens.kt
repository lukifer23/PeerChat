package com.peerchat.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class PeerSpacing(
    val micro: Dp = 2.dp,
    val tiny: Dp = 4.dp,
    val extraSmall: Dp = 8.dp,
    val small: Dp = 12.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp,
    val section: Dp = 40.dp
)

data class PeerElevations(
    val level0: Dp = 0.dp,
    val level1: Dp = 2.dp,
    val level2: Dp = 6.dp,
    val level3: Dp = 12.dp,
    val level4: Dp = 20.dp
)

val LocalSpacing = staticCompositionLocalOf { PeerSpacing() }
val LocalElevations = staticCompositionLocalOf { PeerElevations() }
val LocalGradientColors = staticCompositionLocalOf { DarkGradientColors }

