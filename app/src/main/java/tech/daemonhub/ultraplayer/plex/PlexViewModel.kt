package tech.daemonhub.ultraplayer.plex

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import tech.daemonhub.ultraplayer.player.PlayRequest
import tech.daemonhub.ultraplayer.player.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class PlexStage {
    object Loading : PlexStage()
    object SignedOut : PlexStage()
    data class Linking(val code: String) : PlexStage()
    data class PickServer(val servers: List<PlexServer>) : PlexStage()
    object Browsing : PlexStage()
    data class Failed(val message: String) : PlexStage()
}

data class PlexRow(val title: String, val items: List<PlexItem>)

class PlexViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var stage by mutableStateOf<PlexStage>(PlexStage.Loading)
        private set
    var session by mutableStateOf<PlexSession?>(null)
        private set
    var libraries by mutableStateOf<List<PlexLibrary>>(emptyList())
        private set

    /** null = Home tab */
    var selectedLibrary by mutableStateOf<PlexLibrary?>(null)
        private set
    var homeRows by mutableStateOf<List<PlexRow>>(emptyList())
        private set
    var gridItems by mutableStateOf<List<PlexItem>>(emptyList())
        private set
    var gridLoading by mutableStateOf(false)
        private set
    private var gridEnd = false

    /** Show → season drill-down. Top of the stack is what's on screen. */
    var detailStack by mutableStateOf<List<PlexItem>>(emptyList())
        private set
    var detailChildren by mutableStateOf<List<PlexItem>>(emptyList())
        private set
    var detailLoading by mutableStateOf(false)
        private set

    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<PlexItem>?>(null)
        private set

    var toast by mutableStateOf<String?>(null)

    private var pinJob: Job? = null
    private var searchJob: Job? = null
    private var servers: List<PlexServer> = emptyList()

    init { start() }

    fun start() {
        val token = PlexApi.userToken(ctx)
        if (token == null) { stage = PlexStage.SignedOut; return }
        stage = PlexStage.Loading
        viewModelScope.launch {
            // Fast path: reopen last time's server straight away; the server list loads in the background
            val last = PlexApi.resumeLastSession(ctx)
            if (last != null) {
                try {
                    openSession(last)
                    launch { servers = runCatching { PlexApi.servers(ctx, token) }.getOrDefault(servers) }
                    return@launch
                } catch (e: PlexAuthException) {
                    signOut(); return@launch
                } catch (_: Exception) { /* fall through to the full flow */ }
            }
            try {
                servers = PlexApi.servers(ctx, token)
                when {
                    servers.isEmpty() -> stage = PlexStage.Failed("No Plex servers on this account. Ask the owner to share their library with you.")
                    else -> {
                        val saved = PlexApi.savedServerId(ctx)
                        val pick = servers.firstOrNull { it.clientId == saved } ?: servers.singleOrNull()
                        if (pick != null) openServer(pick) else stage = PlexStage.PickServer(servers)
                    }
                }
            } catch (e: PlexAuthException) {
                signOut()
            } catch (e: Exception) {
                stage = PlexStage.Failed(e.message ?: "Couldn't reach plex.tv")
            }
        }
    }

    fun beginLink() {
        pinJob?.cancel()
        stage = PlexStage.Loading
        pinJob = viewModelScope.launch {
            try {
                val pin = PlexApi.createPin(ctx)
                stage = PlexStage.Linking(pin.code)
                // Codes expire after ~15 minutes; poll every 2s until then
                val deadline = System.currentTimeMillis() + 14 * 60_000
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(2000)
                    val token = runCatching { PlexApi.checkPin(ctx, pin) }.getOrNull()
                    if (token != null) {
                        PlexApi.saveUserToken(ctx, token)
                        start()
                        return@launch
                    }
                }
                stage = PlexStage.SignedOut
            } catch (e: Exception) {
                stage = PlexStage.Failed(e.message ?: "Couldn't contact plex.tv")
            }
        }
    }

    fun cancelLink() { pinJob?.cancel(); stage = PlexStage.SignedOut }

    fun showServerPicker() { if (servers.isNotEmpty()) stage = PlexStage.PickServer(servers) else start() }

    private suspend fun openSession(s: PlexSession) {
        PlexApi.activeSession = s
        session = s
        libraries = PlexApi.libraries(ctx, s)
        detailStack = emptyList()
        searchResults = null
        selectLibrary(null)
        stage = PlexStage.Browsing
    }

    fun openServer(server: PlexServer) {
        stage = PlexStage.Loading
        viewModelScope.launch {
            try {
                PlexApi.saveServerId(ctx, server.clientId)
                openSession(PlexApi.connect(ctx, server))
            } catch (e: PlexAuthException) {
                signOut()
            } catch (e: Exception) {
                stage = PlexStage.Failed(e.message ?: "Couldn't open ${server.name}")
            }
        }
    }

    fun signOut() {
        PlexApi.saveUserToken(ctx, null)
        PlexApi.activeSession = null
        session = null
        libraries = emptyList()
        homeRows = emptyList()
        gridItems = emptyList()
        detailStack = emptyList()
        stage = PlexStage.SignedOut
    }

    fun selectLibrary(lib: PlexLibrary?) {
        val s = session ?: return
        selectedLibrary = lib
        detailStack = emptyList()
        if (lib == null) {
            viewModelScope.launch {
                // All rows at once instead of one after another
                val deck = async { runCatching { PlexApi.onDeck(ctx, s) }.getOrNull() }
                val recents = libraries.map { l -> l to async { runCatching { PlexApi.recentlyAdded(ctx, s, l.key) }.getOrNull() } }
                val rows = mutableListOf<PlexRow>()
                deck.await()?.takeIf { it.isNotEmpty() }?.let { rows += PlexRow("Continue watching", it) }
                for ((l, d) in recents) d.await()?.takeIf { it.isNotEmpty() }?.let { rows += PlexRow("Recently added · ${l.title}", it) }
                homeRows = rows
            }
        } else {
            gridItems = emptyList()
            gridEnd = false
            loadMore()
        }
    }

    fun loadMore() {
        val s = session ?: return
        val lib = selectedLibrary ?: return
        if (gridLoading || gridEnd) return
        gridLoading = true
        viewModelScope.launch {
            try {
                val page = PlexApi.all(ctx, s, lib.key, gridItems.size, 60)
                if (selectedLibrary?.key == lib.key) {
                    gridItems = gridItems + page
                    if (page.size < 60) gridEnd = true
                }
            } catch (e: Exception) {
                toast = e.message
                gridEnd = true
            } finally {
                gridLoading = false
            }
        }
    }

    fun onSearch(q: String) {
        searchQuery = q
        searchJob?.cancel()
        if (q.isBlank()) { searchResults = null; return }
        val s = session ?: return
        searchJob = viewModelScope.launch {
            delay(400)
            searchResults = runCatching { PlexApi.search(ctx, s, q) }.getOrDefault(emptyList())
        }
    }

    /** Movies/episodes play; shows/seasons drill down. */
    fun open(item: PlexItem) {
        when (item.type) {
            "show", "season" -> pushDetail(item)
            else -> play(item)
        }
    }

    private fun pushDetail(item: PlexItem) {
        val s = session ?: return
        detailStack = detailStack + item
        loadChildren(s, item)
    }

    private fun loadChildren(s: PlexSession, item: PlexItem) {
        detailChildren = emptyList()
        detailLoading = true
        viewModelScope.launch {
            val kids = runCatching { PlexApi.children(ctx, s, item.ratingKey) }.getOrDefault(emptyList())
            if (detailStack.lastOrNull()?.ratingKey == item.ratingKey) {
                // Skip the season level when a show only has one season
                if (item.type == "show" && kids.size == 1 && kids[0].type == "season") {
                    detailStack = detailStack.dropLast(1) + kids[0].copy(title = "${item.title} · ${kids[0].title}")
                    loadChildren(s, kids[0])
                    return@launch
                }
                detailChildren = kids
                detailLoading = false
            }
        }
    }

    /** @return true if it handled back */
    fun back(): Boolean {
        val s = session
        if (detailStack.isNotEmpty()) {
            detailStack = detailStack.dropLast(1)
            detailStack.lastOrNull()?.let { if (s != null) loadChildren(s, it) }
            return true
        }
        return false
    }

    fun play(item: PlexItem, fromStart: Boolean = false) {
        val s = session ?: return
        viewModelScope.launch {
            val full = if (item.partKey.isBlank()) runCatching { PlexApi.metadata(ctx, s, item.ratingKey) }.getOrNull() else item
            if (full == null || full.partKey.isBlank()) { toast = "This item has no playable file"; return@launch }
            PlayerActivity.start(
                ctx,
                PlayRequest(
                    url = PlexApi.streamUrl(s, full.partKey),
                    title = full.displayTitle.let { t -> if (full.type == "episode") "$t · ${full.title}" else t },
                    type = PlayRequest.TYPE_PLEX,
                    startMs = if (fromStart) 0L else full.viewOffset,
                    plexRatingKey = full.ratingKey
                )
            )
        }
    }

    /** Called when returning from the player so progress bars refresh. */
    fun refreshAfterPlayback() {
        val s = session ?: return
        if (stage != PlexStage.Browsing) return
        val top = detailStack.lastOrNull()
        when {
            top != null -> viewModelScope.launch {
                runCatching { PlexApi.children(ctx, s, top.ratingKey) }.getOrNull()?.let {
                    if (detailStack.lastOrNull()?.ratingKey == top.ratingKey) detailChildren = it
                }
            }
            selectedLibrary == null -> selectLibrary(null)
        }
    }
}
