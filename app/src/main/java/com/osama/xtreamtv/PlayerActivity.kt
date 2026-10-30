package com.osama.xtreamtv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_START = "start"
        private const val OVERLAY_TIMEOUT_MS = 6_000L
        private const val TAG = "XtreamPlayer"

        fun liveIntent(context: Context, index: Int): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_LIVE, true)
                .putExtra(EXTRA_INDEX, index)

        /** [askResume]: with a saved position, ask "Resume or start over" before playing. */
        fun vodIntent(
            context: Context, url: String, title: String, key: String, startMs: Long, entry: WatchEntry?,
            askResume: Boolean = true,
        ): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_LIVE, false)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_START, startMs)
                .putExtra(EXTRA_ASK_RESUME, askResume)
                .apply { if (entry != null) putExtra(EXTRA_WATCH, entry.toJson().toString()) }

        /** A past programme from the TV guide (server timeshift). */
        fun catchupIntent(context: Context, url: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_LIVE, false)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KEY, "catchup")
                .putExtra(EXTRA_CATCHUP, true)
                .putExtra(EXTRA_ASK_RESUME, false)

        private const val EXTRA_WATCH = "watch"
        private const val EXTRA_ASK_RESUME = "ask_resume"
        private const val EXTRA_CATCHUP = "catchup"
        private val SUBTITLE_SIZES = listOf("Small" to 0.8f, "Medium" to 1.0f, "Large" to 1.3f, "Extra large" to 1.6f)
        private val PICTURE_SIZES = listOf(
            "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
            "Zoom (fill screen)" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            "Stretch" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
        )
        private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        const val SUBTITLES_OFF = "off"
        private const val VOD_RETRIES = 5
        private val PARSE_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
    }

    private lateinit var api: XtreamApi
    private lateinit var prefs: Prefs
    private lateinit var playerView: PlayerView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var overlay: View
    private lateinit var overlayLogo: ImageView
    private lateinit var overlayName: TextView
    private lateinit var overlayNow: TextView
    private lateinit var overlayNext: TextView
    private lateinit var controls: VodControls
    private lateinit var overlayClock: TextView
    private lateinit var overlayQuality: TextView
    private lateinit var overlayProgress: ProgressBar
    /** The start-up title has been shown for the current video. */
    private var titleShown = false
    private lateinit var episodesPanel: View
    private lateinit var episodesSeries: TextView
    private lateinit var episodesList: RecyclerView
    private lateinit var episodeAdapter: TextAdapter<Episode>
    private lateinit var channelsPanel: View
    private lateinit var channelsTitle: TextView
    private lateinit var channelsList: RecyclerView
    private lateinit var channelAdapter: ItemAdapter
    private lateinit var actionCard: View
    private lateinit var actionText: TextView
    private lateinit var actionButton: TextView
    private lateinit var playFlash: TextView
    private lateinit var seekForward: View
    private lateinit var seekBack: View
    private lateinit var seekForwardText: TextView
    private lateinit var seekBackText: TextView
    private lateinit var seekForwardTime: TextView
    private lateinit var seekBackTime: TextView
    private lateinit var numberEntry: TextView

    // Resume prompt, catch-up, sleep timer, speed.
    private var askResume = true
    private var resumeAsked = false
    private var isCatchup = false
    private var sleepAtEnd = false
    private var sleepAt = 0L
    private var speed = 1f
    private val sleepNow = Runnable {
        player?.playWhenReady = false
        Toast.makeText(this, "Sleep timer: playback stopped", Toast.LENGTH_LONG).show()
        finish()
    }

    // Live: channel number entry, last channel, pause.
    private var numberBuffer = ""
    private val commitNumber = Runnable { commitChannelNumber() }
    private var currentChannel: StreamItem? = null
    private var currentList: List<StreamItem> = emptyList()
    private var currentListTitle: String? = null
    private var lastChannel: Triple<List<StreamItem>, StreamItem, String?>? = null
    private var liveResumedAt = 0L

    // "Next episode in Ns" prompt near the end of an episode.
    private enum class CardAction { NONE, NEXT_EPISODE }
    private var cardAction = CardAction.NONE
    /** Back hid the prompt for this episode: don't show it again (it still moves on at the end). */
    private var nextDismissed = false
    private var swallowOkRelease = false
    private val ticker = object : Runnable {
        override fun run() {
            updateEpisodePrompts()
            playerView.postDelayed(this, 500)
        }
    }

    private var player: ExoPlayer? = null
    private var isLive = false
    private var currentUrl = ""
    private var probed = false
    /** Movies / episodes: reconnect attempts after a network or server error (reset once playing). */
    private var vodRetries = 0
    private val retryVod = Runnable {
        player?.let {
            it.prepare()
            it.playWhenReady = true
        }
    }
    private var vodMimeType: String? = null

    // Live state
    private var channelIndex = 0
    private var fallbackUrl: String? = null
    private var triedFallback = false
    private var epgJob: Job? = null
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    // Save progress regularly too, so a crash or power cut doesn't lose the place.
    private val autoSave = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && !isLive && p.isPlaying) saveVodPosition(p)
            playerView.postDelayed(this, 30_000)
        }
    }

    private fun controlsShown() = ::controls.isInitialized && controls.isShown
    private var hoverX = 0f
    private var hoverY = 0f
    private var lastMouseClick = 0L

    // VOD state
    private var vodUrl = ""
    private var vodTitle = ""
    private var resumeKey = ""
    private var startPosition = 0L
    private var watchEntry: WatchEntry? = null

    // Episode queue (series only): enables next/previous and the episode list.
    private var episodes: List<Episode> = emptyList()
    private var episodeIndex = -1
    private var seriesId: String? = null
    private var seriesName = ""
    private var seriesCover: String? = null
    private val hasNextEpisode get() = episodes.isNotEmpty() && episodeIndex < episodes.size - 1
    private val hasPreviousEpisode get() = episodes.isNotEmpty() && episodeIndex > 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Session.api(this) ?: return goToLogin()
        prefs = Prefs(this)
        Appearance.apply(this)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.player_view)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error)
        overlay = findViewById(R.id.overlay)
        overlayLogo = findViewById(R.id.overlay_logo)
        overlayName = findViewById(R.id.overlay_name)
        overlayNow = findViewById(R.id.overlay_now)
        overlayNext = findViewById(R.id.overlay_next)
        overlayClock = findViewById(R.id.overlay_clock)
        overlayQuality = findViewById(R.id.overlay_quality)
        overlayProgress = findViewById(R.id.overlay_progress)
        controls = VodControls(findViewById(R.id.vod_controls), controlsHost)
        episodesPanel = findViewById(R.id.episodes_panel)
        episodesSeries = findViewById(R.id.episodes_series)
        episodesList = findViewById(R.id.episodes_list)
        channelsPanel = findViewById(R.id.channels_panel)
        channelsTitle = findViewById(R.id.channels_title)
        channelsList = findViewById(R.id.channels_list)
        actionCard = findViewById(R.id.action_card)
        actionText = findViewById(R.id.action_text)
        actionButton = findViewById(R.id.action_button)
        playFlash = findViewById(R.id.play_flash)
        seekForward = findViewById(R.id.seek_forward)
        seekBack = findViewById(R.id.seek_back)
        seekForwardText = findViewById(R.id.seek_forward_text)
        seekBackText = findViewById(R.id.seek_back_text)
        seekForwardTime = findViewById(R.id.seek_forward_time)
        seekBackTime = findViewById(R.id.seek_back_time)
        numberEntry = findViewById(R.id.number_entry)
        applySubtitleStyle()
        playerView.resizeMode = PICTURE_SIZES[prefs.pictureSize.coerceIn(0, PICTURE_SIZES.size - 1)].second

        isLive = intent.getBooleanExtra(EXTRA_LIVE, false)
        if (isLive) {
            if (Session.zapList.isEmpty()) return finish()
            channelIndex = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
                .coerceIn(0, Session.zapList.size - 1)
            playerView.useController = false
            setupChannelPanel()
        } else {
            vodUrl = intent.getStringExtra(EXTRA_URL) ?: return finish()
            vodTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
            resumeKey = intent.getStringExtra(EXTRA_KEY) ?: vodUrl
            startPosition = intent.getLongExtra(EXTRA_START, 0L)
            watchEntry = intent.getStringExtra(EXTRA_WATCH)?.let {
                try { watchEntryFromJson(JSONObject(it)) } catch (e: Exception) { null }
            }
            isCatchup = intent.getBooleanExtra(EXTRA_CATCHUP, false)
            askResume = intent.getBooleanExtra(EXTRA_ASK_RESUME, true)
            // Catch-up streams are plain MPEG-TS; the HLS guess only applies to movies/episodes.
            if (prefs.vodIsHls && !isCatchup) vodMimeType = MimeTypes.APPLICATION_M3U8
            // Our own controls (VodControls) replace the standard ones.
            playerView.useController = false
            // After using the mouse, the first remote key only leaves touch mode and focuses the
            // video; with the controls open, pass that focus on to Pause.
            playerView.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && controlsShown()) controls.show() }
            // Mouse click on the video shows / hides them (PlayerView ignores clicks without its controller).
            playerView.setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    v.performClick()
                    lastMouseClick = SystemClock.uptimeMillis()
                    hoverX = ev.x
                    hoverY = ev.y
                    if (controlsShown()) controls.hide() else controls.show()
                }
                true
            }
            playerView.requestFocus()
            setupVodChrome()
            resetEpisodePrompts()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    episodesPanel.visibility == View.VISIBLE -> closeEpisodes()
                    channelsPanel.visibility == View.VISIBLE -> closeChannels()
                    isLive && overlay.visibility == View.VISIBLE -> overlay.visibility = View.GONE
                    !isLive && cardAction != CardAction.NONE && !controlsShown() -> dismissCard()
                    !isLive && controlsShown() -> controls.hide()
                    else -> finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, channelIndex)
    }

    override fun onStart() {
        super.onStart()
        if (!::playerView.isInitialized) return
        // Back from the TV guide with a channel picked.
        Session.pendingChannel?.let { index ->
            Session.pendingChannel = null
            if (isLive) channelIndex = index.coerceIn(0, (Session.zapList.size - 1).coerceAtLeast(0))
        }
        initPlayer()
        if (!isLive) {
            playerView.postDelayed(autoSave, 30_000)
            playerView.post(ticker)
        }
    }

    override fun onPause() {
        super.onPause()
        // Save here, not only in onStop: the previous screen resumes before this one stops.
        val p = player
        if (p != null && !isLive) saveVodPosition(p)
    }

    override fun onStop() {
        super.onStop()
        if (::playerView.isInitialized) {
            playerView.removeCallbacks(autoSave)
            playerView.removeCallbacks(ticker)
        }
        releasePlayer()
    }

    private fun initPlayer() {
        if (player != null) return
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        // Live: retry a failing URL only once so the .ts fallback / error shows quickly.
        val sources = DefaultMediaSourceFactory(http)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(if (isLive) 1 else 3))
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .build()
        p.addListener(playerListener)
        applyTrackPreferences(p)
        player = p
        playerView.player = if (isLive) p else EpisodeAwarePlayer(p)

        if (isLive) playChannel(channelIndex) else startVod()
    }

    private fun startVod() {
        val p = player ?: return
        currentUrl = vodUrl
        // Settings > Player > "Start from the beginning" (an explicit Resume button still resumes).
        if (askResume && !resumeAsked && startPosition > 5_000 && prefs.resumeMode == 2) startPosition = 0
        p.setMediaItem(mediaItem(vodUrl, vodMimeType))
        if (startPosition > 0) p.seekTo(startPosition)
        p.prepare()
        p.setPlaybackSpeed(speed)
        if (askResume && !resumeAsked && startPosition > 5_000 && prefs.resumeMode == 0) {
            resumeAsked = true
            p.playWhenReady = false
            askResumeOrRestart()
        } else {
            p.playWhenReady = true
        }
    }

    private fun askResumeOrRestart() {
        val pos = startPosition
        AlertDialog.Builder(this)
            .setTitle(vodTitle)
            .setItems(arrayOf("▶   Resume from ${formatDuration(pos)}", "↺   Start from the beginning")) { _, which ->
                val p = player ?: return@setItems
                if (which == 1) {
                    p.seekTo(0)
                    startPosition = 0
                }
                p.playWhenReady = true
            }
            .setOnCancelListener { player?.playWhenReady = true }
            .show()
    }

    private fun mediaItem(url: String, mimeType: String?): MediaItem =
        MediaItem.Builder().setUri(url).apply { if (mimeType != null) setMimeType(mimeType) }.build()

    private fun releasePlayer() {
        val p = player ?: return
        if (!isLive) saveVodPosition(p)
        p.removeListener(playerListener)
        p.release()
        player = null
        playerView.player = null
    }

    private fun saveVodPosition(p: Player) {
        if (isCatchup) return
        val pos = p.currentPosition
        val duration = p.duration
        // "Finished" = last minute, or last 5% for short videos.
        if (duration != C.TIME_UNSET && duration > 0 && pos > duration - minOf(60_000L, duration / 20)) {
            // Watched to the end: start from the beginning next time and drop it from Continue watching.
            prefs.removeProgress(resumeKey)
            prefs.setWatched(resumeKey, true)
            startPosition = 0
        } else if (pos > 5_000) {
            prefs.savePosition(resumeKey, pos)
            startPosition = pos
            watchEntry?.let { e ->
                val dur = if (duration != C.TIME_UNSET && duration > 0) duration else 0L
                prefs.saveProgress(e.copy(position = pos, duration = dur, updated = System.currentTimeMillis()))
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            progress.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) {
                errorView.visibility = View.GONE
                vodRetries = 0
                // Show what's playing for a few seconds when a movie or episode starts.
                if (!isLive && !titleShown) {
                    titleShown = true
                    controls.showTitleOnly()
                }
            }
            if (state == Player.STATE_ENDED && !isLive) {
                if (!isCatchup) {
                    prefs.removeProgress(resumeKey)
                    prefs.setWatched(resumeKey, true)
                }
                // Series: carry on with the next episode, like a TV binge (unless the sleep timer says stop).
                if (prefs.autoNext && hasNextEpisode && !sleepAtEnd) playEpisodeAt(episodeIndex + 1) else finish()
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (isLive) showLiveQuality()
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            // A live stream paused for a while often drops its connection: tune in again instead of failing.
            if (isLive && liveResumedAt > 0 && SystemClock.uptimeMillis() - liveResumedAt < 60_000) {
                liveResumedAt = 0
                playChannel(channelIndex)
                return
            }
            if (isLive && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (!probed && error.errorCode in PARSE_ERRORS) {
                // The file extension lies about the content: look at what the server really sends.
                probed = true
                probeAndRetry(currentUrl, error)
                return
            }
            // Movies / episodes: a dropped or refused connection (often the provider still holding
            // the previous stream) is retried up to 5 times from the same spot before giving up.
            if (!isLive && vodRetries < VOD_RETRIES && error.errorCode.let { it in 2000..2999 || it == PlaybackException.ERROR_CODE_TIMEOUT }) {
                vodRetries++
                val position = p.currentPosition
                errorView.text = "Connection lost. Reconnecting…  ($vodRetries of $VOD_RETRIES)"
                errorView.visibility = View.VISIBLE
                progress.visibility = View.VISIBLE
                if (position > 0) p.seekTo(position)
                playerView.removeCallbacks(retryVod)
                playerView.postDelayed(retryVod, 2_000L * vodRetries)
                return
            }
            val fallback = fallbackUrl
            if (isLive && !triedFallback && fallback != null) {
                // Some servers don't serve HLS: retry the channel once as MPEG-TS.
                triedFallback = true
                playUrl(fallback)
                return
            }
            showPlaybackError(describe(error))
        }
    }

    private fun probeAndRetry(url: String, error: PlaybackException) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val probe = probeStream(url)
            Log.w(TAG, "Unrecognized stream format; probe: ${probe?.summary() ?: "failed"}")
            when {
                probe != null && probe.isHls -> {
                    if (isLive) {
                        playUrl(url, MimeTypes.APPLICATION_M3U8)
                    } else {
                        // Remember it so the next movie/episode starts as HLS straight away.
                        prefs.vodIsHls = true
                        vodMimeType = MimeTypes.APPLICATION_M3U8
                        startVod()
                    }
                }
                probe != null && probe.code in 200..299 && !isLive && vodMimeType != null -> {
                    // We assumed HLS but this one is a real file: play it as-is.
                    prefs.vodIsHls = false
                    vodMimeType = null
                    startVod()
                }
                probe != null && probe.isHtml ->
                    showPlaybackError("The server sent a web page instead of the video (HTTP ${probe.code}).")
                probe != null && probe.code !in 200..299 ->
                    showPlaybackError("The server refused the stream (HTTP ${probe.code}).")
                else -> {
                    val fallback = fallbackUrl
                    if (isLive && !triedFallback && fallback != null) {
                        triedFallback = true
                        playUrl(fallback)
                    } else {
                        showPlaybackError(describe(error))
                    }
                }
            }
        }
    }

    private fun showPlaybackError(reason: String) {
        progress.visibility = View.GONE
        val what = if (isLive) "This channel can't be played right now." else "This video can't be played."
        val hint = if (isLive) "\n\nPress Up/Down to change channel." else ""
        errorView.text = "$what\n$reason$hint"
        errorView.visibility = View.VISIBLE
        if (!isLive) controls.hide()
    }

    private fun describe(error: PlaybackException): String {
        val cause = error.cause?.message
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the stream ($cause)."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Network connection failed."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "The stream format isn't supported."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This TV can't decode the video format."
            else -> "${error.errorCodeName}${cause?.let { ": $it" } ?: ""}"
        }
    }

    // ---- Live TV ----

    private fun hlsAllowed(): Boolean = prefs.useHls()

    /** Jump sizes from Settings > Player. */
    private fun forwardMs() = prefs.seekForwardSeconds * 1000L
    private fun backMs() = prefs.seekBackSeconds * 1000L

    private fun playChannel(index: Int) {
        val list = Session.zapList
        if (list.isEmpty()) return finish()
        channelIndex = ((index % list.size) + list.size) % list.size
        val channel = list[channelIndex]
        // Remember where we came from for "Last channel".
        val previous = currentChannel
        if (previous != null && previous.id != channel.id) lastChannel = Triple(currentList, previous, currentListTitle)
        currentChannel = channel
        currentList = list
        currentListTitle = Session.zapTitle
        playFlash.visibility = View.GONE
        val hls = hlsAllowed()
        fallbackUrl = if (hls) api.liveUrl(channel.id, "ts") else null
        triedFallback = false
        probed = false
        errorView.visibility = View.GONE
        playUrl(api.liveUrl(channel.id, if (hls) "m3u8" else "ts"))
        showOverlay(channel)
    }

    private fun playUrl(url: String, mimeType: String? = null) {
        val p = player ?: return
        currentUrl = url
        p.setMediaItem(mediaItem(url, mimeType))
        p.prepare()
        p.playWhenReady = true
    }

    private fun showOverlay(channel: StreamItem) {
        overlayName.text = listOfNotNull(channel.num, channel.name).joinToString("   ")
        overlayLogo.load(channel.image) {
            crossfade(false)
            fallback(R.drawable.placeholder)
            error(R.drawable.placeholder)
        }
        overlayNow.text = "Loading programme guide…"
        overlayNext.text = ""
        overlayClock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        overlayProgress.visibility = View.GONE
        showLiveQuality()
        overlay.visibility = View.VISIBLE
        overlay.removeCallbacks(hideOverlay)
        overlay.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS)

        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
            try {
                val epg = api.shortEpg(channel.id)
                val now = epg.getOrNull(0)
                val next = epg.getOrNull(1)
                overlayNow.text = now?.let { "Now   ${timeRange(it)}   ${it.title}" } ?: "No programme information"
                val start = now?.start
                val end = now?.end
                if (start != null && end != null && end > start) {
                    val nowSec = System.currentTimeMillis() / 1000
                    overlayProgress.progress = ((nowSec - start) * 1000 / (end - start)).toInt().coerceIn(0, 1000)
                    overlayProgress.visibility = View.VISIBLE
                }
                overlayNext.text = next?.let { "Next   ${timeRange(it)}   ${it.title}" } ?: ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                overlayNow.text = "No programme information"
            }
        }
    }

    /** Glass "1080p" tag next to the channel name. */
    private fun showLiveQuality() {
        val h = player?.videoSize?.height ?: 0
        val label = when {
            h <= 0 -> ""
            h >= 2000 -> "4K"
            h >= 1000 -> "1080p"
            h >= 700 -> "720p"
            else -> "${h}p"
        }
        overlayQuality.text = label
        overlayQuality.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun timeRange(entry: EpgEntry): String {
        val start = entry.start ?: return ""
        val end = entry.end
        return if (end != null) "${formatClock(start)}–${formatClock(end)}" else formatClock(start)
    }

    private fun toggleOverlay() {
        if (overlay.visibility == View.VISIBLE) {
            overlay.removeCallbacks(hideOverlay)
            overlay.visibility = View.GONE
        } else {
            Session.zapList.getOrNull(channelIndex)?.let { showOverlay(it) }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isLive) {
            // Some TVs deliver the mouse back button as a Back key from the mouse: treat it as −5 s.
            if (::playerView.isInitialized && event.keyCode == KeyEvent.KEYCODE_BACK &&
                event.isFromSource(InputDevice.SOURCE_MOUSE) && episodesPanel.visibility != View.VISIBLE
            ) {
                if (event.action == KeyEvent.ACTION_DOWN) quickSeek(-backMs())
                return true
            }
            if (!::playerView.isInitialized || event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            // The OK that answered a prompt must not also "click" play/pause when it is released.
            if (swallowOkRelease && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                if (event.action == KeyEvent.ACTION_UP) swallowOkRelease = false
                return true
            }
            // The episode list is a normal focusable list: plain D-pad navigation.
            if (episodesPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
            // Remote play/pause acts on the first press; with the controls hidden a small icon shows.
            val playKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || event.keyCode == KeyEvent.KEYCODE_SPACE
            if (playKey) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    player?.let { p ->
                        p.playWhenReady = when (event.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY -> true
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> false
                            else -> !p.playWhenReady
                        }
                        if (controlsShown()) {
                            controls.poke()
                            controls.refresh()
                        } else {
                            showPlayPauseFlash(p.playWhenReady)
                        }
                    }
                }
                return true
            }
            // Remote ⏩/⏪ buttons (remotes send different codes for them) always seek.
            val mediaSeek = when (event.keyCode) {
                KeyEvent.KEYCODE_FORWARD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> forwardMs()
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> -backMs()
                else -> 0L
            }
            if (mediaSeek != 0L) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (controlsShown()) {
                        seekVod(mediaSeek)
                        controls.poke()
                        controls.refresh()
                    } else {
                        quickSeek(mediaSeek)
                    }
                }
                return true
            }
            if (episodes.isNotEmpty() && event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (hasNextEpisode) playEpisodeAt(episodeIndex + 1)
                        return true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (hasPreviousEpisode) playEpisodeAt(episodeIndex - 1)
                        return true
                    }
                    KeyEvent.KEYCODE_GUIDE -> {
                        openEpisodes()
                        return true
                    }
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (episodes.isNotEmpty()) openEpisodes() else showSettings()
                }
                return true
            }
            // Controls open: normal D-pad navigation between the buttons; every key restarts the 5 s.
            if (controlsShown()) {
                controls.poke()
                // Opened with the mouse: nothing has focus yet, so the first key lands on Pause.
                if (!controls.hasFocus) {
                    if (event.action == KeyEvent.ACTION_DOWN) controls.show()
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.action == KeyEvent.ACTION_DOWN && controls.moveFocus(event.keyCode)) return true
                    if (event.action == KeyEvent.ACTION_UP) return true
                }
                return super.dispatchKeyEvent(event)
            }
            // Controls hidden: Right = +10 s, Left = −5 s without opening them.
            val seekDelta = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> forwardMs()
                KeyEvent.KEYCODE_DPAD_LEFT -> -backMs()
                else -> 0L
            }
            if (seekDelta != 0L) {
                if (event.action == KeyEvent.ACTION_DOWN) quickSeek(seekDelta)
                return true
            }
            val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            // OK answers the "Next episode" prompt.
            if (cardAction != CardAction.NONE && isOk) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    swallowOkRelease = true
                    performCardAction()
                }
                return true
            }
            // OK / Up / Down open the controls with focus on Pause (the OK release must not press it).
            if (isOk || event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (isOk) swallowOkRelease = true
                    controls.show()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (channelsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        // Number keys: type a channel number.
        val digit = when (event.keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> event.keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0) {
            if (event.action == KeyEvent.ACTION_DOWN) typeChannelDigit(digit)
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleLivePause(event.keyCode)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showLiveOptions()
                return true
            }
            KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) switchToLastChannel()
                return true
            }
            KeyEvent.KEYCODE_GUIDE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openGuide()
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_DOWN) openChannels()
            return true
        }
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> true
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> playChannel(channelIndex + 1)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> playChannel(channelIndex - 1)
                else -> if (event.repeatCount == 0) toggleOverlay()
            }
        }
        return true
    }

    // ---- Movies & episodes: controls, next/previous, episode list ----

    private val controlsHost = object : VodControls.Host {
        override val player: Player? get() = this@PlayerActivity.player
        override fun seekBy(deltaMs: Long) = seekVod(deltaMs)
        override fun togglePlay() {
            player?.let { it.playWhenReady = !it.playWhenReady }
        }
        override fun previousEpisode() {
            if (hasPreviousEpisode) playEpisodeAt(episodeIndex - 1)
        }
        override fun nextEpisode() {
            if (hasNextEpisode) playEpisodeAt(episodeIndex + 1)
        }
        override fun openEpisodes() = this@PlayerActivity.openEpisodes()
        override fun openTracks() = showTrackPicker()
        override fun openSettings() = showSettings()
        override fun onControlsHidden() {
            playerView.requestFocus()
        }
    }

    private fun seekVod(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration
        val max = if (duration != C.TIME_UNSET && duration > 0) duration - 1_000 else Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, max))
    }

    private fun setupVodChrome() {
        episodeAdapter = TextAdapter(
            R.layout.item_episode,
            bind = { vh, ep -> bindEpisodeRow(vh, ep) },
            onClick = { index, _ ->
                closeEpisodes()
                if (index != episodeIndex) playEpisodeAt(index)
            },
            onLongClick = { index, ep -> toggleWatched(index, ep) },
        )
        episodesList.layoutManager = LinearLayoutManager(this)
        episodesList.adapter = episodeAdapter

        updateEpisodeButtons()
        loadEpisodeQueue()
    }

    /** Episodes started from the series page bring the list along; Continue watching loads it. */
    private fun loadEpisodeQueue() {
        val entry = watchEntry ?: return
        val sid = entry.seriesId ?: return
        val queue = Session.seriesQueue
        if (queue != null && queue.seriesId == sid) {
            applyQueue(queue, entry.id)
            return
        }
        lifecycleScope.launch {
            try {
                val info = api.seriesInfo(sid)
                val q = SeriesQueue(sid, entry.title, entry.image ?: info.cover, info.playOrder())
                Session.seriesQueue = q
                applyQueue(q, entry.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No list: the episode still plays, just without next/previous.
            }
        }
    }

    private fun applyQueue(queue: SeriesQueue, currentEpisodeId: String) {
        val index = queue.episodes.indexOfFirst { it.id == currentEpisodeId }
        if (index < 0) return
        episodes = queue.episodes
        episodeIndex = index
        seriesId = queue.seriesId
        seriesName = queue.name
        seriesCover = queue.cover
        episodesSeries.text = queue.name
        episodeAdapter.submit(episodes)
        updateEpisodeButtons()
    }

    /** Titles and episode buttons on the controls (series name + episode, or the movie name). */
    private fun updateEpisodeButtons() {
        val entry = watchEntry
        val isEpisode = entry?.type == ContentType.SERIES
        val titleText = when {
            isCatchup -> vodTitle
            isEpisode -> seriesName.ifEmpty { entry?.title ?: vodTitle }
            else -> vodTitle
        }
        val episodeText = if (isEpisode) episodes.getOrNull(episodeIndex)?.label() ?: entry?.subtitle else null
        val upNext = if (hasNextEpisode) episodes[episodeIndex + 1].label() else null
        controls.bind(titleText, episodeText, episodes.isNotEmpty(), hasPreviousEpisode, hasNextEpisode, upNext)
    }

    private fun bindEpisodeRow(vh: TextAdapter.VH, ep: Episode) {
        vh.title.text = ep.label()
        val playing = episodes.getOrNull(episodeIndex)?.id == ep.id
        val pos = prefs.position("ep_${ep.id}")
        vh.subtitle?.text = listOfNotNull(
            when {
                playing -> "▶ Now playing"
                pos > 0 -> "Resume ${formatDuration(pos)}"
                prefs.isWatched("ep_${ep.id}") -> "✓ Watched"
                else -> null
            },
            ep.duration,
        ).joinToString("   ·   ")
    }

    /** Long-press in the Episodes panel: mark as watched / not watched. */
    private fun toggleWatched(index: Int, ep: Episode) {
        val key = "ep_${ep.id}"
        val watched = !prefs.isWatched(key)
        prefs.setWatched(key, watched)
        if (watched && index != episodeIndex) prefs.removeProgress(key)
        Toast.makeText(this, if (watched) "Marked as watched" else "Marked as not watched", Toast.LENGTH_SHORT).show()
        episodeAdapter.notifyItemChanged(index, "watched")
    }

    private fun playEpisodeAt(index: Int) {
        val ep = episodes.getOrNull(index) ?: return
        player?.let { saveVodPosition(it) }
        vodRetries = 0
        playerView.removeCallbacks(retryVod)
        episodeIndex = index
        val label = ep.label()
        resumeKey = "ep_${ep.id}"
        vodUrl = api.episodeUrl(ep.id, ep.ext)
        vodTitle = "$seriesName  ·  $label"
        titleShown = false
        watchEntry = WatchEntry(resumeKey, ContentType.SERIES, ep.id, ep.ext, seriesName, label, seriesCover, seriesId = seriesId)
        startPosition = prefs.position(resumeKey)
        askResume = true
        resumeAsked = false
        probed = false
        errorView.visibility = View.GONE
        updateEpisodeButtons()
        resetEpisodePrompts()
        player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().clearOverrides().build() }
        startVod()
    }

    private fun openEpisodes() {
        if (episodes.isEmpty()) return
        controls.hide()
        episodesPanel.visibility = View.VISIBLE
        episodeAdapter.activeIndex = episodeIndex
        episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.itemCount, "progress")
        episodesList.scrollToPosition(episodeIndex)
        episodesList.post { episodesList.findViewHolderForAdapterPosition(episodeIndex)?.itemView?.requestFocus() }
    }

    private fun closeEpisodes() {
        episodesPanel.visibility = View.GONE
        playerView.requestFocus()
    }

    /** Lets the standard controller (and remote media keys) step through the episode list. */
    private inner class EpisodeAwarePlayer(player: Player) : ForwardingPlayer(player) {
        private val episodeCommands = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                if (episodes.isNotEmpty()) hasNextEpisode else super.isCommandAvailable(command)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                if (episodes.isNotEmpty()) hasPreviousEpisode else super.isCommandAvailable(command)
            else -> super.isCommandAvailable(command)
        }

        override fun getAvailableCommands(): Player.Commands {
            val builder = super.getAvailableCommands().buildUpon()
            for (c in episodeCommands) if (isCommandAvailable(c)) builder.add(c) else builder.remove(c)
            return builder.build()
        }

        override fun hasNextMediaItem(): Boolean = if (episodes.isNotEmpty()) hasNextEpisode else super.hasNextMediaItem()

        override fun hasPreviousMediaItem(): Boolean = if (episodes.isNotEmpty()) hasPreviousEpisode else super.hasPreviousMediaItem()

        override fun seekToNext() {
            if (episodes.isEmpty()) super.seekToNext() else if (hasNextEpisode) playEpisodeAt(episodeIndex + 1)
        }

        override fun seekToNextMediaItem() = seekToNext()

        override fun seekToPrevious() {
            if (episodes.isEmpty()) super.seekToPrevious() else if (hasPreviousEpisode) playEpisodeAt(episodeIndex - 1)
        }

        override fun seekToPreviousMediaItem() = seekToPrevious()
    }

    // ---- Audio & subtitles ----

    private fun applyTrackPreferences(p: ExoPlayer) {
        val builder = p.trackSelectionParameters.buildUpon()
        prefs.audioLanguage?.let { builder.setPreferredAudioLanguage(it) }
        when (val sub = prefs.subtitleLanguage) {
            null -> {}
            SUBTITLES_OFF -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else -> builder.setPreferredTextLanguage(sub)
        }
        p.trackSelectionParameters = builder.build()
    }

    private fun showTrackPicker() {
        val p = player ?: return
        val names = DefaultTrackNameProvider(resources)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val groups = p.currentTracks.groups

        for (group in groups.filter { it.type == C.TRACK_TYPE_AUDIO }) {
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                labels += (if (group.isTrackSelected(i)) "●  " else "○  ") + "Audio:  " + names.getTrackName(format)
                actions += {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                    format.language?.let { prefs.audioLanguage = it }
                }
            }
        }

        val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val subtitlesOff = C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes || textGroups.none { it.isSelected }
        labels += (if (subtitlesOff) "●  " else "○  ") + "Subtitles:  Off"
        actions += {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            prefs.subtitleLanguage = SUBTITLES_OFF
        }
        var untagged = 0
        for (group in textGroups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val selected = !subtitlesOff && group.isTrackSelected(i)
                // Untagged tracks would read "Unknown": number them instead.
                var name = names.getTrackName(format)
                if (name == getString(androidx.media3.ui.R.string.exo_track_unknown)) name = "Track ${++untagged}"
                labels += (if (selected) "●  " else "○  ") + "Subtitles:  " + name
                actions += {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                    prefs.subtitleLanguage = format.language
                }
            }
        }

        if (labels.size <= 1) {
            Toast.makeText(this, "This video has no other audio or subtitles", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Audio & subtitles")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    // ---- Quick seek: arrows / mouse side buttons, with a small pill instead of the full controls ----

    private val hidePlayFlash = Runnable {
        playFlash.animate().alpha(0f).setDuration(200).withEndAction { playFlash.visibility = View.GONE }.start()
    }

    /** Brief ▶ / ❚❚ in the middle of the screen; fades out either way so nothing stays on the picture. */
    private fun showPlayPauseFlash(playing: Boolean) {
        playFlash.text = if (playing) "▶" else "❚❚"
        playFlash.animate().cancel()
        playFlash.alpha = 1f
        playFlash.visibility = View.VISIBLE
        playFlash.removeCallbacks(hidePlayFlash)
        playFlash.postDelayed(hidePlayFlash, 800)
    }

    private var seekTotal = 0L
    private val hideSeekRipple = Runnable {
        for (v in listOf(seekForward, seekBack)) {
            if (v.visibility == View.VISIBLE) v.animate().alpha(0f).setDuration(200).withEndAction { v.visibility = View.GONE }.start()
        }
        seekTotal = 0
    }

    private fun quickSeek(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration
        val max = if (duration != C.TIME_UNSET && duration > 0) duration - 1_000 else Long.MAX_VALUE
        val target = (p.currentPosition + deltaMs).coerceIn(0, max)
        p.seekTo(target)
        (if (deltaMs > 0) seekForwardTime else seekBackTime).text =
            if (duration != C.TIME_UNSET && duration > 0) "${formatDuration(target)} / ${formatDuration(duration)}" else formatDuration(target)
        val forward = deltaMs > 0
        val shown = if (forward) seekForward else seekBack
        val other = if (forward) seekBack else seekForward
        // Presses in the same direction add up ("30 seconds"); changing direction starts again.
        seekTotal = if (shown.visibility == View.VISIBLE && (seekTotal > 0) == forward) seekTotal + deltaMs else deltaMs
        (if (forward) seekForwardText else seekBackText).text = "${kotlin.math.abs(seekTotal) / 1000} seconds"
        other.animate().cancel()
        other.visibility = View.GONE
        shown.animate().cancel()
        if (shown.visibility != View.VISIBLE) {
            shown.alpha = 0f
            shown.visibility = View.VISIBLE
        }
        shown.animate().alpha(1f).setDuration(120).start()
        seekForward.removeCallbacks(hideSeekRipple)
        seekForward.postDelayed(hideSeekRipple, 1_000)
    }

    /**
     * Mouse: moving it shows the controls (and keeps them up); side buttons back = −5 s,
     * forward = +10 s (handled before Android turns "back" into Back).
     */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!isLive && ::controls.isInitialized && ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE &&
            episodesPanel.visibility != View.VISIBLE && actionCard.visibility != View.VISIBLE
        ) {
            // Only a real move counts: a click also sends a hover at the same spot, which
            // would bring back the controls the click just hid.
            val moved = Math.abs(ev.x - hoverX) + Math.abs(ev.y - hoverY) > 12f
            hoverX = ev.x
            hoverY = ev.y
            if (moved && SystemClock.uptimeMillis() - lastMouseClick > 600) {
                if (controlsShown()) controls.poke() else controls.show()
            }
        }
        if (!isLive && ::playerView.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            (ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || ev.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) &&
            episodesPanel.visibility != View.VISIBLE
        ) {
            val delta = when (ev.actionButton) {
                MotionEvent.BUTTON_BACK -> -backMs()
                MotionEvent.BUTTON_FORWARD -> forwardMs()
                else -> 0L
            }
            if (delta != 0L) {
                if (ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) quickSeek(delta)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }


    // ---- Next episode prompt ----

    private fun resetEpisodePrompts() {
        nextDismissed = false
        hideCard()
    }

    private fun updateEpisodePrompts() {
        val p = player
        if (p == null || watchEntry?.type != ContentType.SERIES || errorView.visibility == View.VISIBLE ||
            episodesPanel.visibility == View.VISIBLE
        ) {
            hideCard()
            return
        }
        val pos = p.currentPosition
        val duration = p.duration
        val remaining = if (duration != C.TIME_UNSET && duration > 0) duration - pos else Long.MAX_VALUE
        if (prefs.autoNext && hasNextEpisode && !nextDismissed && !sleepAtEnd && remaining <= prefs.nextCountdownSeconds * 1000L && p.playbackState == Player.STATE_READY) {
            val next = episodes[episodeIndex + 1]
            showCard(
                CardAction.NEXT_EPISODE,
                "Next: ${next.label()}  in ${(remaining / 1000).coerceAtLeast(0)}s   ·   Back to hide",
                "OK  ▶  Play now",
            )
        } else {
            hideCard()
        }
    }

    private fun showCard(action: CardAction, text: String?, button: String) {
        cardAction = action
        actionText.text = text ?: ""
        actionText.visibility = if (text == null) View.GONE else View.VISIBLE
        actionButton.text = button
        actionButton.isSelected = true
        actionCard.visibility = View.VISIBLE
    }

    private fun hideCard() {
        if (cardAction == CardAction.NONE && actionCard.visibility == View.GONE) return
        cardAction = CardAction.NONE
        actionCard.visibility = View.GONE
    }

    private fun performCardAction() {
        if (cardAction == CardAction.NEXT_EPISODE && hasNextEpisode) playEpisodeAt(episodeIndex + 1)
    }

    /** Back: hide the prompt for the rest of this episode; the next one still starts at the end. */
    private fun dismissCard() {
        nextDismissed = true
        hideCard()
    }

    // ---- Live: channel list ----

    private fun setupChannelPanel() {
        channelAdapter = ItemAdapter(
            grid = false,
            onClick = { index, _ ->
                closeChannels()
                if (index != channelIndex) playChannel(index)
            },
            onLongClick = null,
        )
        channelsList.layoutManager = LinearLayoutManager(this)
        channelsList.adapter = channelAdapter
    }

    private fun openChannels() {
        val list = Session.zapList
        if (list.isEmpty()) return
        overlay.removeCallbacks(hideOverlay)
        overlay.visibility = View.GONE
        channelsTitle.text = Session.zapTitle ?: "Channels"
        channelAdapter.favorites = prefs.favoriteIds(ContentType.LIVE)
        // Mark the channel that is playing.
        channelAdapter.submit(list.mapIndexed { i, c -> if (i == channelIndex) c.copy(note = "▶") else c })
        channelsPanel.visibility = View.VISIBLE
        (channelsList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(channelIndex, 200)
        channelsList.post { channelsList.findViewHolderForAdapterPosition(channelIndex)?.itemView?.requestFocus() }
    }

    private fun closeChannels() {
        channelsPanel.visibility = View.GONE
        playerView.requestFocus()
    }

    // ---- Settings menu (movies/episodes) and options menu (live) ----

    private fun showMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun showSettings() {
        showMenu(
            "Settings",
            listOf(
                "Audio and subtitles" to { showTrackPicker() },
                "Subtitle size:  ${SUBTITLE_SIZES[prefs.subtitleSize.coerceIn(0, 3)].first}" to { pickSubtitleSize() },
                "Subtitle background:  ${if (prefs.subtitleBackground) "On" else "Off"}" to {
                    prefs.subtitleBackground = !prefs.subtitleBackground
                    applySubtitleStyle()
                },
                "Speed:  ${speedLabel(speed)}" to { pickSpeed() },
                "Picture size:  ${pictureLabel()}" to { pickPictureSize() },
                "Sleep timer:  ${sleepLabel()}" to { pickSleepTimer() },
                "Stream info" to { showStreamInfo() },
            ),
        )
    }

    private fun showLiveOptions() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "TV guide" to { openGuide() }
        lastChannel?.let { items += "Last channel:  ${it.second.name}" to { switchToLastChannel() } }
        items += "Jump to live" to { playChannel(channelIndex) }
        items += "Picture size:  ${pictureLabel()}" to { pickPictureSize() }
        items += "Sleep timer:  ${sleepLabel()}" to { pickSleepTimer() }
        items += "Stream info" to { showStreamInfo() }
        showMenu(currentChannel?.name ?: "Options", items)
    }

    private fun applySubtitleStyle() {
        val sv = playerView.subtitleView ?: return
        val bg = prefs.subtitleBackground
        sv.setApplyEmbeddedFontSizes(false)
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SUBTITLE_SIZES[prefs.subtitleSize.coerceIn(0, 3)].second)
        sv.setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                if (bg) 0xB3000000.toInt() else Color.TRANSPARENT,
                Color.TRANSPARENT,
                if (bg) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null,
            ),
        )
    }

    private fun pickSubtitleSize() {
        AlertDialog.Builder(this)
            .setTitle("Subtitle size")
            .setSingleChoiceItems(SUBTITLE_SIZES.map { it.first }.toTypedArray(), prefs.subtitleSize.coerceIn(0, 3)) { d, which ->
                prefs.subtitleSize = which
                applySubtitleStyle()
                d.dismiss()
            }
            .show()
    }

    private fun speedLabel(s: Float): String {
        val text = if (s % 1f == 0f) s.toInt().toString() else s.toString()
        return if (s == 1f) "${text}× (normal)" else "$text×"
    }

    private fun pickSpeed() {
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(SPEEDS.map { speedLabel(it) }.toTypedArray(), SPEEDS.indexOf(speed).coerceAtLeast(0)) { d, which ->
                speed = SPEEDS[which]
                player?.setPlaybackSpeed(speed)
                d.dismiss()
            }
            .show()
    }

    private fun pictureLabel() = PICTURE_SIZES[prefs.pictureSize.coerceIn(0, PICTURE_SIZES.size - 1)].first

    private fun pickPictureSize() {
        AlertDialog.Builder(this)
            .setTitle("Picture size")
            .setSingleChoiceItems(PICTURE_SIZES.map { it.first }.toTypedArray(), prefs.pictureSize.coerceIn(0, PICTURE_SIZES.size - 1)) { d, which ->
                prefs.pictureSize = which
                playerView.resizeMode = PICTURE_SIZES[which].second
                d.dismiss()
            }
            .show()
    }

    private fun sleepLabel(): String = when {
        sleepAtEnd -> if (episodes.isNotEmpty()) "End of this episode" else "End of this video"
        sleepAt > 0 -> "${((sleepAt - SystemClock.uptimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min left"
        else -> "Off"
    }

    private fun pickSleepTimer() {
        val options = ArrayList<Pair<String, () -> Unit>>()
        options += "Off" to { setSleepTimer(0) }
        for (m in listOf(30, 60, 90, 120)) options += "$m minutes" to { setSleepTimer(m) }
        if (!isLive) {
            options += (if (episodes.isNotEmpty()) "End of this episode" else "End of this video") to {
                setSleepTimer(0)
                sleepAtEnd = true
                Toast.makeText(this, "Playback will stop at the end", Toast.LENGTH_SHORT).show()
            }
        }
        showMenu("Sleep timer", options)
    }

    private fun setSleepTimer(minutes: Int) {
        playerView.removeCallbacks(sleepNow)
        sleepAtEnd = false
        sleepAt = 0
        if (minutes > 0) {
            sleepAt = SystemClock.uptimeMillis() + minutes * 60_000L
            playerView.postDelayed(sleepNow, minutes * 60_000L)
            Toast.makeText(this, "Playback will stop in $minutes minutes", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
        }
    }

    /** Resolution, codecs and bitrate: handy when a channel stutters. Never shows the URL (it has the login in it). */
    private fun showStreamInfo() {
        val p = player ?: return
        val v = p.videoFormat
        val a = p.audioFormat
        val size = p.videoSize
        val lines = ArrayList<String>()
        if (size.width > 0) {
            val fps = v?.frameRate?.takeIf { it > 0 }?.let { "  ·  ${Math.round(it)} fps" } ?: ""
            lines += "Resolution:  ${size.width} × ${size.height}$fps"
        }
        v?.let { f ->
            val codec = f.codecs ?: f.sampleMimeType?.substringAfter('/') ?: "unknown"
            val rate = listOf(f.bitrate, f.averageBitrate, f.peakBitrate).firstOrNull { it > 0 }
            lines += "Video:  $codec" + (rate?.let { "  ·  ${it / 1000} kbps" } ?: "")
        }
        a?.let { f ->
            val codec = f.codecs ?: f.sampleMimeType?.substringAfter('/') ?: "unknown"
            val parts = listOfNotNull(
                codec,
                f.channelCount.takeIf { it > 0 }?.let { "$it channels" },
                f.sampleRate.takeIf { it > 0 }?.let { "${it / 1000} kHz" },
                f.language,
            )
            lines += "Audio:  " + parts.joinToString("  ·  ")
        }
        val format = when {
            currentUrl.contains(".m3u8") || vodMimeType == MimeTypes.APPLICATION_M3U8 -> "HLS"
            currentUrl.endsWith(".ts") -> "MPEG-TS"
            else -> currentUrl.substringAfterLast('.', "").uppercase().ifEmpty { "unknown" }
        }
        lines += "Stream:  $format"
        lines += "Buffered:  ${p.totalBufferedDuration / 1000} s ahead"
        AlertDialog.Builder(this)
            .setTitle("Stream info")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Close", null)
            .show()
    }

    // ---- Live: pause, channel numbers, last channel, guide ----

    private fun toggleLivePause(keyCode: Int) {
        val p = player ?: return
        val play = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> true
            KeyEvent.KEYCODE_MEDIA_PAUSE -> false
            else -> !p.playWhenReady
        }
        p.playWhenReady = play
        if (play) liveResumedAt = SystemClock.uptimeMillis()
        showPlayPauseFlash(play)
    }

    private fun typeChannelDigit(digit: Int) {
        numberBuffer = (numberBuffer + digit).takeLast(4)
        numberEntry.text = numberBuffer
        numberEntry.visibility = View.VISIBLE
        numberEntry.removeCallbacks(commitNumber)
        numberEntry.postDelayed(commitNumber, 1_500)
    }

    private fun commitChannelNumber() {
        val typed = numberBuffer
        numberBuffer = ""
        numberEntry.visibility = View.GONE
        if (typed.isEmpty()) return
        val target = typed.trimStart('0').ifEmpty { "0" }
        val matches = { list: List<StreamItem> -> list.indexOfFirst { it.num?.trimStart('0') == target } }
        val here = matches(Session.zapList)
        if (here >= 0) {
            playChannel(here)
            return
        }
        // Not in this category: look through all channels.
        lifecycleScope.launch {
            val all = Session.streamCache["LIVE:__all__"] ?: try {
                api.streams(ContentType.LIVE, null).also { Session.streamCache["LIVE:__all__"] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val index = all?.let(matches) ?: -1
            if (all != null && index >= 0) {
                Session.zapList = all
                Session.zapTitle = "All channels"
                playChannel(index)
            } else {
                Toast.makeText(this@PlayerActivity, "There's no channel $typed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchToLastChannel() {
        val (list, channel, title) = lastChannel ?: run {
            Toast.makeText(this, "No previous channel yet", Toast.LENGTH_SHORT).show()
            return
        }
        val index = list.indexOfFirst { it.id == channel.id }
        if (index < 0) return
        Session.zapList = list
        Session.zapTitle = title
        playChannel(index)
    }

    private fun openGuide() {
        startActivity(GuideActivity.intent(this, channelIndex, fromPlayer = true))
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.removeCallbacks(hideOverlay)
        if (::controls.isInitialized) controls.release()
        if (::playerView.isInitialized) playerView.removeCallbacks(sleepNow)
        if (::playerView.isInitialized) playerView.removeCallbacks(retryVod)
        if (::numberEntry.isInitialized) numberEntry.removeCallbacks(commitNumber)
        super.onDestroy()
    }
}
