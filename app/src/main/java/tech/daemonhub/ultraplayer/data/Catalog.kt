package tech.daemonhub.ultraplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.daemonhub.ultraplayer.panel.CategoryRules

/** Pseudo-category ids shown above the provider's own categories. */
const val CAT_FAVOURITES = "__fav"
const val CAT_RECENT = "__recent"

sealed class LoadState {
    object Idle : LoadState()
    object Loading : LoadState()
    object Ready : LoadState()
    data class Failed(val message: String) : LoadState()
}

/** One content type's data, grouped once so every screen lookup is instant. */
class Section(
    val categories: List<Category>,           // provider order, unfiltered
    val byCategory: Map<String, List<Item>>,  // categoryId -> items
    val byId: Map<String, Item>
)

/**
 * Holds the provider's lists in memory. Each tab loads its own type the first time it's opened,
 * so Live TV is usable before the (much bigger) film and series lists have even been fetched.
 */
object Catalog {
    private val sections = HashMap<Kind, Section>()
    private val states = Kind.values().associateWith { MutableStateFlow<LoadState>(LoadState.Idle) }
    private val locks = Kind.values().associateWith { Mutex() }
    private var m3uCache: M3u.Playlist? = null

    /** Bumped whenever favourites change so screens can refresh their Favourites list. */
    val favouritesVersion = MutableStateFlow(0)

    fun state(kind: Kind): StateFlow<LoadState> = states.getValue(kind)

    fun section(kind: Kind): Section? = synchronized(sections) { sections[kind] }

    fun reset() {
        synchronized(sections) { sections.clear() }
        m3uCache = null
        states.values.forEach { it.value = LoadState.Idle }
    }

    suspend fun ensure(kind: Kind, force: Boolean = false) {
        val login = Prefs.login ?: return
        locks.getValue(kind).withLock {
            if (!force && section(kind) != null) return
            val st = states.getValue(kind)
            st.value = LoadState.Loading
            try {
                val (cats, items) = withContext(Dispatchers.IO) {
                    if (login.isXtream) {
                        Xtream.categories(login, kind, force) to Xtream.items(login, kind, force)
                    } else {
                        val pl = m3uCache?.takeIf { !force } ?: M3u.load(login.m3uUrl, force).also { m3uCache = it }
                        (pl.categories[kind] ?: emptyList()) to (pl.items[kind] ?: emptyList())
                    }
                }
                val section = withContext(Dispatchers.Default) { build(cats, items) }
                synchronized(sections) { sections[kind] = section }
                st.value = LoadState.Ready
            } catch (e: Exception) {
                st.value = LoadState.Failed(e.message ?: "Couldn't load the list")
            }
        }
    }

    private fun build(cats: List<Category>, items: List<Item>): Section {
        val byCat = LinkedHashMap<String, MutableList<Item>>()
        val byId = HashMap<String, Item>(items.size * 2)
        for (it in items) {
            byCat.getOrPut(it.categoryId) { ArrayList() }.add(it)
            byId[it.id] = it
        }
        // Categories the provider forgot to list but that have items
        val known = cats.mapTo(HashSet()) { it.id }
        val extra = byCat.keys.filter { it !in known && it.isNotBlank() }.map { Category(it, "Other") }
        return Section(cats + extra, byCat, byId)
    }

    /** Categories as the user should see them: panel rules applied, empty ones dropped, Favourites on top. */
    fun visibleCategories(kind: Kind): List<Category> {
        val s = section(kind) ?: return emptyList()
        val arranged = CategoryRules.arrange(s.categories.filter { (s.byCategory[it.id]?.isNotEmpty()) == true })
        val top = ArrayList<Category>()
        if (Prefs.favourites(kind.name.lowercase()).isNotEmpty()) top += Category(CAT_FAVOURITES, "★ Favourites")
        if (kind != Kind.LIVE && Prefs.recentResumes(1).isNotEmpty()) top += Category(CAT_RECENT, "Continue watching")
        return top + arranged
    }

    fun itemsIn(kind: Kind, categoryId: String): List<Item> {
        val s = section(kind) ?: return emptyList()
        return when (categoryId) {
            CAT_FAVOURITES -> Prefs.favourites(kind.name.lowercase()).mapNotNull { s.byId[it] }.sortedBy { it.name.lowercase() }
            CAT_RECENT -> Prefs.recentResumes(40).mapNotNull { (key, _) ->
                // resume keys look like "movie:123" or "episode:<seriesId>:<episodeId>"
                val parts = key.split(":")
                when {
                    kind == Kind.MOVIE && parts.size == 2 && parts[0] == "movie" -> s.byId[parts[1]]
                    kind == Kind.SERIES && parts.size == 3 && parts[0] == "episode" -> s.byId[parts[1]]
                    else -> null
                }
            }.distinctBy { it.id }
            else -> s.byCategory[categoryId] ?: emptyList()
        }
    }

    /** Newest items (by the provider's "added" date), skipping categories the panel hides. */
    fun recentlyAdded(kind: Kind, limit: Int = 30): List<Item> {
        val s = section(kind) ?: return emptyList()
        val hidden = s.categories.filter { CategoryRules.isHidden(it.name) }.mapTo(HashSet()) { it.id }
        return s.byId.values.asSequence().filter { it.added > 0 && it.categoryId !in hidden }
            .sortedByDescending { it.added }.take(limit).toList()
    }

    /** Simple in-memory search across whatever has been loaded. */
    fun search(query: String, limitPerKind: Int = 60): Map<Kind, List<Item>> {
        val q = query.trim()
        if (q.length < 2) return emptyMap()
        return Kind.values().associateWith { k ->
            val s = section(k) ?: return@associateWith emptyList()
            val hiddenCats = s.categories.filter { CategoryRules.isHidden(it.name) }.mapTo(HashSet()) { it.id }
            s.byId.values.asSequence()
                .filter { it.categoryId !in hiddenCats && it.name.contains(q, ignoreCase = true) }
                .take(limitPerKind)
                .toList()
        }
    }
}
