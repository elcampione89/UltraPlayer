package tech.daemonhub.ultraplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.daemonhub.ultraplayer.R
import tech.daemonhub.ultraplayer.data.Kind
import tech.daemonhub.ultraplayer.panel.PanelClient
import tech.daemonhub.ultraplayer.plex.PlexScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    LIVE("Live TV", Icons.Default.LiveTv),
    MOVIES("Films", Icons.Default.Movie),
    SERIES("Series", Icons.Default.Tv),
    PLEX("Plex", Icons.Default.VideoLibrary),
    SEARCH("Search", Icons.Default.Search),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun HomeScreen(onLogout: () -> Unit) {
    val cfg by PanelClient.config.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val tabs = Tab.values().filter { it != Tab.PLEX || cfg.plexEnabled }
    if (tab !in tabs) tab = Tab.HOME

    var railHasFocus by remember { mutableStateOf(true) }
    val railFocus = remember { FocusRequester() }

    // Back from anywhere in the content jumps to the menu; Back on the menu leaves the app
    BackHandler(enabled = !railHasFocus) { runCatching { railFocus.requestFocus() } }
    LaunchedEffect(Unit) { runCatching { railFocus.requestFocus() } }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(180.dp)
                .fillMaxHeight()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Rail, Bg)))
                .padding(vertical = 20.dp, horizontal = 12.dp)
                .onFocusChanged { railHasFocus = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 18.dp)) {
                Image(painterResource(R.drawable.ic_app_logo), null, Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("ULTRA", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
                    Text("PLAYER", color = Lavender, style = MaterialTheme.typography.labelSmall)
                }
            }
            tabs.forEach { t ->
                FocusBox(
                    onClick = { tab = t },
                    selected = tab == t,
                    modifier = Modifier.fillMaxWidth().let { if (t == tab) it.focusRequester(railFocus) else it },
                    focusedScale = 1.02f,
                    shape = RoundedCornerShape(16.dp)
                ) { focused ->
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon, null, tint = if (focused) androidx.compose.ui.graphics.Color.White else if (tab == t) Lavender else Muted)
                        Spacer(Modifier.width(12.dp))
                        Text(t.label, fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(SelectedBg.copy(alpha = 0.35f), Bg), radius = 1400f))
        ) {
            when (tab) {
                Tab.HOME -> HomeTab()
                Tab.LIVE -> LiveScreen()
                Tab.MOVIES -> VodScreen(Kind.MOVIE)
                Tab.SERIES -> VodScreen(Kind.SERIES)
                Tab.PLEX -> PlexScreen()
                Tab.SEARCH -> SearchScreen()
                Tab.SETTINGS -> SettingsScreen(onLogout)
            }
        }
    }
}


/** Shared "loading / failed / empty" placeholder. */
@Composable
fun CenterMessage(text: String, sub: String? = null, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.titleMedium)
            if (sub != null) {
                Spacer(Modifier.height(6.dp))
                Text(sub, color = Muted)
            }
            if (actionLabel != null) {
                Spacer(Modifier.height(16.dp))
                Chip(actionLabel, false, onClick = onAction)
            }
        }
    }
}

/** Screen title on the left, live clock on the right. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val dayFmt = remember { java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            if (subtitle != null) Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmt.format(java.util.Date(now)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(dayFmt.format(java.util.Date(now)), color = Muted, style = MaterialTheme.typography.labelMedium)
        }
    }
}
