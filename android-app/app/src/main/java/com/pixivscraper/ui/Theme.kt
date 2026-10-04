package com.pixivscraper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FB6FF),
    secondary = Color(0xFF9CC9F5),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6BCB),
    secondary = Color(0xFF4A7DA8),
)

@Composable
fun PixivScraperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
