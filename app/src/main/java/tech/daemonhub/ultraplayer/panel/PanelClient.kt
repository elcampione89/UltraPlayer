package tech.daemonhub.ultraplayer.panel

import android.content.Context
import android.os.Build
import tech.daemonhub.ultraplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PanelServer(val name: String, val url: String)

data class PanelUpdate(
    val versionCode: Int,
    val versionName: String,
    val urlArm32: String,
    val urlArm64: String,
    val urlUniversal: String,
    val changelog: String,
    val force: Boolean
) {
    /** Picks the APK that matches this device's CPU. Firesticks are armeabi-v7a. */
    fun bestUrl(): String {
        val abis = Build.SUPPORTED_ABIS.toList()
        // Ultra Player ships one APK for every device, so the universal link wins when it's set
        val order = when {
            abis.firstOrNull() == "arm64-v8a" -> listOf(urlUniversal, urlArm64, urlArm32)
            abis.contains("armeabi-v7a") -> listOf(urlUniversal, urlArm32)
            else -> listOf(urlUniversal)
        }
        return order.firstOrNull { it.isNotBlank() } ?: ""
    }

    // Never offer the build that's already installed, even if the panel's version code is off
    val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE &&
        !versionName.trim().equals(BuildConfig.VERSION_NAME, ignoreCase = true) &&
        bestUrl().isNotBlank()
}

data class PanelMessage(val id: String, val title: String, val body: String, val once: Boolean)

data class PanelConfig(
    val appName: String,
    val allowCustomDns: Boolean,
    val allowM3u: Boolean,
    val plexEnabled: Boolean,
    val servers: List<PanelServer>,
    val update: PanelUpdate?,
    val messages: List<PanelMessage>,
    val catTop: List<String> = emptyList(),
    val catBottom: List<String> = listOf("24/7"),
    val catHide: List<String> = emptyList(),
    val catHideUnlisted: Boolean = false,
    val catSortAz: Boolean = false
) {
    companion object {
        val DEFAULT = PanelConfig("Ultra Player", true, true, true, emptyList(), null, emptyList())

        fun parse(raw: String): PanelConfig {
            val j = JSONObject(raw)
            val servers = mutableListOf<PanelServer>()
            j.optJSONArray("dns")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val url = s.optString("url").trim()
                    if (url.isNotEmpty()) servers += PanelServer(s.optString("name", url), url)
                }
            }
            val messages = mutableListOf<PanelMessage>()
            j.optJSONArray("messages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    messages += PanelMessage(
                        m.optString("id"), m.optString("title"), m.optString("body"), m.optBoolean("once", true)
                    )
                }
            }
            val update = j.optJSONObject("update")?.let { u ->
                PanelUpdate(
                    versionCode = u.optInt("version_code", 0),
                    versionName = u.optString("version_name"),
                    urlArm32 = u.optString("url_arm32"),
                    urlArm64 = u.optString("url_arm64"),
                    urlUniversal = u.optString("url_universal"),
                    changelog = u.optString("changelog"),
                    force = u.optBoolean("force", false)
                )
            }
            fun strList(key: String, default: List<String>): List<String> {
                val arr = j.optJSONObject("categories")?.optJSONArray(key) ?: return default
                return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
            }
            val cats = j.optJSONObject("categories")
            return PanelConfig(
                appName = j.optString("app_name", "Ultra Player").ifBlank { "Ultra Player" },
                allowCustomDns = j.optBoolean("allow_custom_dns", true),
                allowM3u = j.optBoolean("allow_m3u", true),
                plexEnabled = j.optBoolean("plex_enabled", true),
                servers = servers,
                update = update,
                messages = messages,
                catTop = strList("top", emptyList()),
                catBottom = strList("bottom", listOf("24/7")),
                catHide = strList("hide", emptyList()),
                catHideUnlisted = cats?.optBoolean("hide_unlisted", false) ?: false,
                catSortAz = cats?.optString("sort") == "az"
            )
        }
    }
}

/**
 * Reads the panel's api.php. The last good copy is cached so the login screen
 * still has the server list if the panel is briefly unreachable.
 */
object PanelClient {
    private const val PREFS = "panel_cache"
    private const val KEY_JSON = "config_json"
    private const val KEY_SEEN = "seen_messages"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _config = MutableStateFlow(PanelConfig.DEFAULT)
    val config: StateFlow<PanelConfig> = _config

    val isConfigured: Boolean get() = BuildConfig.PANEL_URL.isNotBlank()

    fun loadCached(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null) ?: return
        runCatching { _config.value = PanelConfig.parse(raw) }
    }

    suspend fun refresh(context: Context): PanelConfig? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        try {
            val req = Request.Builder()
                .url(BuildConfig.PANEL_URL)
                .header("User-Agent", "UltraPlayer/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val parsed = PanelConfig.parse(body)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_JSON, body).apply()
                _config.value = parsed
                parsed
            }
        } catch (e: Exception) {
            android.util.Log.w("PanelClient", "Panel unreachable: ${e.message}")
            null
        }
    }

    fun unseenMessages(context: Context, cfg: PanelConfig): List<PanelMessage> {
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        return cfg.messages.filter { !it.once || it.id !in seen }
    }

    fun markSeen(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = (prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()).toMutableSet()
        seen += id
        prefs.edit().putStringSet(KEY_SEEN, seen).apply()
    }
}
