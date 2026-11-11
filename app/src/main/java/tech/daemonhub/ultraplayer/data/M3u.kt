package tech.daemonhub.ultraplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import tech.daemonhub.ultraplayer.App
import java.io.File
import java.io.IOException

/** M3U playlist support: group-title becomes the category; /movie/ and /series/ links become films. */
object M3u {
    data class Playlist(val categories: Map<Kind, List<Category>>, val items: Map<Kind, List<Item>>)

    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    private fun cacheFile(url: String) =
        File(App.instance.cacheDir, "m3u_${url.hashCode().toUInt().toString(16)}.m3u")

    suspend fun load(url: String, force: Boolean): Playlist = withContext(Dispatchers.IO) {
        val f = cacheFile(url)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 12L * 60 * 60 * 1000
        if (!fresh || force) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", Xtream.USER_AGENT).build()
                Xtream.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Playlist error (HTTP ${r.code})")
                    val tmp = File(f.path + ".tmp")
                    r.body!!.byteStream().use { i -> tmp.outputStream().use { i.copyTo(it, 64 * 1024) } }
                    tmp.renameTo(f)
                }
            } catch (e: Exception) {
                if (!f.exists()) throw e
            }
        }
        parse(f)
    }

    private fun parse(f: File): Playlist {
        val cats: Map<Kind, LinkedHashMap<String, Category>> = mapOf(
            Kind.LIVE to LinkedHashMap(), Kind.MOVIE to LinkedHashMap(), Kind.SERIES to LinkedHashMap()
        )
        val items: Map<Kind, ArrayList<Item>> = mapOf(Kind.LIVE to ArrayList(), Kind.MOVIE to ArrayList(), Kind.SERIES to ArrayList())
        var pendingName: String? = null
        var pendingAttrs: Map<String, String> = emptyMap()
        var counter = 0
        f.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    pendingAttrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    // Name is after the last comma that isn't inside quotes; good enough to take the text after the final '",' or first ','
                    val q = line.lastIndexOf("\",")
                    pendingName = if (q >= 0) line.substring(q + 2).trim() else line.substringAfter(",", "").trim()
                } else if (line.isNotEmpty() && !line.startsWith("#") && pendingName != null) {
                    val url = line
                    val kind = when {
                        url.contains("/movie/") -> Kind.MOVIE
                        url.contains("/series/") -> Kind.SERIES
                        else -> Kind.LIVE
                    }
                    val group = pendingAttrs["group-title"]?.ifBlank { null } ?: "Uncategorised"
                    cats[kind]!!.getOrPut(group) { Category(group, group) }
                    // M3U series links are single episodes, so treat them like films
                    val itemKind = if (kind == Kind.SERIES) Kind.MOVIE else kind
                    if (itemKind != kind) cats[Kind.MOVIE]!!.getOrPut(group) { Category(group, group) }
                    items[itemKind]!! += Item(
                        kind = itemKind,
                        id = "m${counter++}",
                        name = pendingName!!.ifBlank { pendingAttrs["tvg-name"] ?: "Channel $counter" },
                        icon = pendingAttrs["tvg-logo"]?.ifBlank { null },
                        categoryId = group,
                        directUrl = url
                    )
                    pendingName = null
                }
            }
        }
        return Playlist(
            categories = mapOf(
                Kind.LIVE to cats[Kind.LIVE]!!.values.toList(),
                Kind.MOVIE to cats[Kind.MOVIE]!!.values.toList(),
                Kind.SERIES to emptyList()
            ),
            items = mapOf(Kind.LIVE to items[Kind.LIVE]!!, Kind.MOVIE to items[Kind.MOVIE]!!, Kind.SERIES to emptyList())
        )
    }
}
