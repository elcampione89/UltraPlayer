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
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    val tabs = Tab.values().filter { it != Tab.PLEX || cfg.plexEnabled }
    if (tab !in tabs) tab = Tab.LIVE

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
                .background(Color0)
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
                        Icon(t.icon, null, tint = if (tab == t || focused) Lavender else Muted)
                        Spacer(Modifier.width(12.dp))
                        Text(t.label, fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (tab) {
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

private val Color0 = androidx.compose.ui.graphics.Color(0xFF130E20)

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
