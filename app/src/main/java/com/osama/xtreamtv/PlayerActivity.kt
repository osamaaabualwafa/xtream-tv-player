package com.osama.xtreamtv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.os.Build
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
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
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

        fun vodIntent(context: Context, url: String, title: String, key: String, startMs: Long, entry: WatchEntry): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_LIVE, false)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_START, startMs)
                .putExtra(EXTRA_WATCH, entry.toJson().toString())

        private const val EXTRA_WATCH = "watch"
        const val SUBTITLES_OFF = "off"
        private const val NEXT_PROMPT_MS = 30_000L
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
    private lateinit var topBar: View
    private lateinit var playerTitle: TextView
    private lateinit var btnEpisodes: View
    private lateinit var btnNext: View
    private lateinit var episodesPanel: View
    private lateinit var episodesSeries: TextView
    private lateinit var episodesList: RecyclerView
    private lateinit var episodeAdapter: TextAdapter<Episode>
    private lateinit var btnTracks: View
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

    /** The controller's own "fully visible" flag is unreliable on some TVs; the top bar mirrors it. */
    private fun controlsShown() = playerView.isControllerFullyVisible || topBar.visibility == View.VISIBLE

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
        topBar = findViewById(R.id.top_bar)
        playerTitle = findViewById(R.id.player_title)
        btnEpisodes = findViewById(R.id.btn_episodes)
        btnNext = findViewById(R.id.btn_next)
        episodesPanel = findViewById(R.id.episodes_panel)
        episodesSeries = findViewById(R.id.episodes_series)
        episodesList = findViewById(R.id.episodes_list)
        btnTracks = findViewById(R.id.btn_tracks)
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
            if (prefs.vodIsHls) vodMimeType = MimeTypes.APPLICATION_M3U8
            playerView.useController = true
            playerView.controllerShowTimeoutMs = 5_000
            // Pausing (e.g. the remote play/pause button) shows a small icon, not the full controls.
            playerView.controllerAutoShow = false
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
                    !isLive && controlsShown() -> playerView.hideController()
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
        p.setMediaItem(mediaItem(vodUrl, vodMimeType))
        if (startPosition > 0) p.seekTo(startPosition)
        p.prepare()
        p.playWhenReady = true
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
        val pos = p.currentPosition
        val duration = p.duration
        // "Finished" = last minute, or last 5% for short videos.
        if (duration != C.TIME_UNSET && duration > 0 && pos > duration - minOf(60_000L, duration / 20)) {
            // Watched to the end: start from the beginning next time and drop it from Continue watching.
            prefs.removeProgress(resumeKey)
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
            if (state == Player.STATE_READY) errorView.visibility = View.GONE
            if (state == Player.STATE_ENDED && !isLive) {
                prefs.removeProgress(resumeKey)
                // Series: carry on with the next episode, like a TV binge.
                if (hasNextEpisode) playEpisodeAt(episodeIndex + 1) else finish()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (!isLive) updateTracksButton(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
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
        if (!isLive) playerView.hideController()
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

    private fun hlsAllowed(): Boolean {
        val formats = prefs.formats.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return formats.isEmpty() || formats.contains("m3u8")
    }

    private fun playChannel(index: Int) {
        val list = Session.zapList
        if (list.isEmpty()) return finish()
        channelIndex = ((index % list.size) + list.size) % list.size
        val channel = list[channelIndex]
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
                overlayNext.text = next?.let { "Next   ${timeRange(it)}   ${it.title}" } ?: ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                overlayNow.text = "No programme information"
            }
        }
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
                if (event.action == KeyEvent.ACTION_DOWN) quickSeek(-5_000L)
                return true
            }
            if (!::playerView.isInitialized || event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            // The OK that answered a prompt must not also "click" play/pause when it is released.
            if (swallowOkRelease && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                if (event.action == KeyEvent.ACTION_UP) swallowOkRelease = false
                return true
            }
            // The episode list and the top bar are normal focusable views: plain D-pad navigation.
            if (episodesPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
            if (topBar.hasFocus()) {
                playerView.showController()
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val playPause = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                        if (playPause?.requestFocus() != true) playerView.requestFocus()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            // Up from the player controls jumps to the top bar (the controller ignores nextFocusUp).
            // The seek bar keeps its own Up handling.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && controlsShown() &&
                playerView.hasFocus() && currentFocus?.id != androidx.media3.ui.R.id.exo_progress &&
                firstTopButton() != null
            ) {
                if (event.action == KeyEvent.ACTION_DOWN) firstTopButton()?.requestFocus()
                return true
            }
            // Remote play/pause acts on the first press, with a small center icon instead of the controls.
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
                        showPlayPauseFlash(p.playWhenReady)
                    }
                }
                return true
            }
            // Right = +10 s, Left = −5 s without opening the controls (unless a player button has focus).
            val seekDelta = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (currentFocus?.id in controlIds) 0L else 10_000L
                KeyEvent.KEYCODE_DPAD_LEFT -> if (currentFocus?.id in controlIds) 0L else -5_000L
                // Remote ⏩/⏪ buttons (remotes send different codes for them).
                KeyEvent.KEYCODE_FORWARD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> 10_000L
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> -5_000L
                else -> 0L
            }
            if (seekDelta != 0L) {
                if (event.action == KeyEvent.ACTION_DOWN) quickSeek(seekDelta)
                return true
            }
            // OK answers the "Next episode" prompt while the controls are hidden.
            if (cardAction != CardAction.NONE && !controlsShown() &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    swallowOkRelease = true
                    performCardAction()
                }
                return true
            }
            if (episodes.isNotEmpty() && event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP -> {
                        if (hasNextEpisode) playEpisodeAt(episodeIndex + 1)
                        return true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        if (hasPreviousEpisode) playEpisodeAt(episodeIndex - 1)
                        return true
                    }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                        openEpisodes()
                        return true
                    }
                }
            }
            // Let PlayerView show its controller and handle D-pad/media keys first.
            if (playerView.dispatchKeyEvent(event)) return true
            return super.dispatchKeyEvent(event)
        }
        if (channelsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
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

    // ---- Movies & episodes: title bar, next/previous, episode list ----

    private val controlIds = listOf(
        androidx.media3.ui.R.id.exo_play_pause,
        androidx.media3.ui.R.id.exo_prev,
        androidx.media3.ui.R.id.exo_next,
        androidx.media3.ui.R.id.exo_rew,
        androidx.media3.ui.R.id.exo_ffwd,
    )

    private fun setupVodChrome() {
        playerTitle.text = vodTitle
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBar.visibility = if (episodesPanel.visibility == View.VISIBLE) View.GONE else visibility
        })
        // Keep the controls up while the user is in the top bar.
        val keepVisible = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) playerView.showController() }
        btnEpisodes.onFocusChangeListener = keepVisible
        btnNext.onFocusChangeListener = keepVisible
        btnEpisodes.setOnClickListener { openEpisodes() }
        btnTracks.onFocusChangeListener = keepVisible
        btnTracks.setOnClickListener { showTrackPicker() }
        btnNext.setOnClickListener { if (hasNextEpisode) playEpisodeAt(episodeIndex + 1) }
        // Up from the player controls reaches the top bar; Down comes back.
        btnEpisodes.nextFocusDownId = androidx.media3.ui.R.id.exo_play_pause
        btnNext.nextFocusDownId = androidx.media3.ui.R.id.exo_play_pause
        controlIds.mapNotNull { playerView.findViewById<View>(it) }.forEach { it.nextFocusUpId = R.id.btn_episodes }

        episodeAdapter = TextAdapter(
            R.layout.item_episode,
            bind = { vh, ep -> bindEpisodeRow(vh, ep) },
            onClick = { index, _ ->
                closeEpisodes()
                if (index != episodeIndex) playEpisodeAt(index)
            },
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

    private fun updateEpisodeButtons() {
        val hasList = episodes.isNotEmpty()
        btnEpisodes.visibility = if (hasList) View.VISIBLE else View.GONE
        btnNext.visibility = if (hasNextEpisode) View.VISIBLE else View.GONE
        // The controller's previous/next buttons mean previous/next episode; movies don't show them.
        playerView.setShowPreviousButton(hasList)
        playerView.setShowNextButton(hasList)
    }

    private fun bindEpisodeRow(vh: TextAdapter.VH, ep: Episode) {
        vh.title.text = ep.label()
        val playing = episodes.getOrNull(episodeIndex)?.id == ep.id
        val pos = prefs.position("ep_${ep.id}")
        vh.subtitle?.text = listOfNotNull(
            when {
                playing -> "▶ Now playing"
                pos > 0 -> "Resume ${formatDuration(pos)}"
                else -> null
            },
            ep.duration,
        ).joinToString("   ·   ")
    }

    private fun playEpisodeAt(index: Int) {
        val ep = episodes.getOrNull(index) ?: return
        player?.let { saveVodPosition(it) }
        episodeIndex = index
        val label = ep.label()
        resumeKey = "ep_${ep.id}"
        vodUrl = api.episodeUrl(ep.id, ep.ext)
        vodTitle = "$seriesName  ·  $label"
        playerTitle.text = vodTitle
        watchEntry = WatchEntry(resumeKey, ContentType.SERIES, ep.id, ep.ext, seriesName, label, seriesCover, seriesId = seriesId)
        startPosition = prefs.position(resumeKey)
        probed = false
        errorView.visibility = View.GONE
        updateEpisodeButtons()
        resetEpisodePrompts()
        player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().clearOverrides().build() }
        startVod()
        playerView.showController()
    }

    private fun openEpisodes() {
        if (episodes.isEmpty()) return
        playerView.hideController()
        playerView.useController = false
        topBar.visibility = View.GONE
        episodesPanel.visibility = View.VISIBLE
        episodeAdapter.activeIndex = episodeIndex
        episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.itemCount, "progress")
        episodesList.scrollToPosition(episodeIndex)
        episodesList.post { episodesList.findViewHolderForAdapterPosition(episodeIndex)?.itemView?.requestFocus() }
    }

    private fun closeEpisodes() {
        episodesPanel.visibility = View.GONE
        playerView.useController = true
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

    /** Shown only when there is something to choose: several audio tracks or any subtitles. */
    private fun updateTracksButton(tracks: Tracks) {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { g -> (0 until g.length).count { g.isTrackSupported(it) } }
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.sumOf { g -> (0 until g.length).count { g.isTrackSupported(it) } }
        btnTracks.visibility = if (audio > 1 || text > 0) View.VISIBLE else View.GONE
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

    /** Brief ▶ / ❚❚ in the middle of the screen; stays up while paused so the state is obvious. */
    private fun showPlayPauseFlash(playing: Boolean) {
        playFlash.text = if (playing) "▶" else "❚❚"
        playFlash.animate().cancel()
        playFlash.alpha = 1f
        playFlash.visibility = View.VISIBLE
        playFlash.removeCallbacks(hidePlayFlash)
        if (playing) playFlash.postDelayed(hidePlayFlash, 700)
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

    /** Mouse side buttons: back = −5 s, forward = +10 s (handled before Android turns "back" into Back). */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!isLive && ::playerView.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            (ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || ev.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) &&
            episodesPanel.visibility != View.VISIBLE
        ) {
            val delta = when (ev.actionButton) {
                MotionEvent.BUTTON_BACK -> -5_000L
                MotionEvent.BUTTON_FORWARD -> 10_000L
                else -> 0L
            }
            if (delta != 0L) {
                if (ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) quickSeek(delta)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun firstTopButton(): View? =
        listOf(btnTracks, btnEpisodes, btnNext).firstOrNull { it.visibility == View.VISIBLE }

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
        if (hasNextEpisode && !nextDismissed && remaining <= NEXT_PROMPT_MS && p.playbackState == Player.STATE_READY) {
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

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.removeCallbacks(hideOverlay)
        super.onDestroy()
    }
}
