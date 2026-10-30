package com.osama.xtreamtv

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import coil.load
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Live TV list: a small muted preview of the highlighted channel in the top-right corner,
 * started after a short pause on a channel so scrolling through the list stays light.
 */
class ChannelPreview(private val context: Context, private val api: XtreamApi, private val prefs: Prefs) {

    companion object {
        /** Wait this long on a channel before opening its stream (1-connection accounts). */
        private const val DELAY_MS = 800L
        /** The server answered a .ts request with an HLS playlist: use HLS from now on. */
        private var tsIsHls = false
        const val WIDTH_DP = 336
        const val HEIGHT_DP = 189
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** Preview box + channel name, added to the top-right of the list area. */
    val view: View
    private val playerView: PlayerView
    private val label: TextView
    private val status: TextView
    private val logo: android.widget.ImageView
    private var player: ExoPlayer? = null
    private var pending: Runnable? = null
    private var currentId: String? = null
    /** The channel being previewed and how many times it was retried. */
    private var currentItem: StreamItem? = null
    private var retries = 0

    init {
        playerView = PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(0xFF000000.toInt())
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isFocusable = false
        }
        logo = android.widget.ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(120), dp(80), Gravity.CENTER).apply { bottomMargin = dp(18) }
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        status = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER or Gravity.BOTTOM)
                .apply { bottomMargin = dp(14) }
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 14f
        }
        val box = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(WIDTH_DP), dp(HEIGHT_DP))
            setBackgroundColor(0xFF000000.toInt())
            addView(playerView)
            addView(logo)
            addView(status)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat())
            }
        }
        label = TextView(context).apply {
            setTextColor(context.getColor(R.color.text))
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(dp(WIDTH_DP), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { topMargin = dp(6) }
            addView(box)
            addView(label)
            visibility = View.INVISIBLE
        }
    }

    /** The highlighted channel changed: preview it after a short pause. */
    fun focus(item: StreamItem) {
        pending?.let { view.removeCallbacks(it) }
        view.visibility = View.VISIBLE
        label.text = listOfNotNull(item.num, item.name).joinToString("  ")
        if (item.id == currentId && player != null) return
        status.text = "Loading preview…"
        status.visibility = View.VISIBLE
        // While it loads: the channel logo instead of a black box.
        logo.load(item.image) { crossfade(false) }
        logo.visibility = View.VISIBLE
        // Drop the old stream at once: providers often allow one connection, and the next
        // channel can only start once the server has let go of this one.
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        currentId = null
        val start = Runnable { play(item) }
        pending = start
        view.postDelayed(start, DELAY_MS)
    }

    private fun play(item: StreamItem) {
        if (currentItem?.id != item.id) retries = 0
        currentItem = item
        currentId = item.id
        val p = player ?: createPlayer().also { player = it }
        // TS is one continuous stream and starts much faster than HLS (playlist + segments);
        // use it for the preview unless the server only offers HLS.
        val formats = prefs.formats.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val hls = tsIsHls || prefs.streamType == 1 || (formats.isNotEmpty() && "ts" !in formats)
        val media = MediaItem.Builder()
            .setUri(api.liveUrl(item.id, if (hls) "m3u8" else "ts"))
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        p.setMediaItem(media)
        p.prepare()
        p.playWhenReady = true
    }

    private fun createPlayer(): ExoPlayer {
        val http = PlaybackTuning.http(connectTimeoutMs = 2_000, readTimeoutMs = 6_000)
        // Start on the first half second of video instead of waiting for a full buffer.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 4_000, 400, 800)
            .build()
        // A small preview: ask for the lowest quality when the stream has several.
        val tracks = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setMaxVideoSizeSd().setForceLowestBitrate(true))
        }
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(PlaybackTuning.QuickRetry(3, openRetries = 1)))
            .setLoadControl(loadControl)
            .setTrackSelector(tracks)
            .build().also { p ->
            p.volume = 0f
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) status.visibility = View.GONE
                }

                override fun onRenderedFirstFrame() {
                    logo.visibility = View.GONE
                    status.visibility = View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    p.stop()
                    // Not a TS stream (some servers send an HLS playlist): try again as HLS at once.
                    if (!tsIsHls && error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                        tsIsHls = true
                        currentItem?.let { play(it) }
                        return
                    }
                    // The server is often still letting go of the previous channel: try again shortly.
                    // A stream server that does not answer was already retried (QuickRetry).
                    val unreachable = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                    val item = currentItem
                    if (item != null && retries < 3 && !unreachable) {
                        retries++
                        status.text = "Connecting…"
                        val again = Runnable { play(item) }
                        pending = again
                        view.postDelayed(again, 1_500)
                        return
                    }
                    status.text = "Preview not available"
                    status.visibility = View.VISIBLE
                    currentId = null
                }
            })
        }
    }

    /** Leaving the screen (or opening a channel): stop and free the connection. */
    fun release() {
        pending?.let { view.removeCallbacks(it) }
        pending = null
        player?.release()
        player = null
        playerView.player = null
        currentId = null
        view.visibility = View.INVISIBLE
    }
}
