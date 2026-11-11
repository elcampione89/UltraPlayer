package tech.daemonhub.ultraplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tech.daemonhub.ultraplayer.R
import tech.daemonhub.ultraplayer.data.Login
import tech.daemonhub.ultraplayer.data.Prefs
import tech.daemonhub.ultraplayer.data.Xtream
import tech.daemonhub.ultraplayer.panel.PanelClient

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val cfg by PanelClient.config.collectAsState()
    val scope = rememberCoroutineScope()
    var useM3u by remember { mutableStateOf(false) }
    var serverIndex by remember { mutableStateOf(0) }       // index into cfg.servers, or -1 for custom
    var customUrl by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var m3u by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstField = remember { FocusRequester() }

    val servers = cfg.servers
    val useCustom = servers.isEmpty() || serverIndex < 0
    if (!cfg.allowM3u && useM3u) useM3u = false

    fun submit() {
        error = null
        if (useM3u) {
            val url = m3u.trim()
            if (!url.startsWith("http")) { error = "Enter the full playlist link (starting http)"; return }
            Prefs.login = Login("m3u", "", "", "", url)
            onLoggedIn()
            return
        }
        val server = (if (useCustom) customUrl else servers.getOrNull(serverIndex)?.url ?: "").trim().trimEnd('/')
            .let { if (it.isNotEmpty() && !it.startsWith("http")) "http://$it" else it }
        if (server.isBlank() || user.isBlank() || pass.isBlank()) { error = "Fill in every box"; return }
        val login = Login("xtream", server, user.trim(), pass.trim(), "")
        busy = true
        scope.launch {
            try {
                Xtream.login(login)
                Prefs.login = login
                onLoggedIn()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't sign in"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(Modifier.padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painterResource(R.drawable.ic_app_logo), null,
                    Modifier.size(120.dp).clip(RoundedCornerShape(28.dp))
                )
                Spacer(Modifier.height(16.dp))
                Text("Ultra Player", fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text("Sign in to start watching", color = Muted)
            }
            Spacer(Modifier.width(48.dp))
            Column(
                Modifier.width(440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (cfg.allowM3u) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("Username & password", !useM3u, Modifier.focusRequester(firstField)) { useM3u = false }
                        Chip("M3U link", useM3u) { useM3u = true }
                    }
                }
                if (useM3u) {
                    Field("Playlist link", m3u, { m3u = it }, KeyboardType.Uri, ImeAction.Done, onDone = { submit() })
                } else {
                    if (servers.isNotEmpty()) {
                        Text("Server", color = Muted, style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            servers.forEachIndexed { i, s ->
                                Chip(s.name, serverIndex == i, if (i == 0 && !cfg.allowM3u) Modifier.focusRequester(firstField) else Modifier) { serverIndex = i }
                            }
                            if (cfg.allowCustomDns) Chip("Other", serverIndex < 0) { serverIndex = -1 }
                        }
                    }
                    if (useCustom) {
                        Field(
                            "Server address", customUrl, { customUrl = it }, KeyboardType.Uri, ImeAction.Next,
                            modifier = if (servers.isEmpty() && !cfg.allowM3u) Modifier.focusRequester(firstField) else Modifier
                        )
                    }
                    Field("Username", user, { user = it }, KeyboardType.Text, ImeAction.Next)
                    Field("Password", pass, { pass = it }, KeyboardType.Password, ImeAction.Done, password = true, onDone = { submit() })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                FocusBox(
                    onClick = { if (!busy) submit() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    color = MaterialTheme.colorScheme.primary,
                    focusedColor = FocusBg,
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        else Text(if (useM3u) "Load playlist" else "Sign in", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(
        onClick = onClick,
        modifier = modifier,
        selected = selected,
        color = Panel,
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    type: KeyboardType,
    ime: ImeAction,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    onDone: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ime),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().dpadVertical()
    )
}
