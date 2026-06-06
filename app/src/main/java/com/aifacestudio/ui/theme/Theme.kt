package com.aifacestudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioLightScheme = lightColorScheme(
    primary = Color(0xFF0E7C76),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F3EF),
    onPrimaryContainer = Color(0xFF063A37),
    secondary = Color(0xFFB84B3E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD4),
    onSecondaryContainer = Color(0xFF4A0E08),
    tertiary = Color(0xFF7B5F00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE490),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFBFCF8),
    onBackground = Color(0xFF1B1D1B),
    surface = Color(0xFFFBFCF8),
    onSurface = Color(0xFF1B1D1B),
    surfaceVariant = Color(0xFFE0E5DE),
    onSurfaceVariant = Color(0xFF434A45),
    outline = Color(0xFF737A74),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

@Composable
fun AiFaceStudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioLightScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
