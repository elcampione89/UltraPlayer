package tech.daemonhub.ultraplayer.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import tech.daemonhub.ultraplayer.data.Xtream

/** One place that builds ExoPlayer the same way for the full-screen player and the live preview. */
@androidx.annotation.OptIn(UnstableApi::class)
object Players {
    fun create(context: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Xtream.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        // FFmpeg audio decoder as a fallback: plays AC3/E-AC3/DTS/TrueHD on devices that can't
        // (e.g. Plex films showing "Audio: None"), while the device still decodes what it can.
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .build()
    }
}
