package com.peerchat.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3F2FD),
    onPrimaryContainer = Color(0xFF0D47A1),

    secondary = Color(0xFF03DAC6),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF004D40),

    tertiary = Color(0xFFCF93D9),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFFF3E5F5),
    onTertiaryContainer = Color(0xFF4A148C),

    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1B1F),

    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),

    surfaceTint = Color(0xFF1976D2),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),

    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCDCDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF001633),
    primaryContainer = Color(0xFF0B2D6B),
    onPrimaryContainer = Color(0xFFD8E2FF),

    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF2A4E4A),
    onSecondaryContainer = Color(0xFFAEEDE5),

    tertiary = Color(0xFFFFB4A9),
    onTertiary = Color(0xFF3B0905),
    tertiaryContainer = Color(0xFF5C1B14),
    onTertiaryContainer = Color(0xFFFFDAD3),

    background = Color(0xFF0B0E12),
    onBackground = Color(0xFFE2E7EE),

    surface = Color(0xFF0F1318),
    onSurface = Color(0xFFE2E7EE),
    surfaceVariant = Color(0xFF1A2129),
    onSurfaceVariant = Color(0xFFBFC6D0),

    surfaceTint = Color(0xFF8AB4FF),
    outline = Color(0xFF3A434F),
    outlineVariant = Color(0xFF242C35),

    error = Color(0xFFFF5449),
    onError = Color(0xFF370001),
    errorContainer = Color(0xFF8C1913),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun PeerChatTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PeerTypography,
        shapes = Shapes(
            extraSmall = Shapes().extraSmall,
            small = Shapes().small,
            medium = Shapes().medium,
            large = Shapes().large,
            extraLarge = Shapes().extraLarge
        ),
        content = content
    )
}


