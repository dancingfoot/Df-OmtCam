package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OmtDarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = DeepSpace,
    secondary = LiveRed,
    onSecondary = DeepSpace,
    background = DeepSpace,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateBorder,
    onSurfaceVariant = TextSecondary,
    error = LiveRed,
    onError = TextPrimary
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = OmtDarkColorScheme,
        typography = Typography,
        content = content
    )
}
