package com.yt.lite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFF0000),
            onPrimary = Color.White,
            background = Color(0xFF0F0F0F),
            surface = Color(0xFF1A1A1A),
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFAAAAAA),
        ),
        content = content
    )
}
