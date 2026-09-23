package com.bydmate.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

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

/** Multiplies only the sp scale; dp sizes stay put. Returns [base] itself for 1f. */
internal fun scaledDensity(base: Density, fontScale: Float): Density =
    if (fontScale == 1f) base else Density(base.density, base.fontScale * fontScale)

/** In-app text size multiplier, so nested windows (dialogs, popups) can re-apply it. */
val LocalAppFontScale = compositionLocalOf { 1f }

/**
 * Applies the in-app text size setting on top of the system font scale.
 * Always the same group (no branch on 1f): switching the setting must not remount the subtree.
 */
@Composable
fun WithAppFontScale(fontScale: Float, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDensity provides scaledDensity(LocalDensity.current, fontScale),
        content = content
    )
}

/**
 * Dialog and popup windows re-provide LocalDensity from their own window, dropping the
 * theme-level override; wrap their content in this to re-apply the setting.
 */
@Composable
fun ScaledDialogContent(content: @Composable () -> Unit) =
    WithAppFontScale(LocalAppFontScale.current, content)

@Composable
fun BYDMateTheme(fontScale: Float = 1f, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppFontScale provides fontScale) {
        WithAppFontScale(fontScale) {
            MaterialTheme(
                colorScheme = DarkColorScheme,
                typography = BYDMateTypography,
                content = content
            )
        }
    }
}
