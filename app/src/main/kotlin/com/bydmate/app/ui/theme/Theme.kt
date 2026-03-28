package com.bydmate.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    background = NavyDark,
    surface = CardSurface,
    surfaceVariant = CardSurfaceElevated,
    primary = AccentGreen,
    secondary = AccentBlue,
    tertiary = AccentTeal,
    error = SocRed,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onPrimary = NavyDark,
    onSecondary = NavyDark,
    outline = CardBorder,
    surfaceTint = Color.Transparent
)

@Composable
fun BYDMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = BYDMateTypography,
        content = content
    )
}
