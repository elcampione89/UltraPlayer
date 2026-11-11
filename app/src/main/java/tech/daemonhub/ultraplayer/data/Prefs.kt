package tech.daemonhub.ultraplayer.data

import android.content.Context
import android.content.SharedPreferences

data class Login(
    val type: String,        // "xtream" or "m3u"
    val server: String,      // Xtream base URL, no trailing slash
    val username: String,
    val password: String,
    val m3uUrl: String
) {
    val isXtream get() = type == "xtream"
}

/** Everything the app keeps on the device. Plain SharedPreferences: small and fast. */
object Prefs {
    private lateinit var main: SharedPreferences
    private lateinit var resume: SharedPreferences

    fun init(context: Context) {
        main = context.getSharedPreferences("ultra", Context.MODE_PRIVATE)
        resume = context.getSharedPreferences("ultra_resume", Context.MODE_PRIVATE)
    }

    // ---------- login ----------
    var login: Login?
        get() {
            val type = main.getString("login_type", null) ?: return null
            return Login(
                type,
                main.getString("server", "") ?: "",
                main.getString("user", "") ?: "",
                main.getString("pass", "") ?: "",
                main.getString("m3u", "") ?: ""
            )
        }
        set(value) {
            main.edit().apply {
                if (value == null) {
                    remove("login_type"); remove("server"); remove("user"); remove("pass"); remove("m3u")
                } else {
                    putString("login_type", value.type)
                    putString("server", value.server)
                    putString("user", value.username)
                    putString("pass", value.password)
                    putString("m3u", value.m3uUrl)
                }
            }.apply()
        }

    // ---------- favourites (live channels, films, series) ----------
    private fun favKey(kind: String, id: String) = "$kind:$id"

    fun isFavourite(kind: String, id: String): Boolean =
        main.getStringSet("favs", emptySet())?.contains(favKey(kind, id)) == true

    /** @return true if it is now a favourite */
    fun toggleFavourite(kind: String, id: String): Boolean {
        val set = (main.getStringSet("favs", emptySet()) ?: emptySet()).toMutableSet()
        val k = favKey(kind, id)
        val added = if (k in set) { set.remove(k); false } else { set.add(k); true }
        main.edit().putStringSet("favs", set).apply()
        return added
    }

    fun favourites(kind: String): Set<String> =
        (main.getStringSet("favs", emptySet()) ?: emptySet())
            .filter { it.startsWith("$kind:") }
            .map { it.removePrefix("$kind:") }
            .toSet()

    // ---------- last channel ----------
    var lastLiveCategory: String?
        get() = main.getString("last_live_cat", null)
        set(v) { main.edit().putString("last_live_cat", v).apply() }

    // ---------- resume positions ----------
    data class Resume(val positionMs: Long, val durationMs: Long, val updatedAt: Long)

    fun resumeFor(key: String): Resume? {
        val raw = resume.getString(key, null) ?: return null
        val p = raw.split("|")
        if (p.size < 3) return null
        return Resume(p[0].toLongOrNull() ?: 0, p[1].toLongOrNull() ?: 0, p[2].toLongOrNull() ?: 0)
    }

    fun saveResume(key: String, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && positionMs > durationMs * 0.93
        resume.edit().apply {
            if (finished || positionMs < 15_000) remove(key)
            else putString(key, "$positionMs|$durationMs|${System.currentTimeMillis()}")
        }.apply()
    }

    /** Most recent unfinished items, newest first. */
    fun recentResumes(limit: Int = 20): List<Pair<String, Resume>> =
        resume.all.mapNotNull { (k, v) ->
            val r = (v as? String)?.let { resumeFor(k) } ?: return@mapNotNull null
            k to r
        }.sortedByDescending { it.second.updatedAt }.take(limit)

    fun clearAll() {
        main.edit().clear().apply()
        resume.edit().clear().apply()
    }
}
