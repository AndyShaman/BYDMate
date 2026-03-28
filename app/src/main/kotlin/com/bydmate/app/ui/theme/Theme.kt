package com.bydmate.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    background = Color(0xFF0D0D0D),
    surface = Color(0xFF1E1E1E),
    primary = Color(0xFF4CAF50),
    secondary = Color(0xFF2196F3),
    error = Color(0xFFF44336),
    onBackground = Color.White,
    onSurface = Color.White,
    onPrimary = Color.White,
)

@Composable
fun BYDMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
