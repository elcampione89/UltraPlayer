package tech.daemonhub.ultraplayer.plex

import android.content.Context
import android.os.Build
import tech.daemonhub.ultraplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PlexPin(val id: Long, val code: String)

data class PlexServer(
    val name: String,
    val clientId: String,
    val accessToken: String,
    val owned: Boolean,
    val connections: List<PlexConnection>
)

data class PlexConnection(val uri: String, val local: Boolean, val relay: Boolean)

/** A server we've confirmed we can reach, with the base URL that worked. */
data class PlexSession(val serverName: String, val baseUrl: String, val token: String)

data class PlexLibrary(val key: String, val title: String, val type: String) // type: movie, show, artist, photo

data class PlexItem(
    val ratingKey: String,
    val title: String,
    val type: String,          // movie, show, season, episode
    val year: Int,
    val summary: String,
    val thumb: String,
    val grandparentThumb: String,
    val art: String,
    val grandparentTitle: String,
    val parentIndex: Int,
    val index: Int,
    val viewOffset: Long,
    val duration: Long,
    val viewCount: Int,
    val leafCount: Int,
    val viewedLeafCount: Int,
    val partKey: String
) {
    /** Poster for grids: episodes use their show's poster. */
    val poster: String get() = if (type == "episode" && grandparentThumb.isNotBlank()) grandparentThumb else thumb
    val isWatched: Boolean get() = if (type == "show" || type == "season") leafCount in 1..viewedLeafCount else viewCount > 0
    val progress: Float get() = if (duration > 0 && viewOffset > 0) (viewOffset.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val displayTitle: String get() = when (type) {
        "episode" -> if (grandparentTitle.isNotBlank()) "$grandparentTitle · S${parentIndex}E$index" else title
        else -> title
    }
}

/**
 * Minimal Plex client: plex.tv PIN sign-in (plex.tv/link), server discovery and
 * direct-play URLs that the app's own players (Exo/VLC/MPV) open.
 */
object PlexApi {
    private const val PREFS = "plex"
    private const val PRODUCT = "Ultra Player"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    // ---------- stored state ----------
    fun clientId(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("client_id", it).apply()
        }
    }

    fun userToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("user_token", null)

    fun saveUserToken(context: Context, token: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (token == null) remove("user_token").remove("server_id").remove("last_session") else putString("user_token", token)
        }.apply()
    }

    fun savedServerId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("server_id", null)

    fun saveServerId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("server_id", id).apply()
    }

    // Set when a server is opened; PlayerActivity uses it to report progress back.
    @Volatile var activeSession: PlexSession? = null

    private fun Request.Builder.plexHeaders(context: Context, token: String? = null): Request.Builder {
        header("Accept", "application/json")
        header("X-Plex-Product", PRODUCT)
        header("X-Plex-Version", BuildConfig.VERSION_NAME)
        header("X-Plex-Client-Identifier", clientId(context))
        header("X-Plex-Platform", "Android")
        header("X-Plex-Platform-Version", Build.VERSION.RELEASE ?: "")
        header("X-Plex-Device", Build.MODEL ?: "Android")
        header("X-Plex-Device-Name", "$PRODUCT (${Build.MODEL ?: "Android"})")
        if (token != null) header("X-Plex-Token", token)
        return this
    }

    private fun getJson(context: Context, url: String, token: String?, http: OkHttpClient = client): String {
        val req = Request.Builder().url(url).plexHeaders(context, token).get().build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw PlexAuthException()
            if (!resp.isSuccessful) throw java.io.IOException("Plex HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    // ---------- sign-in ----------
    suspend fun createPin(context: Context): PlexPin = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://plex.tv/api/v2/pins?strong=false")
            .plexHeaders(context)
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("Couldn't get a code from plex.tv (HTTP ${resp.code})")
            val j = JSONObject(resp.body?.string() ?: "{}")
            PlexPin(j.getLong("id"), j.getString("code"))
        }
    }

    /** Returns the auth token once the user has entered the code at plex.tv/link, else null. */
    suspend fun checkPin(context: Context, pin: PlexPin): String? = withContext(Dispatchers.IO) {
        val j = JSONObject(getJson(context, "https://plex.tv/api/v2/pins/${pin.id}", null))
        j.optString("authToken").takeIf { it.isNotBlank() && it != "null" }
    }

    // ---------- servers ----------
    suspend fun servers(context: Context, userToken: String): List<PlexServer> = withContext(Dispatchers.IO) {
        val arr = JSONArray(getJson(context, "https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", userToken))
        val out = mutableListOf<PlexServer>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (!r.optString("provides").split(",").contains("server")) continue
            val conns = mutableListOf<PlexConnection>()
            r.optJSONArray("connections")?.let { c ->
                for (k in 0 until c.length()) {
                    val o = c.getJSONObject(k)
                    conns += PlexConnection(o.optString("uri"), o.optBoolean("local"), o.optBoolean("relay"))
                }
            }
            out += PlexServer(
                name = r.optString("name"),
                clientId = r.optString("clientIdentifier"),
                accessToken = r.optString("accessToken").ifBlank { userToken },
                owned = r.optBoolean("owned"),
                connections = conns
            )
        }
        out.sortedWith(compareByDescending<PlexServer> { it.owned }.thenBy { it.name.lowercase() })
    }

    private fun probe(context: Context, base: String, token: String): Boolean =
        runCatching { getJson(context, "$base/identity", token, probeClient); true }.getOrDefault(false)

    /**
     * Finds a working address for the server. The last one that worked is tried first (instant);
     * otherwise every address is tried at the same time and the first to answer wins, instead of
     * waiting out each dead local/IPv6 address in turn (which took minutes away from home).
     */
    suspend fun connect(context: Context, server: PlexServer): PlexSession = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedUri = prefs.getString("conn_${server.clientId}", null)
        if (cachedUri != null && probe(context, cachedUri, server.accessToken)) {
            return@withContext PlexSession(server.name, cachedUri, server.accessToken).also { saveLastSession(context, server.clientId, it) }
        }
        // Relay last: it's slow and capped, so give direct addresses a head start
        val ordered = server.connections.sortedBy { c -> if (c.relay) 1 else 0 }
        val results = kotlinx.coroutines.channels.Channel<String?>(ordered.size.coerceAtLeast(1))
        val winner = kotlinx.coroutines.coroutineScope {
            val jobs = ordered.map { c ->
                launch {
                    if (c.relay) kotlinx.coroutines.delay(1500)
                    val base = c.uri.trimEnd('/')
                    results.send(if (probe(context, base, server.accessToken)) base else null)
                }
            }
            var found: String? = null
            for (n in ordered.indices) {
                val r = kotlinx.coroutines.withTimeoutOrNull(12_000) { results.receive() }
                if (r != null) { found = r; break }
            }
            jobs.forEach { it.cancel() }
            found
        } ?: throw java.io.IOException("Couldn't reach ${server.name}. Is the server online and remote access on?")
        prefs.edit().putString("conn_${server.clientId}", winner).apply()
        PlexSession(server.name, winner, server.accessToken).also { saveLastSession(context, server.clientId, it) }
    }

    // ---------- instant start ----------
    private fun saveLastSession(context: Context, serverId: String, s: PlexSession) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_session", listOf(serverId, s.baseUrl, s.token, s.serverName).joinToString("\u0001"))
            .apply()
    }

    /** The server we used last time, if it still answers. Lets the Plex tab open without asking plex.tv first. */
    suspend fun resumeLastSession(context: Context): PlexSession? = withContext(Dispatchers.IO) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_session", null) ?: return@withContext null
        val p = raw.split("\u0001")
        if (p.size < 4 || p[0] != savedServerId(context)) return@withContext null
        val s = PlexSession(p[3], p[1], p[2])
        if (probe(context, s.baseUrl, s.token)) s else null
    }

    // ---------- library ----------
    suspend fun libraries(context: Context, s: PlexSession): List<PlexLibrary> = withContext(Dispatchers.IO) {
        val mc = JSONObject(getJson(context, "${s.baseUrl}/library/sections", s.token)).optJSONObject("MediaContainer")
        val dirs = mc?.optJSONArray("Directory") ?: JSONArray()
        (0 until dirs.length()).map { dirs.getJSONObject(it) }
            .filter { it.optString("type") == "movie" || it.optString("type") == "show" }
            .map { PlexLibrary(it.optString("key"), it.optString("title"), it.optString("type")) }
    }

    suspend fun onDeck(context: Context, s: PlexSession): List<PlexItem> =
        items(context, s, "/library/onDeck", 0, 30)

    suspend fun recentlyAdded(context: Context, s: PlexSession, sectionKey: String): List<PlexItem> =
        items(context, s, "/library/sections/$sectionKey/recentlyAdded", 0, 30)

    suspend fun all(context: Context, s: PlexSession, sectionKey: String, start: Int, size: Int): List<PlexItem> =
        items(context, s, "/library/sections/$sectionKey/all?sort=titleSort", start, size)

    suspend fun children(context: Context, s: PlexSession, ratingKey: String): List<PlexItem> =
        items(context, s, "/library/metadata/$ratingKey/children", 0, 500)

    suspend fun search(context: Context, s: PlexSession, query: String): List<PlexItem> =
        items(context, s, "/search?query=" + URLEncoder.encode(query, "UTF-8"), 0, 60)
            .filter { it.type == "movie" || it.type == "show" || it.type == "episode" }

    suspend fun metadata(context: Context, s: PlexSession, ratingKey: String): PlexItem? =
        items(context, s, "/library/metadata/$ratingKey", 0, 1).firstOrNull()

    private suspend fun items(context: Context, s: PlexSession, path: String, start: Int, size: Int): List<PlexItem> =
        withContext(Dispatchers.IO) {
            val sep = if (path.contains("?")) "&" else "?"
            val url = "${s.baseUrl}$path${sep}X-Plex-Container-Start=$start&X-Plex-Container-Size=$size"
            val mc = JSONObject(getJson(context, url, s.token)).optJSONObject("MediaContainer") ?: return@withContext emptyList()
            val arr = mc.optJSONArray("Metadata") ?: return@withContext emptyList()
            (0 until arr.length()).map { parseItem(arr.getJSONObject(it)) }
        }

    private fun parseItem(m: JSONObject): PlexItem {
        val partKey = m.optJSONArray("Media")?.optJSONObject(0)
            ?.optJSONArray("Part")?.optJSONObject(0)?.optString("key").orEmpty()
        val type = m.optString("type")
        return PlexItem(
            ratingKey = m.optString("ratingKey"),
            title = m.optString("title"),
            type = type,
            year = m.optInt("year"),
            summary = m.optString("summary"),
            thumb = m.optString("thumb"),
            grandparentThumb = m.optString("grandparentThumb"),
            art = m.optString("art"),
            grandparentTitle = m.optString("grandparentTitle"),
            parentIndex = m.optInt("parentIndex"),
            index = m.optInt("index"),
            viewOffset = m.optLong("viewOffset"),
            duration = m.optLong("duration"),
            viewCount = m.optInt("viewCount"),
            leafCount = m.optInt("leafCount"),
            viewedLeafCount = m.optInt("viewedLeafCount"),
            partKey = partKey
        )
    }

    // ---------- urls ----------
    fun imageUrl(s: PlexSession, path: String, width: Int, height: Int): String? {
        if (path.isBlank()) return null
        return "${s.baseUrl}/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1&url=" +
            URLEncoder.encode(path, "UTF-8") + "&X-Plex-Token=" + s.token
    }

    /** Direct-play URL for the file itself; the app's players handle the codecs. */
    fun streamUrl(s: PlexSession, partKey: String): String {
        val base = (s.baseUrl + partKey).toHttpUrl().newBuilder()
            .addQueryParameter("X-Plex-Token", s.token)
            .build()
        return base.toString()
    }

    // ---------- progress ----------
    /** Tells Plex where playback got to, so Continue Watching / watched status stay in sync. */
    suspend fun reportProgress(context: Context, s: PlexSession, ratingKey: String, positionMs: Long, durationMs: Long) =
        withContext(Dispatchers.IO) {
            runCatching {
                if (durationMs > 0 && positionMs >= durationMs * 0.9) {
                    getJson(context, "${s.baseUrl}/:/scrobble?identifier=com.plexapp.plugins.library&key=$ratingKey", s.token)
                } else if (positionMs > 10_000) {
                    getJson(
                        context,
                        "${s.baseUrl}/:/timeline?ratingKey=$ratingKey&key=%2Flibrary%2Fmetadata%2F$ratingKey" +
                            "&state=stopped&time=$positionMs&duration=$durationMs",
                        s.token
                    )
                }
                Unit
            }
            Unit
        }
}

class PlexAuthException : Exception("Plex sign-in expired")
