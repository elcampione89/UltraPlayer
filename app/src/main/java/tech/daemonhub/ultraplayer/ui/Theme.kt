package tech.daemonhub.ultraplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Purple = Color(0xFF7B3FFF)
val Lavender = Color(0xFFD9C8FF)
val Bg = Color(0xFF0F0B1A)
val Panel = Color(0xFF181226)
val PanelHigh = Color(0xFF231A38)
val Muted = Color(0xFFA59FB0)

private val colors = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Lavender,
    tertiary = Color(0xFFEFB8C8),
    background = Bg,
    onBackground = Color(0xFFECE6F0),
    surface = Panel,
    onSurface = Color(0xFFECE6F0),
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Muted,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = Color(0xFF2C2242),
    surfaceContainerLow = Color(0xFF130F1F),
    outline = Color(0xFF4A4458),
    error = Color(0xFFFFB4AB)
)

@Composable
fun UltraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
