package tech.daemonhub.ultraplayer.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tech.daemonhub.ultraplayer.App
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Xtream Codes client. Big lists are downloaded to a cache file and parsed with a streaming
 * reader, so a 100k-item film list doesn't need the whole JSON tree in memory.
 */
object Xtream {
    const val USER_AGENT = "UltraPlayer"
    private const val CACHE_MAX_AGE_MS = 12L * 60 * 60 * 1000

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun apiUrl(l: Login, action: String?, extra: String = ""): String {
        val base = "${l.server}/player_api.php?username=${enc(l.username)}&password=${enc(l.password)}"
        return if (action == null) base + extra else "$base&action=$action$extra"
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server error (HTTP ${r.code})")
            return r.body?.string() ?: ""
        }
    }

    // ---------- login ----------
    /** Checks the account. Returns the expiry text (or null) on success. */
    suspend fun login(l: Login): String? = withContext(Dispatchers.IO) {
        val body = get(apiUrl(l, null))
        val j = runCatching { JSONObject(body) }.getOrNull()
            ?: throw LoginException("That doesn't look like an Xtream server")
        val info = j.optJSONObject("user_info") ?: throw LoginException("Wrong username or password")
        if (info.optString("auth") != "1" && info.optInt("auth", 0) != 1) throw LoginException("Wrong username or password")
        val status = info.optString("status")
        if (status.isNotBlank() && !status.equals("Active", ignoreCase = true)) throw LoginException("Account is $status")
        info.optString("exp_date").takeIf { it.isNotBlank() && it != "null" }
    }

    // ---------- cached list downloads ----------
    private fun cacheFile(l: Login, name: String): File {
        val dir = File(App.instance.cacheDir, "xtream").apply { mkdirs() }
        val tag = (l.server + "|" + l.username).hashCode().toUInt().toString(16)
        return File(dir, "${tag}_$name.json")
    }

    fun clearCache() {
        File(App.instance.cacheDir, "xtream").listFiles()?.forEach { it.delete() }
    }

    private fun fetchToCache(l: Login, action: String, force: Boolean): File {
        val f = cacheFile(l, action)
        val fresh = f.exists() && f.length() > 2 && System.currentTimeMillis() - f.lastModified() < CACHE_MAX_AGE_MS
        if (fresh && !force) return f
        try {
            val req = Request.Builder().url(apiUrl(l, action)).header("User-Agent", USER_AGENT).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Server error (HTTP ${r.code})")
                val tmp = File(f.path + ".tmp")
                r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // Offline or server hiccup: fall back to an older copy if there is one
            if (f.exists() && f.length() > 2) return f
            throw e
        }
        return f
    }

    // ---------- tolerant JSON helpers ----------
    /** Reads any scalar as a string (providers mix numbers, strings and nulls freely). */
    private fun JsonReader.anyString(): String? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.BOOLEAN -> nextBoolean().toString()
        JsonToken.NUMBER, JsonToken.STRING -> nextString()
        else -> { skipValue(); null }
    }

    private inline fun JsonReader.eachObjectInArray(block: JsonReader.() -> Unit) {
        if (peek() != JsonToken.BEGIN_ARRAY) { skipValue(); return }
        beginArray()
        while (hasNext()) {
            if (peek() == JsonToken.BEGIN_OBJECT) {
                beginObject(); block(); endObject()
            } else skipValue()
        }
        endArray()
    }

    private fun <T> parseFile(f: File, parse: (JsonReader) -> T): T =
        JsonReader(InputStreamReader(f.inputStream().buffered(64 * 1024), Charsets.UTF_8)).use { r ->
            r.isLenient = true
            parse(r)
        }

    // ---------- categories ----------
    suspend fun categories(l: Login, kind: Kind, force: Boolean): List<Category> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            Kind.LIVE -> "get_live_categories"
            Kind.MOVIE -> "get_vod_categories"
            Kind.SERIES -> "get_series_categories"
        }
        val f = fetchToCache(l, action, force)
        parseFile(f) { r ->
            val out = ArrayList<Category>()
            r.eachObjectInArray {
                var id: String? = null
                var name: String? = null
                while (hasNext()) {
                    when (nextName()) {
                        "category_id" -> id = anyString()
                        "category_name" -> name = anyString()
                        else -> skipValue()
                    }
                }
                if (id != null) out += Category(id!!, name ?: "Unnamed")
            }
            out
        }
    }

    // ---------- streams ----------
    suspend fun items(l: Login, kind: Kind, force: Boolean): List<Item> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            Kind.LIVE -> "get_live_streams"
            Kind.MOVIE -> "get_vod_streams"
            Kind.SERIES -> "get_series"
        }
        val f = fetchToCache(l, action, force)
        parseFile(f) { r ->
            val out = ArrayList<Item>(4096)
            r.eachObjectInArray {
                var id: String? = null
                var name: String? = null
                var icon: String? = null
                var cat: String? = null
                var ext: String? = null
                var rating: String? = null
                var num = 0
                var added = 0L
                while (hasNext()) {
                    when (nextName()) {
                        "stream_id", "series_id" -> id = anyString()
                        "name" -> name = anyString()
                        "stream_icon", "cover" -> icon = anyString()
                        "category_id" -> cat = anyString()
                        "container_extension" -> ext = anyString()
                        "rating" -> rating = anyString()
                        "num" -> num = anyString()?.toIntOrNull() ?: 0
                        "added", "last_modified" -> added = anyString()?.toLongOrNull() ?: 0L
                        else -> skipValue()
                    }
                }
                if (id != null) {
                    out += Item(kind, id!!, name?.trim() ?: "", icon?.takeIf { it.isNotBlank() }, cat ?: "", ext, rating, num = num, added = added)
                }
            }
            out
        }
    }

    // ---------- playback urls ----------
    fun liveUrl(l: Login, item: Item): String =
        item.directUrl ?: "${l.server}/live/${l.username}/${l.password}/${item.id}.ts"

    fun movieUrl(l: Login, item: Item): String =
        item.directUrl ?: "${l.server}/movie/${l.username}/${l.password}/${item.id}.${item.ext ?: "mp4"}"

    fun episodeUrl(l: Login, ep: Episode): String =
        ep.directUrl ?: "${l.server}/series/${l.username}/${l.password}/${ep.id}.${ep.ext ?: "mp4"}"

    // ---------- details ----------
    suspend fun seriesInfo(l: Login, seriesId: String): SeriesInfo = withContext(Dispatchers.IO) {
        val j = JSONObject(get(apiUrl(l, "get_series_info", "&series_id=${enc(seriesId)}")))
        val info = j.optJSONObject("info")
        val seasons = sortedMapOf<Int, List<Episode>>()
        val eps = j.opt("episodes")
        fun parseEps(arr: JSONArray?, seasonKey: Int) {
            if (arr == null) return
            val list = ArrayList<Episode>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val ei = e.optJSONObject("info")
                list += Episode(
                    id = e.optString("id"),
                    title = e.optString("title").ifBlank { "Episode ${e.optInt("episode_num")}" },
                    season = e.optInt("season", seasonKey),
                    number = e.optInt("episode_num", i + 1),
                    ext = e.optString("container_extension").takeIf { it.isNotBlank() },
                    plot = ei?.optString("plot")?.takeIf { it.isNotBlank() && it != "null" },
                    image = ei?.optString("movie_image")?.takeIf { it.isNotBlank() && it != "null" },
                    durationSecs = ei?.optInt("duration_secs", 0) ?: 0
                )
            }
            if (list.isNotEmpty()) seasons[seasonKey] = list.sortedBy { it.number }
        }
        when (eps) {
            is JSONObject -> eps.keys().forEach { k -> parseEps(eps.optJSONArray(k), k.toIntOrNull() ?: 0) }
            is JSONArray -> for (i in 0 until eps.length()) parseEps(eps.optJSONArray(i), i + 1)
        }
        fun s(o: JSONObject?, k: String) = o?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
        SeriesInfo(
            plot = s(info, "plot"),
            cover = s(info, "cover"),
            backdrop = info?.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.isNotBlank() },
            genre = s(info, "genre"),
            year = s(info, "releaseDate")?.take(4) ?: s(info, "release_date")?.take(4),
            rating = s(info, "rating"),
            seasons = seasons
        )
    }

    suspend fun vodInfo(l: Login, vodId: String): VodInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val j = JSONObject(get(apiUrl(l, "get_vod_info", "&vod_id=${enc(vodId)}")))
            val info = j.optJSONObject("info") ?: return@runCatching null
            fun s(k: String) = info.optString(k).takeIf { it.isNotBlank() && it != "null" }
            VodInfo(
                plot = s("plot") ?: s("description"),
                genre = s("genre"),
                year = s("releasedate")?.take(4) ?: s("release_date")?.take(4),
                rating = s("rating"),
                durationText = s("duration"),
                cast = s("cast") ?: s("actors"),
                director = s("director"),
                backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private fun b64(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(s)
    }

    suspend fun shortEpg(l: Login, streamId: String, limit: Int = 4): List<EpgEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val body = get(apiUrl(l, "get_short_epg", "&stream_id=${enc(streamId)}&limit=$limit"))
            val arr = JSONObject(body).optJSONArray("epg_listings") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val start = e.optString("start_timestamp").toLongOrNull()?.times(1000) ?: return@mapNotNull null
                val end = (e.optString("stop_timestamp").ifBlank { e.optString("end_timestamp") }).toLongOrNull()?.times(1000) ?: start
                EpgEntry(b64(e.optString("title")), b64(e.optString("description")).takeIf { it.isNotBlank() }, start, end)
            }.filter { it.endMs > System.currentTimeMillis() - 60_000 }
        }.getOrDefault(emptyList())
    }
}
