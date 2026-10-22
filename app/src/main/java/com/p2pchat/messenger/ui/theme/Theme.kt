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
 * P2P Chat brand theme (v1.1.0): deep navy surfaces with the v3 logo's
 * azure → indigo accent direction — azure primary, indigo secondary,
 * deep-azure container for outgoing chat bubbles. Dark-only by design —
 * the mesh UI is meant to read clearly outdoors and at night on relay
 * phones.
 */
private val P2PDarkColors = darkColorScheme(
    primary = Color(0xFF4DB8F5),
    onPrimary = Color(0xFF06283D),
    primaryContainer = Color(0xFF14507A),
    onPrimaryContainer = Color(0xFFD8EFFF),
    secondary = Color(0xFF7C8CF8),
    onSecondary = Color(0xFF141A3D),
    secondaryContainer = Color(0xFF2A3170),
    onSecondaryContainer = Color(0xFFDDE1FF),
    tertiary = Color(0xFF7C8CF8),
    onTertiary = Color(0xFF141A3D),
    background = Color(0xFF0B0F1C),
    onBackground = Color(0xFFF2F4FA),
    surface = Color(0xFF131A2C),
    onSurface = Color(0xFFF2F4FA),
    surfaceVariant = Color(0xFF1D2742),
    onSurfaceVariant = Color(0xFF9AA7C7),
    outline = Color(0xFF2A3554),
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
