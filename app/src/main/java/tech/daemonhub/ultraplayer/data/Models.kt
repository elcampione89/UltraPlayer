package tech.daemonhub.ultraplayer.data

enum class Kind { LIVE, MOVIE, SERIES }

data class Category(val id: String, val name: String)

/** One channel, film or series in a provider's list. Kept small: lists can hold 100k+ of these. */
data class Item(
    val kind: Kind,
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val ext: String? = null,       // container extension for films
    val rating: String? = null,
    val directUrl: String? = null, // M3U playlists give the URL directly
    val num: Int = 0               // channel number from the provider
) {
    val favKind: String get() = kind.name.lowercase()
}

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int,
    val ext: String?,
    val plot: String?,
    val image: String?,
    val durationSecs: Int,
    val directUrl: String? = null
)

data class SeriesInfo(
    val plot: String?,
    val cover: String?,
    val backdrop: String?,
    val genre: String?,
    val year: String?,
    val rating: String?,
    val seasons: Map<Int, List<Episode>>
)

data class VodInfo(
    val plot: String?,
    val genre: String?,
    val year: String?,
    val rating: String?,
    val durationText: String?,
    val cast: String?,
    val director: String?,
    val backdrop: String?
)

data class EpgEntry(val title: String, val description: String?, val startMs: Long, val endMs: Long)

/** Thrown for wrong username/password or an expired line. */
class LoginException(message: String) : Exception(message)
