package org.wyrdsekai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80CBC4),       // Teal
    onPrimary = Color(0xFF003731),
    secondary = Color(0xFFFFB74D),     // Amber
    onSecondary = Color(0xFF462900),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    error = Color(0xFFEF5350),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00796B),       // Teal
    onPrimary = Color.White,
    secondary = Color(0xFFF57C00),     // Amber
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF212121),
    surface = Color.White,
    onSurface = Color(0xFF000000),
    onSurfaceVariant = Color(0xFF424242),
    error = Color(0xFFD32F2F),
    onError = Color.White,
)

@Composable
fun WyrdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
