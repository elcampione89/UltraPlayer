package tech.daemonhub.ultraplayer.plex

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

@Composable
fun PlexScreen(vm: PlexViewModel = viewModel()) {
    val context = LocalContext.current

    LaunchedEffect(vm.toast) {
        vm.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.toast = null }
    }

    // Refresh progress bars when coming back from the player
    val owner = context as? LifecycleOwner
    DisposableEffect(owner) {
        var skipFirst = true
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (skipFirst) skipFirst = false else vm.refreshAfterPlayback()
            }
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val st = vm.stage) {
            PlexStage.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            PlexStage.SignedOut -> SignedOutCard(onSignIn = vm::beginLink)
            is PlexStage.Linking -> LinkingCard(st.code, onCancel = vm::cancelLink)
            is PlexStage.PickServer -> ServerPicker(st.servers, onPick = vm::openServer, onSignOut = vm::signOut)
            is PlexStage.Failed -> CenterCard {
                Text("Plex", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(st.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusedButton("Retry", onClick = vm::start)
                    OutlinedButton(onClick = vm::signOut) { Text("Sign out") }
                }
            }
            PlexStage.Browsing -> Browser(vm)
        }
    }
}

// ---------------- sign-in ----------------

@Composable
private fun CenterCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        ElevatedCard(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
    }
}

@Composable
private fun FocusedButton(text: String, onClick: () -> Unit) {
    val fr = remember { FocusRequester() }
    Button(onClick = onClick, modifier = Modifier.focusRequester(fr)) { Text(text) }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
private fun SignedOutCard(onSignIn: () -> Unit) = CenterCard {
    Icon(Icons.Default.VideoLibrary, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(12.dp))
    Text("Plex", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(8.dp))
    Text(
        "Sign in with your Plex account to watch the libraries shared with you.",
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
    FocusedButton("Sign in with Plex", onClick = onSignIn)
}

@Composable
private fun LinkingCard(code: String, onCancel: () -> Unit) = CenterCard {
    Text("Link your Plex account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Text("On your phone or computer, go to", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("plex.tv/link", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    Text("and enter this code:", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Text(
        code,
        fontSize = 52.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 10.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 28.dp, vertical = 8.dp)
    )
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Waiting for you to enter the code…", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun ServerPicker(servers: List<PlexServer>, onPick: (PlexServer) -> Unit, onSignOut: () -> Unit) = CenterCard {
    Text("Choose a server", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    val fr = remember { FocusRequester() }
    servers.forEachIndexed { i, s ->
        OutlinedButton(
            onClick = { onPick(s) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).let { if (i == 0) it.focusRequester(fr) else it }
        ) {
            Icon(Icons.Default.Dns, null)
            Spacer(Modifier.width(12.dp))
            Text(s.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!s.owned) Text("shared", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onSignOut) { Text("Sign out of Plex") }
}

// ---------------- browsing ----------------

@Composable
private fun Browser(vm: PlexViewModel) {
    val session = vm.session ?: return
    var searching by remember { mutableStateOf(false) }

    BackHandler(enabled = vm.detailStack.isNotEmpty() || searching) {
        if (vm.detailStack.isNotEmpty()) vm.back()
        else { searching = false; vm.onSearch("") }
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Plex", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(session.serverName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { searching = !searching; if (!searching) vm.onSearch("") }) {
                Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
            }
            IconButton(onClick = vm::showServerPicker) { Icon(Icons.Default.Dns, "Switch server") }
            IconButton(onClick = vm::signOut) { Icon(Icons.Default.Logout, "Sign out") }
        }

        if (searching && vm.detailStack.isEmpty()) {
            val fr = remember { FocusRequester() }
            OutlinedTextField(
                value = vm.searchQuery,
                onValueChange = vm::onSearch,
                placeholder = { Text("Search ${session.serverName}") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).focusRequester(fr)
            )
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        } else if (vm.detailStack.isEmpty()) {
            // Library chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(selected = vm.selectedLibrary == null, onClick = { vm.selectLibrary(null) }, label = { Text("Home") })
                }
                items(vm.libraries, key = { it.key }) { lib ->
                    FilterChip(selected = vm.selectedLibrary?.key == lib.key, onClick = { vm.selectLibrary(lib) }, label = { Text(lib.title) })
                }
            }
        }

        Box(Modifier.weight(1f)) {
            val results = vm.searchResults
            when {
                vm.detailStack.isNotEmpty() -> DetailPane(vm, session)
                searching && results != null -> PosterGrid(session, results, loading = false, onOpen = vm::open, onEnd = {})
                searching -> {}
                vm.selectedLibrary == null -> HomeRows(vm, session)
                else -> PosterGrid(session, vm.gridItems, vm.gridLoading, onOpen = vm::open, onEnd = vm::loadMore)
            }
        }
    }
}

@Composable
private fun HomeRows(vm: PlexViewModel, session: PlexSession) {
    if (vm.homeRows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(vm.homeRows, key = { it.title }) { row ->
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp)
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(row.items, key = { it.ratingKey }) { item ->
                    PosterCard(session, item, Modifier.width(128.dp)) { vm.open(item) }
                }
            }
        }
    }
}

@Composable
private fun PosterGrid(
    session: PlexSession,
    list: List<PlexItem>,
    loading: Boolean,
    onOpen: (PlexItem) -> Unit,
    onEnd: () -> Unit
) {
    val state = rememberLazyGridState()
    val nearEnd by remember(list.size) {
        derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.size - 12 }
    }
    LaunchedEffect(nearEnd, list.size) { if (nearEnd && list.isNotEmpty()) onEnd() }

    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator() else Text("Nothing here", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        state = state,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(list, key = { it.ratingKey }) { item -> PosterCard(session, item) { onOpen(item) } }
        if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun DetailPane(vm: PlexViewModel, session: PlexSession) {
    val top = vm.detailStack.last()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            AsyncImage(
                model = PlexApi.imageUrl(session, top.thumb, 240, 360),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(90.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(top.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                if (top.year > 0) Text(top.year.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (top.summary.isNotBlank()) Text(
                    top.summary, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        when {
            vm.detailLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            vm.detailChildren.firstOrNull()?.type == "episode" -> {
                // Jump focus to the first unwatched episode
                val firstFr = remember(top.ratingKey) { FocusRequester() }
                val target = vm.detailChildren.indexOfFirst { !it.isWatched }.coerceAtLeast(0)
                LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.detailChildren.size) { i ->
                        val ep = vm.detailChildren[i]
                        EpisodeRow(session, ep, if (i == target) Modifier.focusRequester(firstFr) else Modifier) { vm.play(ep) }
                    }
                }
                LaunchedEffect(top.ratingKey, vm.detailChildren.size) { runCatching { firstFr.requestFocus() } }
            }
            else -> PosterGrid(session, vm.detailChildren, loading = false, onOpen = vm::open, onEnd = {})
        }
    }
}

// ---------------- cards ----------------

@Composable
private fun focusBorder(interaction: MutableInteractionSource): BorderStroke? {
    val focused by interaction.collectIsFocusedAsState()
    return if (focused) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null
}

@Composable
private fun PosterCard(session: PlexSession, item: PlexItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(modifier) {
        Card(
            onClick = onClick,
            interactionSource = interaction,
            border = focusBorder(interaction),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = PlexApi.imageUrl(session, item.poster, 300, 450),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (item.isWatched) {
                    Icon(
                        Icons.Default.CheckCircle, "Watched",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    )
                }
                if (item.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.displayTitle, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = when (item.type) {
            "episode" -> item.title
            "season" -> if (item.leafCount > 0) "${item.leafCount} episodes" else ""
            else -> if (item.year > 0) item.year.toString() else ""
        }
        if (sub.isNotBlank()) Text(
            sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EpisodeRow(session: PlexSession, ep: PlexItem, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = interaction,
        border = focusBorder(interaction),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                AsyncImage(
                    model = PlexApi.imageUrl(session, ep.thumb, 320, 180),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (ep.progress > 0f) LinearProgressIndicator(
                    progress = { ep.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${ep.index}. ${ep.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ep.duration > 0) Text(
                    "${ep.duration / 60000} min", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ep.summary.isNotBlank()) Text(
                    ep.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (ep.isWatched) Icon(Icons.Default.CheckCircle, "Watched", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
