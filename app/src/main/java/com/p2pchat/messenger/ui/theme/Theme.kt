package com.p2pchat.messenger.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * P2P Chat brand theme: dark charcoal surfaces, electric cyan primary,
 * green secondary/tertiary accents. Dark-only by design — the mesh UI is
 * meant to read clearly outdoors and at night on relay phones.
 */
private val P2PDarkColors = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00262B),
    primaryContainer = Color(0xFF004D57),
    onPrimaryContainer = Color(0xFFB2F5FF),
    secondary = Color(0xFF00E676),
    onSecondary = Color(0xFF00331A),
    secondaryContainer = Color(0xFF00512A),
    onSecondaryContainer = Color(0xFFB9F6D3),
    tertiary = Color(0xFF00E676),
    onTertiary = Color(0xFF00331A),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB8B8B8),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFCF6679),
    onError = Color(0xFF1A0006)
)

@Composable
fun P2PChatTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = P2PDarkColors.background.toArgb()
            window.navigationBarColor = P2PDarkColors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = P2PDarkColors,
        typography = P2PTypography,
        content = content
    )
}
