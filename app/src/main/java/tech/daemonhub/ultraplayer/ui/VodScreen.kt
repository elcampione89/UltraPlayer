package tech.daemonhub.ultraplayer.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.daemonhub.ultraplayer.data.Catalog
import tech.daemonhub.ultraplayer.data.Episode
import tech.daemonhub.ultraplayer.data.Item
import tech.daemonhub.ultraplayer.data.Kind
import tech.daemonhub.ultraplayer.data.LoadState
import tech.daemonhub.ultraplayer.data.Prefs
import tech.daemonhub.ultraplayer.data.SeriesInfo
import tech.daemonhub.ultraplayer.data.VodInfo
import tech.daemonhub.ultraplayer.data.Xtream
import tech.daemonhub.ultraplayer.player.PlayRequest
import tech.daemonhub.ultraplayer.player.PlayerActivity


/** Films or Series: categories on the left, posters on the right, details on OK. */
@Composable
fun VodScreen(kind: Kind) {
    val scope = rememberCoroutineScope()
    val state by Catalog.state(kind).collectAsState()
    val favVersion by Catalog.favouritesVersion.collectAsState()
    var openItem by remember(kind) { mutableStateOf<Item?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val label = if (kind == Kind.MOVIE) "films" else "series"

    LaunchedEffect(kind) { Catalog.ensure(kind) }

    val detail = openItem
    if (detail != null) {
        BackHandler { openItem = null; refreshTick++ }
        if (kind == Kind.MOVIE) MovieDetail(detail) else SeriesDetail(detail)
        return
    }

    when (val st = state) {
        LoadState.Idle, LoadState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading $label… the first load can take a minute", color = Muted)
                }
            }
            return
        }
        is LoadState.Failed -> {
            CenterMessage("Couldn't load $label", st.message, "Try again") { scope.launch { Catalog.ensure(kind, force = true) } }
            return
        }
        LoadState.Ready -> Unit
    }

    val categories = remember(state, favVersion, refreshTick) { Catalog.visibleCategories(kind) }
    if (categories.isEmpty()) { CenterMessage("No $label", "Check your category rules in the panel"); return }

    var selectedCat by remember(kind) { mutableStateOf(categories.first().id) }
    var pendingCat by remember(kind) { mutableStateOf<String?>(null) }
    // Wait a moment before switching the grid, so scrolling through categories stays smooth
    LaunchedEffect(pendingCat) {
        val p = pendingCat ?: return@LaunchedEffect
        delay(250)
        selectedCat = p
    }
    val items = remember(selectedCat, favVersion, state, refreshTick) { Catalog.itemsIn(kind, selectedCat) }
    val gridState = remember(selectedCat) { LazyGridState() }

    Column(Modifier.fillMaxSize().padding(top = 16.dp, bottom = 16.dp, end = 12.dp)) {
    ScreenHeader(
        if (kind == Kind.MOVIE) "Films" else "Series",
        categories.firstOrNull { it.id == selectedCat }?.name?.let { "$it · ${items.size} titles" }
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.width(210.dp).fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(categories) { _, c ->
                FocusBox(
                    onClick = { selectedCat = c.id },
                    onFocused = { pendingCat = c.id },
                    selected = c.id == selectedCat,
                    modifier = Modifier.fillMaxWidth(),
                    focusedScale = 1.02f
                ) {
                    Text(
                        c.name, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 1, style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis, fontWeight = if (c.id == selectedCat) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(116.dp),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(items) { _, item ->
                Poster(item, onClick = { openItem = item })
            }
        }
    }
    }
}

@Composable
private fun Poster(item: Item, onClick: () -> Unit) {
    val context = LocalContext.current
    FocusBox(
        onClick = onClick,
        onMenu = {
            val added = Prefs.toggleFavourite(item.favKind, item.id)
            Catalog.favouritesVersion.value++
            Toast.makeText(context, if (added) "Added to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth(),
        focusedScale = 1.07f,
        color = Tile
    ) { focused ->
        Column {
            AsyncImage(
                model = item.icon,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Tile)
            )
            Text(
                item.name,
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                maxLines = if (focused) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

// ------------------------------------------------------------------ films

@Composable
internal fun MovieDetail(item: Item) {
    val context = LocalContext.current
    var info by remember(item.id) { mutableStateOf<VodInfo?>(null) }
    var fav by remember(item.id) { mutableStateOf(Prefs.isFavourite("movie", item.id)) }
    val resumeKey = "movie:${item.id}"
    var resume by remember(item.id) { mutableStateOf(Prefs.resumeFor(resumeKey)) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(item.id) {
        runCatching { playFocus.requestFocus() }
        val login = Prefs.login
        if (login != null && login.isXtream) info = Xtream.vodInfo(login, item.id)
    }

    fun play(fromStart: Boolean) {
        val login = Prefs.login ?: return
        PlayerActivity.start(
            context,
            PlayRequest(
                url = Xtream.movieUrl(login, item),
                title = item.name,
                type = PlayRequest.TYPE_VOD,
                startMs = if (fromStart) 0L else resume?.positionMs ?: 0L,
                resumeKey = resumeKey
            )
        )
    }

    DetailLayout(
        image = item.icon,
        backdrop = info?.backdrop,
        title = item.name,
        meta = listOfNotNull(info?.year, info?.genre, info?.durationText, info?.rating?.let { "★ $it" }).joinToString("  ·  "),
        plot = info?.plot,
        extra = listOfNotNull(info?.director?.let { "Director: $it" }, info?.cast?.let { "Cast: $it" }).joinToString("\n")
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val r = resume
            if (r != null && r.durationMs > 0) {
                ActionButton("Resume ${fmtTime(r.positionMs)}", Icons.Default.PlayArrow, Modifier.focusRequester(playFocus)) { play(false) }
                ActionButton("From start", Icons.Default.Replay) { play(true) }
            } else {
                ActionButton("Play", Icons.Default.PlayArrow, Modifier.focusRequester(playFocus)) { play(true) }
            }
            ActionButton(if (fav) "Favourite" else "Add to favourites", if (fav) Icons.Default.Star else Icons.Default.StarBorder) {
                fav = Prefs.toggleFavourite("movie", item.id)
                Catalog.favouritesVersion.value++
            }
        }
        val r = resume
        if (r != null && r.durationMs > 0) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { (r.positionMs.toFloat() / r.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.width(300.dp))
        }
    }

    // Refresh the resume point when coming back from the player
    LifecycleResume { resume = Prefs.resumeFor(resumeKey) }
}

// ------------------------------------------------------------------ series

@Composable
internal fun SeriesDetail(item: Item) {
    val context = LocalContext.current
    var info by remember(item.id) { mutableStateOf<SeriesInfo?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
    var season by remember(item.id) { mutableStateOf<Int?>(null) }
    var fav by remember(item.id) { mutableStateOf(Prefs.isFavourite("series", item.id)) }
    var resumeTick by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(item.id) {
        val login = Prefs.login ?: return@LaunchedEffect
        try {
            val i = Xtream.seriesInfo(login, item.id)
            info = i
            // Start on the season of the last episode watched, else the first
            val lastKey = Prefs.recentResumes(100).firstOrNull { it.first.startsWith("episode:${item.id}:") }?.first
            val lastEp = lastKey?.substringAfterLast(":")
            season = i.seasons.entries.firstOrNull { e -> e.value.any { it.id == lastEp } }?.key ?: i.seasons.keys.firstOrNull()
        } catch (e: Exception) {
            failed = true
        }
    }
    LaunchedEffect(info) { if (info != null) runCatching { firstFocus.requestFocus() } }
    LifecycleResume { resumeTick++ }

    val i = info
    DetailLayout(
        image = i?.cover ?: item.icon,
        backdrop = i?.backdrop,
        title = item.name,
        meta = listOfNotNull(i?.year, i?.genre, i?.rating?.let { "★ $it" }).joinToString("  ·  "),
        plot = i?.plot,
        extra = null
    ) {
        when {
            failed -> Text("Couldn't load episodes", color = MaterialTheme.colorScheme.error)
            i == null -> CircularProgressIndicator()
            i.seasons.isEmpty() -> Text("No episodes available", color = Muted)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(if (fav) "Favourite" else "Add to favourites", if (fav) Icons.Default.Star else Icons.Default.StarBorder) {
                        fav = Prefs.toggleFavourite("series", item.id)
                        Catalog.favouritesVersion.value++
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(i.seasons.keys.toList()) { _, s ->
                        Chip(if (s == 0) "Specials" else "Season $s", s == season) { season = s }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val eps = season?.let { i.seasons[it] } ?: emptyList()
                val target = remember(season, resumeTick, eps) {
                    eps.indexOfFirst { Prefs.resumeFor("episode:${item.id}:${it.id}") != null }.coerceAtLeast(0)
                }
                LazyColumn(Modifier.fillMaxWidth().height(260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(eps) { idx, ep ->
                        val key = "episode:${item.id}:${ep.id}"
                        val r = remember(resumeTick, key) { Prefs.resumeFor(key) }
                        EpisodeRow(ep, r?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f } ?: 0f,
                            if (idx == target) Modifier.focusRequester(firstFocus) else Modifier) {
                            val login = Prefs.login ?: return@EpisodeRow
                            PlayerActivity.start(
                                context,
                                PlayRequest(
                                    url = Xtream.episodeUrl(login, ep),
                                    title = "${item.name} · S${ep.season}E${ep.number} ${ep.title}",
                                    type = PlayRequest.TYPE_VOD,
                                    startMs = r?.positionMs ?: 0L,
                                    resumeKey = key
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, progress: Float, modifier: Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.01f, color = Panel) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(130.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Tile)) {
                AsyncImage(ep.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (progress > 0f) LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${ep.number}. ${ep.title}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ep.durationSecs > 0) Text("${ep.durationSecs / 60} min", color = Muted, style = MaterialTheme.typography.labelSmall)
                if (!ep.plot.isNullOrBlank()) Text(ep.plot, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ------------------------------------------------------------------ shared

@Composable
private fun DetailLayout(
    image: String?,
    backdrop: String?,
    title: String,
    meta: String,
    plot: String?,
    extra: String?,
    actions: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        if (backdrop != null) {
            AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.25f)
        }
        Row(Modifier.fillMaxSize().padding(32.dp)) {
            AsyncImage(
                image, null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)).background(Tile)
            )
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, maxLines = 2)
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(meta, color = Lavender)
                }
                if (!plot.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(plot, color = Muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (!extra.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(extra, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(18.dp))
                actions()
            }
        }
    }
}

@Composable
fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(
        onClick = onClick,
        modifier = modifier,
        color = PanelHigh,
        focusedColor = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, fontWeight = FontWeight.Bold)
        }
    }
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Runs [onResume] whenever the screen comes back (e.g. after the player closes). */
@Composable
fun LifecycleResume(onResume: () -> Unit) {
    val owner = LocalContext.current as? androidx.lifecycle.LifecycleOwner ?: return
    androidx.compose.runtime.DisposableEffect(owner) {
        var first = true
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if (first) first = false else onResume() }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

/** Opens a film or series detail page from elsewhere (search). */
@Composable
fun SearchDetail(item: Item) {
    if (item.kind == Kind.MOVIE) MovieDetail(item) else SeriesDetail(item)
}
