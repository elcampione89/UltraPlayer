package tech.daemonhub.ultraplayer.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
import tech.daemonhub.ultraplayer.player.Players
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
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
                    CircularProgressIndicator(color = Lavender)
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
    var resumeTick by remember { mutableIntStateOf(0) }

    // Small live preview of the highlighted channel. Stopped before going full screen.
    val preview = remember { Players.create(context) }
    DisposableEffect(Unit) { onDispose { preview.release() } }
    LifecycleResume { resumeTick++ }
    LaunchedEffect(focusedChannel?.id, resumeTick) {
        val ch = focusedChannel ?: return@LaunchedEffect
        val login = Prefs.login ?: return@LaunchedEffect
        preview.stop()
        delay(900) // only start once the remote has settled on a channel
        preview.setMediaItem(MediaItem.fromUri(Xtream.liveUrl(login, ch)))
        preview.prepare()
        preview.play()
    }

    fun openFullScreen(ch: Item) {
        val login = Prefs.login ?: return
        preview.stop()
        PlayerActivity.start(
            context,
            PlayRequest(Xtream.liveUrl(login, ch), ch.name, PlayRequest.TYPE_LIVE, liveCategoryId = selectedCat, liveItemId = ch.id)
        )
    }

    Column(Modifier.fillMaxSize().padding(top = 16.dp, bottom = 16.dp, end = 16.dp)) {
        ScreenHeader("Live TV", categories.firstOrNull { it.id == selectedCat }?.name?.let { "$it · ${channels.size} channels" })
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxSize()) {
            // Categories
            LazyColumn(
                Modifier.width(210.dp).fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(categories) { _, c ->
                    FocusBox(
                        onClick = { selectedCat = c.id; Prefs.lastLiveCategory = c.id },
                        onFocused = { selectedCat = c.id; Prefs.lastLiveCategory = c.id },
                        selected = c.id == selectedCat,
                        modifier = Modifier.fillMaxWidth(),
                        focusedScale = 1.0f,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            c.name,
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (c.id == selectedCat) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Channels
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                state = channelListState,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(channels) { _, ch ->
                    val isFav = remember(favVersion, ch.id) { Prefs.isFavourite("live", ch.id) }
                    FocusBox(
                        onClick = { openFullScreen(ch) },
                        onMenu = {
                            val added = Prefs.toggleFavourite("live", ch.id)
                            Catalog.favouritesVersion.value++
                            Toast.makeText(context, if (added) "Added to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show()
                        },
                        onFocused = { focusedChannel = ch },
                        modifier = Modifier.fillMaxWidth(),
                        focusedScale = 1.0f,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (ch.num > 0) "${ch.num}" else "",
                                color = Muted, modifier = Modifier.width(40.dp), maxLines = 1,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Box(
                                Modifier.size(width = 46.dp, height = 28.dp).clip(RoundedCornerShape(5.dp)).background(Tile),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(ch.icon, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(ch.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            if (isFav) Icon(Icons.Default.Star, "Favourite", tint = Lavender, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            // Preview + guide
            Column(Modifier.width(300.dp).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Panel)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                isFocusable = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                player = preview
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (focusedChannel == null) Text("Preview", color = Muted)
                }
                Column(Modifier.padding(14.dp)) {
                    val ch = focusedChannel
                    if (ch == null) {
                        Text("Pick a channel", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("OK: watch full screen\n≡ Menu: add to favourites\nWhile watching: up/down changes channel", color = Muted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        ChannelGuide(ch)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelGuide(ch: Item) {
    var epg by remember(ch.id) { mutableStateOf<List<EpgEntry>?>(null) }
    LaunchedEffect(ch.id) {
        delay(350) // don't fetch for every channel the remote passes over
        val login = Prefs.login
        epg = if (login != null && login.isXtream) Xtream.shortEpg(login, ch.id, 4) else emptyList()
    }
    Text(ch.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(8.dp))
    val list = epg
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    when {
        list == null -> Text("Loading guide…", color = Muted, style = MaterialTheme.typography.bodySmall)
        list.isEmpty() -> Text("No guide for this channel", color = Muted, style = MaterialTheme.typography.bodySmall)
        else -> list.take(3).forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${fmt.format(Date(e.startMs))}–${fmt.format(Date(e.endMs))}",
                    color = if (i == 0) Lavender else Muted, style = MaterialTheme.typography.labelMedium
                )
                if (i == 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("NOW", color = Lavender, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                }
            }
            Text(
                e.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium
            )
            if (i == 0) {
                val now = System.currentTimeMillis()
                val p = if (e.endMs > e.startMs) ((now - e.startMs).toFloat() / (e.endMs - e.startMs)).coerceIn(0f, 1f) else 0f
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { p }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Lavender, trackColor = Tile
                )
                if (!e.description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(e.description, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
