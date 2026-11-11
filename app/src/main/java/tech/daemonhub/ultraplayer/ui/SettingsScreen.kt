package tech.daemonhub.ultraplayer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.daemonhub.ultraplayer.BuildConfig
import tech.daemonhub.ultraplayer.data.Catalog
import tech.daemonhub.ultraplayer.data.Kind
import tech.daemonhub.ultraplayer.data.Prefs
import tech.daemonhub.ultraplayer.data.Xtream
import tech.daemonhub.ultraplayer.panel.PanelClient
import tech.daemonhub.ultraplayer.panel.PanelStartupDialogs

@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by PanelClient.config.collectAsState()
    val login = Prefs.login
    var confirmLogout by remember { mutableStateOf(false) }
    var updateCheck by remember { mutableIntStateOf(0) }

    // The server's address is never shown: only the name it has in your panel
    val serverName = login?.let { l ->
        if (!l.isXtream) "M3U playlist"
        else cfg.servers.firstOrNull { it.url.trimEnd('/').equals(l.server, ignoreCase = true) }?.name ?: "Custom server"
    } ?: "-"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(4.dp))
        SettingRow(Icons.Default.Dns, "Server", serverName) {}
        if (login?.isXtream == true) SettingRow(Icons.Default.Person, "Account", login?.username ?: "") {}
        if (cfg.allowThemeChange) {
            val current = Themes.current
            SettingRow(Icons.Default.Palette, "Theme: ${current.label}", "Press OK to switch look") {
                val all = Themes.all
                val next = all[(all.indexOfFirst { it.id == current.id } + 1) % all.size]
                Themes.choose(next.id)
            }
        }
        SettingRow(Icons.Default.Refresh, "Refresh channels, films and series", "Downloads fresh lists from your provider") {
            Xtream.clearCache()
            Catalog.reset()
            scope.launch { Catalog.ensure(Kind.LIVE, force = true) }
            Toast.makeText(context, "Refreshing… open each tab to reload it", Toast.LENGTH_SHORT).show()
        }
        SettingRow(Icons.Default.SystemUpdate, "Check for updates", "You have ${BuildConfig.VERSION_NAME}") { updateCheck++ }
        SettingRow(Icons.Default.Info, "About", "Ultra Player ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") {}
        SettingRow(Icons.Default.Logout, "Sign out", "Return to the login screen") { confirmLogout = true }
    }

    if (updateCheck > 0) key(updateCheck) {
        PanelStartupDialogs(showMessages = false) { reachable, available ->
            if (!available) Toast.makeText(
                context,
                if (reachable) "You're on the latest version" else "Couldn't reach the update server",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = { Text("You'll need your username and password to sign back in.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f, color = Panel) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Lavender)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
