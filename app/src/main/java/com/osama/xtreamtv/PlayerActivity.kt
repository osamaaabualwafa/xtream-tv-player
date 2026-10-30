package com.osama.xtreamtv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
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

        isLive = intent.getBooleanExtra(EXTRA_LIVE, false)
        if (isLive) {
            if (Session.zapList.isEmpty()) return finish()
            channelIndex = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
                .coerceIn(0, Session.zapList.size - 1)
            playerView.useController = false
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
            playerView.requestFocus()
            setupVodChrome()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    episodesPanel.visibility == View.VISIBLE -> closeEpisodes()
                    isLive && overlay.visibility == View.VISIBLE -> overlay.visibility = View.GONE
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
        if (!isLive) playerView.postDelayed(autoSave, 30_000)
    }

    override fun onPause() {
        super.onPause()
        // Save here, not only in onStop: the previous screen resumes before this one stops.
        val p = player
        if (p != null && !isLive) saveVodPosition(p)
    }

    override fun onStop() {
        super.onStop()
        if (::playerView.isInitialized) playerView.removeCallbacks(autoSave)
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
            if (!::playerView.isInitialized || event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
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
                btnEpisodes.visibility == View.VISIBLE
            ) {
                if (event.action == KeyEvent.ACTION_DOWN) btnEpisodes.requestFocus()
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

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.removeCallbacks(hideOverlay)
        super.onDestroy()
    }
}
