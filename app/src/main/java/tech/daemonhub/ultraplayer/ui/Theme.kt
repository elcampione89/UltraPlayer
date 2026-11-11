package tech.daemonhub.ultraplayer.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import tech.daemonhub.ultraplayer.data.Prefs
import tech.daemonhub.ultraplayer.panel.PanelClient

/** One complete look. Text on [focus] and [selected] is always white, so it stays readable. */
data class Palette(
    val id: String,
    val label: String,
    val bg: Color,
    val rail: Color,
    val panel: Color,
    val panelHigh: Color,
    val tile: Color,
    val focus: Color,      // background of the item the remote is on
    val selected: Color,   // background of the chosen category/tab when not focused
    val accent: Color,     // bright accent for icons, "NOW", subtitles
    val text: Color,
    val muted: Color,
    val primary: Color     // buttons, progress bars
)

object Themes {
    val all = listOf(
        Palette("purple", "Purple", Color(0xFF0B0814), Color(0xFF100B1C), Color(0xFF19122A), Color(0xFF261C3F), Color(0xFF221A36),
            focus = Color(0xFF6A3FE0), selected = Color(0xFF34265C), accent = Color(0xFFD9C8FF),
            text = Color(0xFFFFFFFF), muted = Color(0xFFC8C1D8), primary = Color(0xFF8E63FF)),
        Palette("gold", "Black & gold", Color(0xFF080808), Color(0xFF0F0F0F), Color(0xFF181818), Color(0xFF262626), Color(0xFF202020),
            focus = Color(0xFF946A00), selected = Color(0xFF3A2D0A), accent = Color(0xFFFFC857),
            text = Color(0xFFFFFFFF), muted = Color(0xFFD0CCC4), primary = Color(0xFFC79200)),
        Palette("red", "Black & red", Color(0xFF080808), Color(0xFF0F0F0F), Color(0xFF181818), Color(0xFF262626), Color(0xFF202020),
            focus = Color(0xFFC4161C), selected = Color(0xFF3D0D10), accent = Color(0xFFFF7A7A),
            text = Color(0xFFFFFFFF), muted = Color(0xFFD2CCCC), primary = Color(0xFFE5252C)),
        Palette("blue", "Navy & cyan", Color(0xFF050D1A), Color(0xFF081426), Color(0xFF0E1D35), Color(0xFF17304F), Color(0xFF13263F),
            focus = Color(0xFF0A7FA6), selected = Color(0xFF103C5A), accent = Color(0xFF7FE3FF),
            text = Color(0xFFFFFFFF), muted = Color(0xFFC3D2E0), primary = Color(0xFF14A3D4))
    )

    fun byId(id: String?): Palette = all.firstOrNull { it.id == id } ?: all.first()

    /** The panel picks the default; users can override it in Settings if the panel allows. */
    var userChoice by mutableStateOf(Prefs.themeId)
        private set

    fun choose(id: String) {
        Prefs.themeId = id
        userChoice = id
    }

    val current: Palette
        get() {
            val cfg = PanelClient.config.value
            return if (cfg.allowThemeChange && userChoice != null) byId(userChoice) else byId(cfg.theme)
        }
}

// Shortcuts used throughout the UI. They read the current palette, so a theme change recolours everything.
val Bg: Color get() = Themes.current.bg
val Rail: Color get() = Themes.current.rail
val Panel: Color get() = Themes.current.panel
val PanelHigh: Color get() = Themes.current.panelHigh
val Tile: Color get() = Themes.current.tile
val FocusBg: Color get() = Themes.current.focus
val SelectedBg: Color get() = Themes.current.selected
val Lavender: Color get() = Themes.current.accent
val Muted: Color get() = Themes.current.muted
val TextMain: Color get() = Themes.current.text

@Composable
fun UltraTheme(content: @Composable () -> Unit) {
    // Read the panel config as state so a panel theme change recolours the app
    val cfg by PanelClient.config.collectAsState()
    val p = if (cfg.allowThemeChange && Themes.userChoice != null) Themes.byId(Themes.userChoice) else Themes.byId(cfg.theme)
    val scheme = darkColorScheme(
        primary = p.primary,
        onPrimary = Color.White,
        primaryContainer = p.focus,
        onPrimaryContainer = Color.White,
        secondary = p.accent,
        tertiary = p.accent,
        background = p.bg,
        onBackground = p.text,
        surface = p.panel,
        onSurface = p.text,
        surfaceVariant = p.panelHigh,
        onSurfaceVariant = p.muted,
        surfaceContainer = p.panel,
        surfaceContainerHigh = p.panelHigh,
        surfaceContainerHighest = p.tile,
        surfaceContainerLow = p.rail,
        outline = p.muted.copy(alpha = 0.5f),
        error = Color(0xFFFFB4AB)
    )
    val density = LocalDensity.current
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        CompositionLocalProvider(
            LocalContentColor provides p.text,
            // Slightly larger text: it's read from the sofa
            LocalDensity provides Density(density.density, density.fontScale * 1.12f)
        ) {
            // Rebuild the screen when the theme changes so every colour updates
            key(p.id) { content() }
        }
    }
}
