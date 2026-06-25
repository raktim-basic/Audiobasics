package com.rkd.audiobasics.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import android.os.Build
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFFFF0000),
    onPrimary = Color.White,
    secondary = Color(0xFF1A1A1A),
    onSecondary = Color.White,
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF666666),
    error = Color(0xFFB00020),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF0000),
    onPrimary = Color.White,
    secondary = Color(0xFFEEEEEE),
    onSecondary = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFAAAAAA),
    error = Color(0xFFCF6679),
    onError = Color.Black,
)

@Composable
fun AppTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            val controller = WindowCompat.getInsetsController(window, view)

            // Status bar icon color — dark icons on light, light icons on dark
            controller.isAppearanceLightStatusBars = !darkTheme

            // On Android 14 and below, set bar colors manually.
            // Android 15+ enforces edge-to-edge and handles this automatically.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                window.statusBarColor = if (darkTheme) 0xFF121212.toInt() else 0xFFF5F5F5.toInt()
                @Suppress("DEPRECATION")
                window.navigationBarColor = if (darkTheme) 0xFF1E1E1E.toInt() else 0xFFE8E8E8.toInt()
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
