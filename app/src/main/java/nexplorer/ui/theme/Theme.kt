package nexplorer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AccentBlue = Color(0xFF2F80ED)
val IconRed = Color(0xFFE5484D)

private val Light = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFF1F1F1),
    onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF7F7F7),
    surfaceContainerLow = Color(0xFFFFFFFF),
    outlineVariant = Color(0xFFE5E5E5),
    error = IconRed,
)

val Eggshell = Color(0xFFF0EAD6)

private val Dark = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    background = Color(0xFF121212),
    onBackground = Eggshell,
    surface = Color(0xFF121212),
    onSurface = Eggshell,
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1E1E1E),
    surfaceContainerLow = Color(0xFF121212),
    outlineVariant = Color(0xFF2A2A2A),
    error = IconRed,
)

@Composable
fun NExplorerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
