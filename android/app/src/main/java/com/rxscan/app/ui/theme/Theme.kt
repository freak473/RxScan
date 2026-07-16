package com.rxscan.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val RxColorScheme = lightColorScheme(
    primary = Green,
    onPrimary = White,
    primaryContainer = GreenSoft,
    onPrimaryContainer = Green900,
    secondary = Ink,
    onSecondary = White,
    background = Paper,
    onBackground = TextPrimary,
    surface = Paper,
    onSurface = TextPrimary,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = Muted,
    error = RxRed,
    outline = PaperLine,
)

@Composable
fun RxScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RxColorScheme,
        typography = Typography,
        content = content,
    )
}
