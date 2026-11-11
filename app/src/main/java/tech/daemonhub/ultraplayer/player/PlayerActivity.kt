package tech.daemonhub.ultraplayer.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.daemonhub.ultraplayer.data.Catalog
import tech.daemonhub.ultraplayer.data.Item
import tech.daemonhub.ultraplayer.data.Kind
import tech.daemonhub.ultraplayer.data.Prefs
import tech.daemonhub.ultraplayer.data.Xtream
import tech.daemonhub.ultraplayer.plex.PlexApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What to play. Passed to [PlayerActivity] as plain intent extras. */
data class PlayRequest(
    val url: String,
    val title: String,
    val type: String,                 // TYPE_LIVE / TYPE_VOD / TYPE_PLEX
    val startMs: Long = 0L,
    val resumeKey: String? = null,    // where to save the position (films/episodes)
    val plexRatingKey: String? = null,
    val liveCategoryId: String? = null, // lets up/down zap through this category
    val liveItemId: String? = null
) {
    companion object {
        const val TYPE_LIVE = "live"
        const val TYPE_VOD = "vod"
        const val TYPE_PLEX = "plex"
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        fun start(context: Context, r: PlayRequest) {
            val i = Intent(context, PlayerActivity::class.java).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("url", r.url)
                putExtra("title", r.title)
                putExtra("type", r.type)
                putExtra("start", r.startMs)
                putExtra("resumeKey", r.resumeKey)
                putExtra("plexKey", r.plexRatingKey)
                putExtra("liveCat", r.liveCategoryId)
                putExtra("liveId", r.liveItemId)
            }
            context.startActivity(i)
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var banner: LinearLayout
    private lateinit var bannerTitle: TextView
    private lateinit var bannerEpg: TextView
    private lateinit var status: TextView
    private var player: ExoPlayer? = null

    private var type = PlayRequest.TYPE_VOD
    private var resumeKey: String? = null
    private var plexKey: String? = null
    private var liveList: List<Item> = emptyList()
    private var liveIndex = -1
    private var retried = false
    private var bannerJob: Job? = null
    private var epgJob: Job? = null
    private var zapJob: Job? = null

    private val isLive get() = type == PlayRequest.TYPE_LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        type = intent.getStringExtra("type") ?: PlayRequest.TYPE_VOD
        resumeKey = intent.getStringExtra("resumeKey")
        plexKey = intent.getStringExtra("plexKey")
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val title = intent.getStringExtra("title") ?: ""
        val startMs = intent.getLongExtra("start", 0L)

        if (isLive) {
            val cat = intent.getStringExtra("liveCat")
            val id = intent.getStringExtra("liveId")
            if (cat != null) {
                liveList = Catalog.itemsIn(Kind.LIVE, cat)
                liveIndex = liveList.indexOfFirst { it.id == id }
            }
        }

        buildViews()
        createPlayer()
        play(url, startMs)
        if (isLive) showBanner(title, liveList.getOrNull(liveIndex))
    }

    // ---------------- views ----------------
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = !isLive
            controllerShowTimeoutMs = 4000
            controllerAutoShow = !isLive
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            keepScreenOn = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(playerView)

        // Channel banner for live TV (shown on start, zap and OK)
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(215, 20, 12, 40))
            }
            visibility = View.GONE
        }
        bannerTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        }
        bannerEpg = TextView(this).apply {
            setTextColor(Color.rgb(0xD9, 0xC8, 0xFF))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(6), 0, 0)
        }
        banner.addView(bannerTitle)
        banner.addView(bannerEpg)
        root.addView(banner, FrameLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(40), 0, 0, dp(40))
        })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(200, 0, 0, 0))
            }
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        setContentView(root)
        playerView.requestFocus()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // ---------------- player ----------------
    private fun createPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Xtream.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build().also { p ->
                p.playWhenReady = true
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) = handleError(error)
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            retried = false
                            status.visibility = View.GONE
                        }
                        if (state == Player.STATE_ENDED && !isLive) {
                            resumeKey?.let { Prefs.saveResume(it, 1, 1) } // finished: clear resume
                            finish()
                        }
                    }
                })
                playerView.player = p
            }
    }

    private fun play(url: String, startMs: Long) {
        val p = player ?: return
        status.visibility = View.GONE
        val item = MediaItem.Builder().setUri(url).build()
        p.setMediaItem(item, if (startMs > 0) startMs else C.TIME_UNSET)
        p.prepare()
        p.play()
    }

    private fun handleError(e: PlaybackException) {
        val p = player ?: return
        // Live streams often drop briefly: retry once before showing an error
        if (isLive && !retried) {
            retried = true
            showStatus("Reconnecting…")
            lifecycleScope.launch {
                delay(1500)
                p.prepare()
                p.play()
            }
            return
        }
        val msg = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused this stream"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the server"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This format isn't supported on this device"
            else -> "Playback failed (${e.errorCodeName})"
        }
        showStatus(if (isLive) "$msg\nPress up/down to try another channel" else msg)
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    // ---------------- live TV ----------------
    private fun showBanner(title: String, item: Item?) {
        val num = item?.num?.takeIf { it > 0 }?.let { "$it  " } ?: ""
        bannerTitle.text = num + title
        bannerEpg.text = ""
        banner.visibility = View.VISIBLE
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch {
            delay(5000)
            banner.visibility = View.GONE
        }
        val login = Prefs.login
        if (item != null && login != null && login.isXtream) {
            epgJob?.cancel()
            epgJob = lifecycleScope.launch {
                val epg = Xtream.shortEpg(login, item.id, 2)
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                bannerEpg.text = epg.mapIndexed { i, e ->
                    "${if (i == 0) "Now" else "Next"}  ${fmt.format(Date(e.startMs))}  ${e.title}"
                }.joinToString("\n")
            }
        }
    }

    private fun zap(delta: Int) {
        if (liveList.isEmpty()) return
        liveIndex = ((if (liveIndex < 0) 0 else liveIndex) + delta).mod(liveList.size)
        val item = liveList[liveIndex]
        showBanner(item.name, item)
        retried = false
        // Short delay so fast presses skip channels without starting each stream
        zapJob?.cancel()
        zapJob = lifecycleScope.launch {
            delay(450)
            val login = Prefs.login ?: return@launch
            play(Xtream.liveUrl(login, item), 0L)
        }
    }

    // ---------------- keys ----------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (isLive) {
            if (down) when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                    val item = liveList.getOrNull(liveIndex)
                    showBanner(item?.name ?: intent.getStringExtra("title") ?: "", item)
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    liveList.getOrNull(liveIndex)?.let {
                        val added = Prefs.toggleFavourite("live", it.id)
                        Catalog.favouritesVersion.value++
                        Toast.makeText(this, if (added) "Added to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
        // Films / episodes / Plex: left/right skip straight away when the controls are hidden
        if (down && !playerView.isControllerFullyVisible) {
            val p = player
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    p?.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); playerView.showController(); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    p?.seekTo(p.currentPosition + 30_000); playerView.showController(); return true
                }
            }
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    // ---------------- lifecycle ----------------
    private fun saveProgress() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        resumeKey?.let { Prefs.saveResume(it, pos, dur) }
        val pk = plexKey
        val session = PlexApi.activeSession
        if (pk != null && session != null && dur > 0) {
            val ctx = applicationContext
            CoroutineScope(Dispatchers.IO).launch { PlexApi.reportProgress(ctx, session, pk, pos, dur) }
        }
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
