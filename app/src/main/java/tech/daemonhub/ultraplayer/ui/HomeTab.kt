package tech.daemonhub.ultraplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import tech.daemonhub.ultraplayer.data.CAT_FAVOURITES
import tech.daemonhub.ultraplayer.data.Catalog
import tech.daemonhub.ultraplayer.data.Item
import tech.daemonhub.ultraplayer.data.Kind
import tech.daemonhub.ultraplayer.data.Prefs
import tech.daemonhub.ultraplayer.data.Xtream
import tech.daemonhub.ultraplayer.player.PlayRequest
import tech.daemonhub.ultraplayer.player.PlayerActivity
import java.util.Calendar

private data class Resumable(val item: Item, val progress: Float, val label: String?)

/** The landing page: continue watching, favourite channels and what's new. */
@Composable
fun HomeTab() {
    val context = LocalContext.current
    val liveState by Catalog.state(Kind.LIVE).collectAsState()
    val movieState by Catalog.state(Kind.MOVIE).collectAsState()
    val seriesState by Catalog.state(Kind.SERIES).collectAsState()
    val favVersion by Catalog.favouritesVersion.collectAsState()
    var opened by remember { mutableStateOf<Item?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResume { resumeTick++ }

    // Live first (quick), then the big lists in the background
    LaunchedEffect(Unit) {
        Catalog.ensure(Kind.LIVE)
        Catalog.ensure(Kind.MOVIE)
        Catalog.ensure(Kind.SERIES)
    }

    val o = opened
    if (o != null) {
        BackHandler { opened = null; resumeTick++ }
        SearchDetail(o)
        return
    }

    val continueList = remember(movieState, seriesState, resumeTick) {
        val movies = Catalog.section(Kind.MOVIE)
        val series = Catalog.section(Kind.SERIES)
        Prefs.recentResumes(30).mapNotNull { (key, r) ->
            val p = key.split(":")
            val progress = if (r.durationMs > 0) r.positionMs.toFloat() / r.durationMs else 0f
            when {
                p.size == 2 && p[0] == "movie" -> movies?.byId?.get(p[1])?.let { Resumable(it, progress, null) }
                p.size == 3 && p[0] == "episode" -> series?.byId?.get(p[1])?.let { Resumable(it, progress, "Continue series") }
                else -> null
            }
        }.distinctBy { it.item.kind to it.item.id }
    }
    val favChannels = remember(liveState, favVersion) { Catalog.itemsIn(Kind.LIVE, CAT_FAVOURITES) }
    val newFilms = remember(movieState) { Catalog.recentlyAdded(Kind.MOVIE, 25) }
    val newSeries = remember(seriesState) { Catalog.recentlyAdded(Kind.SERIES, 25) }

    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { ScreenHeader(greeting, "What are we watching?") }

        if (continueList.isNotEmpty()) item {
            HomeRow("Continue watching") {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(continueList) { _, r -> PosterCard(r.item, r.progress) { opened = r.item } }
                }
            }
        }

        item {
            HomeRow("Favourite channels") {
                if (favChannels.isEmpty()) {
                    Text(
                        "Press ≡ (Menu) on any channel in Live TV to pin it here.",
                        color = Muted, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(favChannels) { _, ch ->
                            ChannelCard(ch) {
                                val login = Prefs.login ?: return@ChannelCard
                                PlayerActivity.start(
                                    context,
                                    PlayRequest(Xtream.liveUrl(login, ch), ch.name, PlayRequest.TYPE_LIVE, liveCategoryId = CAT_FAVOURITES, liveItemId = ch.id)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (newFilms.isNotEmpty()) item {
            HomeRow("New films") {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(newFilms) { _, film -> PosterCard(film, 0f) { opened = film } }
                }
            }
        }
        if (newSeries.isNotEmpty()) item {
            HomeRow("New series") {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(newSeries) { _, show -> PosterCard(show, 0f) { opened = show } }
                }
            }
        }
        if (newFilms.isEmpty() || newSeries.isEmpty()) item {
            Text("Loading films and series…", color = Muted, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HomeRow(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        content()
    }
}

@Composable
private fun PosterCard(item: Item, progress: Float, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.width(118.dp), focusedScale = 1.08f, color = Tile) { focused ->
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Tile)) {
                AsyncImage(item.icon, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (progress > 0f) LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = Lavender, trackColor = Tile
                )
            }
            Text(
                item.name, Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                maxLines = if (focused) 2 else 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun ChannelCard(ch: Item, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.width(170.dp), focusedScale = 1.06f, color = Panel) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Tile), contentAlignment = Alignment.Center) {
                AsyncImage(ch.icon, ch.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(14.dp))
            }
            Text(ch.name, Modifier.padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }
}
