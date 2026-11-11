package tech.daemonhub.ultraplayer.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.daemonhub.ultraplayer.data.Catalog
import tech.daemonhub.ultraplayer.data.EpgEntry
import tech.daemonhub.ultraplayer.data.Item
import tech.daemonhub.ultraplayer.data.Kind
import tech.daemonhub.ultraplayer.data.LoadState
import tech.daemonhub.ultraplayer.data.Prefs
import tech.daemonhub.ultraplayer.data.Xtream
import tech.daemonhub.ultraplayer.player.PlayRequest
import tech.daemonhub.ultraplayer.player.PlayerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Catalog.state(Kind.LIVE).collectAsState()
    val favVersion by Catalog.favouritesVersion.collectAsState()

    LaunchedEffect(Unit) { Catalog.ensure(Kind.LIVE) }

    when (val st = state) {
        LoadState.Idle, LoadState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading channels…", color = Muted)
                }
            }
            return
        }
        is LoadState.Failed -> {
            CenterMessage("Couldn't load channels", st.message, "Try again") { scope.launch { Catalog.ensure(Kind.LIVE, force = true) } }
            return
        }
        LoadState.Ready -> Unit
    }

    val categories = remember(state, favVersion) { Catalog.visibleCategories(Kind.LIVE) }
    if (categories.isEmpty()) { CenterMessage("No channels", "Check your category rules in the panel"); return }

    var selectedCat by remember {
        mutableStateOf(Prefs.lastLiveCategory?.takeIf { id -> categories.any { it.id == id } } ?: categories.first().id)
    }
    val channels = remember(selectedCat, favVersion, state) { Catalog.itemsIn(Kind.LIVE, selectedCat) }
    var focusedChannel by remember { mutableStateOf<Item?>(null) }
    val channelListState = remember(selectedCat) { LazyListState() }

    Row(Modifier.fillMaxSize().padding(top = 20.dp, bottom = 20.dp, end = 20.dp)) {
        // Categories
        LazyColumn(
            Modifier.width(230.dp).fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(categories) { _, c ->
                FocusBox(
                    onClick = { selectedCat = c.id; Prefs.lastLiveCategory = c.id },
                    onFocused = { selectedCat = c.id; Prefs.lastLiveCategory = c.id },
                    selected = c.id == selectedCat,
                    modifier = Modifier.fillMaxWidth(),
                    focusedScale = 1.02f
                ) {
                    Text(
                        c.name,
                        Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (c.id == selectedCat) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // Channels
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            state = channelListState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(channels) { _, ch ->
                val isFav = remember(favVersion, ch.id) { Prefs.isFavourite("live", ch.id) }
                FocusBox(
                    onClick = {
                        val login = Prefs.login ?: return@FocusBox
                        PlayerActivity.start(
                            context,
                            PlayRequest(
                                url = Xtream.liveUrl(login, ch),
                                title = ch.name,
                                type = PlayRequest.TYPE_LIVE,
                                liveCategoryId = selectedCat,
                                liveItemId = ch.id
                            )
                        )
                    },
                    onMenu = {
                        val added = Prefs.toggleFavourite("live", ch.id)
                        Catalog.favouritesVersion.value++
                        Toast.makeText(context, if (added) "Added to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show()
                    },
                    onFocused = { focusedChannel = ch },
                    modifier = Modifier.fillMaxWidth(),
                    focusedScale = 1.02f,
                    color = Panel
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ch.num > 0) {
                            Text("${ch.num}", color = Muted, modifier = Modifier.width(44.dp), maxLines = 1)
                        }
                        AsyncImage(
                            model = ch.icon,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(width = 56.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)).background(Tile)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(ch.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isFav) Icon(Icons.Default.Star, "Favourite", tint = Lavender, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Info / guide panel
        Column(
            Modifier.width(260.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(Panel).padding(18.dp)
        ) {
            val ch = focusedChannel
            if (ch == null) {
                Text("Pick a channel", color = Muted)
                Spacer(Modifier.height(8.dp))
                Text("OK to watch · ≡ to add to favourites\nWhile watching: up/down to change channel", color = Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                ChannelInfo(ch)
            }
        }
    }
}


@Composable
private fun ChannelInfo(ch: Item) {
    var epg by remember(ch.id) { mutableStateOf<List<EpgEntry>?>(null) }
    LaunchedEffect(ch.id) {
        delay(350) // don't fetch for every channel the remote passes over
        val login = Prefs.login
        epg = if (login != null && login.isXtream) Xtream.shortEpg(login, ch.id, 4) else emptyList()
    }
    AsyncImage(
        model = ch.icon, contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(Tile)
    )
    Spacer(Modifier.height(12.dp))
    Text(ch.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
    Spacer(Modifier.height(12.dp))
    val list = epg
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    when {
        list == null -> Text("Loading guide…", color = Muted, style = MaterialTheme.typography.bodySmall)
        list.isEmpty() -> Text("No guide for this channel", color = Muted, style = MaterialTheme.typography.bodySmall)
        else -> list.forEachIndexed { i, e ->
            Text(
                "${fmt.format(Date(e.startMs))}  ${if (i == 0) "NOW" else ""}",
                color = if (i == 0) Lavender else Muted,
                style = MaterialTheme.typography.labelMedium
            )
            Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal)
            if (i == 0 && !e.description.isNullOrBlank()) {
                Text(e.description, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
