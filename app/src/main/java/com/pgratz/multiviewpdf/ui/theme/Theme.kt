package com.pgratz.multiviewpdf.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark by default: on the glasses, black is transparent and bright chrome is distracting.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF2B1700),
    primaryContainer = Color(0xFF5C3B00),
    onPrimaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF000000),
    surface = Color(0xFF141414),
)

@Composable
fun MultiViewPdfTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
