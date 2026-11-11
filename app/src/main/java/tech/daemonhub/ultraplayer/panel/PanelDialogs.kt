package tech.daemonhub.ultraplayer.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import tech.daemonhub.ultraplayer.BuildConfig
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shown once per launch over the whole app: the panel's update pop-up first,
 * then any live system messages the device hasn't dismissed yet.
 */
@Composable
fun PanelStartupDialogs(showMessages: Boolean = true, onChecked: ((reachable: Boolean, updateAvailable: Boolean) -> Unit)? = null) {
    val context = LocalContext.current
    var update by remember { mutableStateOf<PanelUpdate?>(null) }
    var messages by remember { mutableStateOf<List<PanelMessage>>(emptyList()) }
    var appName by remember { mutableStateOf("Ultra Player") }

    LaunchedEffect(Unit) {
        val cfg = PanelClient.refresh(context)
        if (cfg == null) { onChecked?.invoke(false, false); return@LaunchedEffect }
        appName = cfg.appName
        update = cfg.update?.takeIf { it.isNewer }
        if (showMessages) messages = PanelClient.unseenMessages(context, cfg)
        onChecked?.invoke(true, update != null)
    }

    val pendingUpdate = update
    if (pendingUpdate != null) {
        UpdateDialog(appName, pendingUpdate, onLater = { update = null })
        return
    }

    val msg = messages.firstOrNull() ?: return
    MessageDialog(msg) {
        PanelClient.markSeen(context, msg.id)
        messages = messages.drop(1)
    }
}

@Composable
private fun UpdateDialog(appName: String, info: PanelUpdate, onLater: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableIntStateOf(-2) } // -2 idle, -1 unknown size, 0..100
    var downloaded by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun tryInstall(file: File) {
        if (PanelUpdater.canInstall(context)) {
            needsPermission = false
            runCatching { PanelUpdater.install(context, file) }
                .onFailure { error = "Couldn't open the installer: ${it.message}" }
        } else {
            needsPermission = true
            PanelUpdater.openInstallPermissionSettings(context)
        }
    }

    val busy = progress >= -1 && downloaded == null && error == null

    AlertDialog(
        onDismissRequest = { if (!info.force && !busy) onLater() },
        properties = DialogProperties(dismissOnBackPress = !info.force, dismissOnClickOutside = false),
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("Update available") },
        text = {
            Column {
                Text(
                    "$appName ${info.versionName.ifBlank { info.versionCode.toString() }} is ready " +
                        "(you have ${BuildConfig.VERSION_NAME}).",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (info.changelog.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        info.changelog,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(16.dp))
                    if (progress >= 0) {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("$progress%", style = MaterialTheme.typography.labelSmall)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (needsPermission) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Turn on \"Allow from this source\" for this app, come back, then press Install.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                modifier = Modifier.focusRequester(focus),
                onClick = {
                    val file = downloaded
                    if (file != null) {
                        tryInstall(file)
                    } else {
                        error = null
                        progress = -1
                        scope.launch {
                            try {
                                val f = PanelUpdater.download(context, info.bestUrl()) { progress = it }
                                downloaded = f
                                tryInstall(f)
                            } catch (e: Exception) {
                                error = e.message ?: "Download failed"
                                progress = -2
                            }
                        }
                    }
                }
            ) {
                Text(
                    when {
                        downloaded != null -> "Install"
                        error != null -> "Retry"
                        else -> "Update now"
                    }
                )
            }
        },
        dismissButton = if (info.force) null else {
            { TextButton(enabled = !busy, onClick = onLater) { Text("Later") } }
        }
    )

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun MessageDialog(msg: PanelMessage, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Default.Campaign, contentDescription = null) },
        title = if (msg.title.isNotBlank()) { { Text(msg.title) } } else null,
        text = {
            Text(
                msg.body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            Button(onClick = onClose, modifier = Modifier.focusRequester(focus)) { Text("OK") }
        }
    )
    LaunchedEffect(msg.id) { runCatching { focus.requestFocus() } }
}
