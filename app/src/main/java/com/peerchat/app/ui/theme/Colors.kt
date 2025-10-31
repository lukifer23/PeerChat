package com.peerchat.app.ui.theme

import androidx.compose.ui.graphics.Color

// Light palette
val LightPrimary = Color(0xFF006B5D)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFF6DF5DE)
val LightOnPrimaryContainer = Color(0xFF00201B)

val LightSecondary = Color(0xFF006A64)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFF70F6EB)
val LightOnSecondaryContainer = Color(0xFF00201E)

val LightTertiary = Color(0xFF7C5800)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFDEAA)
val LightOnTertiaryContainer = Color(0xFF271900)

val LightBackground = Color(0xFFF3FFFB)
val LightOnBackground = Color(0xFF00201C)
val LightSurface = Color(0xFFFAFFFD)
val LightOnSurface = Color(0xFF00201C)
val LightSurfaceVariant = Color(0xFFD0E4E0)
val LightOnSurfaceVariant = Color(0xFF3D5A55)
val LightOutline = Color(0xFF6A8B86)
val LightOutlineVariant = Color(0xFFB3CEC8)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightInverseSurface = Color(0xFF10302C)
val LightInverseOnSurface = Color(0xFFE5FFFA)
val LightInversePrimary = Color(0xFF4AE9CB)

val LightGradientTop = Color(0xFFE5FFF9)
val LightGradientBottom = Color(0xFFF6FFFE)
val LightOverlay = Color(0x29FFFFFF)

// Dark palette
val DarkPrimary = Color(0xFF4AE9CB)
val DarkOnPrimary = Color(0xFF003731)
val DarkPrimaryContainer = Color(0xFF005046)
val DarkOnPrimaryContainer = Color(0xFF6FFAE1)

val DarkSecondary = Color(0xFF4DDBD1)
val DarkOnSecondary = Color(0xFF003733)
val DarkSecondaryContainer = Color(0xFF005049)
val DarkOnSecondaryContainer = Color(0xFF71FBEF)

val DarkTertiary = Color(0xFFE5C27E)
val DarkOnTertiary = Color(0xFF3A2500)
val DarkTertiaryContainer = Color(0xFF523400)
val DarkOnTertiaryContainer = Color(0xFFFFDEA9)

val DarkBackground = Color(0xFF041417)
val DarkOnBackground = Color(0xFFE0F7F1)
val DarkSurface = Color(0xFF061A1D)
val DarkOnSurface = Color(0xFFCFF5ED)
val DarkSurfaceVariant = Color(0xFF1B3B3E)
val DarkOnSurfaceVariant = Color(0xFFA4CFC9)
val DarkOutline = Color(0xFF3D5C5B)
val DarkOutlineVariant = Color(0xFF264240)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkInverseSurface = Color(0xFFE0F7F1)
val DarkInverseOnSurface = Color(0xFF041917)
val DarkInversePrimary = Color(0xFF006B5D)

val DarkGradientTop = Color(0xFF031619)
val DarkGradientBottom = Color(0xFF010B0E)
val DarkOverlay = Color(0x33000000)

data class PeerGradientColors(val top: Color, val bottom: Color, val overlay: Color)

val LightGradientColors = PeerGradientColors(
    top = LightGradientTop,
    bottom = LightGradientBottom,
    overlay = LightOverlay
)

val DarkGradientColors = PeerGradientColors(
    top = DarkGradientTop,
    bottom = DarkGradientBottom,
    overlay = DarkOverlay
)

