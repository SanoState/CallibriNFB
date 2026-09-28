package com.callibri.nfb.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CallibriColors = lightColorScheme(
    primary = Color(0xFF0E6B66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EBE7),
    onPrimaryContainer = Color(0xFF062E2C),
    secondary = Color(0xFF3E5C48),
    background = Color(0xFFF4F7F6),
    onBackground = Color(0xFF1A2422),
    surface = Color.White,
    onSurface = Color(0xFF1A2422),
    surfaceVariant = Color(0xFFE4EEEB),
    error = Color(0xFF9B2C2C),
    onError = Color.White,
)

@Composable
fun CallibriNfbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CallibriColors,
        content = content,
    )
}
