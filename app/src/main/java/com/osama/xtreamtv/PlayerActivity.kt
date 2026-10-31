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
import android.widget.Button
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Look.wrap(newBase))

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
        /** Only "come back to full screen": never a new player of its own. */
        private const val EXTRA_EXPAND = "expand"
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
        /** The player on screen (full or mini); a new video closes the old one first. */
        private var current: java.lang.ref.WeakReference<PlayerActivity>? = null

        /** What the mini player is playing, or null when there is no mini player. */
        fun miniTitle(): String? {
            val p = current?.get() ?: return null
            if (!p.wasMini || p.isFinishing) return null
            return if (p.isLive) p.currentChannel?.name ?: "Live TV" else p.vodTitle
        }

        /**
         * Mini player back to full screen: the corner player starts itself again (brought to the
         * front of its own task), which the TV answers by expanding it.
         */
        @Suppress("UNUSED_PARAMETER")
        fun expandMini(context: Context) {
            val p = current?.get()?.takeIf { it.wasMini } ?: return
            p.startActivity(
                Intent(p, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra(EXTRA_EXPAND, true),
            )
        }

        fun closeMini() {
            current?.get()?.takeIf { it.wasMini }?.finish()
        }
        private const val VOD_RETRIES = 5
        private const val LIVE_RETRIES = 4
        private const val STILL_WATCHING_AFTER = 3
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
    private lateinit var seasonsList: RecyclerView
    private lateinit var seasonAdapter: TextAdapter<Int>
    /** Season shown in the episodes panel. */
    private var shownSeason = -1
    private lateinit var menuPanel: View
    private lateinit var menuItems: android.widget.LinearLayout
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
    /** Live: tries for the current channel after the server refused it (connection still counted). */
    private var liveRetries = 0
    private val retryLive = Runnable { playChannel(channelIndex, banner = false, retry = true) }
    /** Episodes started on their own (no key pressed since): ask "Still watching?" at [STILL_WATCHING_AFTER]. */
    private var autoEpisodes = 0
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
        // A "full screen" request that didn't reach the corner player: leave that one alone.
        if (intent.getBooleanExtra(EXTRA_EXPAND, false)) return finish()
        api = Session.api(this) ?: return goToLogin()
        prefs = Prefs(this)
        // A mini player still running elsewhere: close it (one stream at a time).
        current?.get()?.takeIf { it !== this }?.finish()
        current = java.lang.ref.WeakReference(this)
        originTaskId = taskId
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
        seasonsList = findViewById(R.id.episodes_seasons)
        menuPanel = findViewById(R.id.player_menu)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (now == null) return@addOnGlobalFocusChangeListener
            fun inside(panel: View): Boolean {
                var v: View? = now
                while (v != null) {
                    if (v === panel) return true
                    v = v.parent as? View
                }
                return false
            }
            if (menuPanel.visibility == View.VISIBLE && !inside(menuPanel)) closeMenu(refocus = false)
            if (episodesPanel.visibility == View.VISIBLE && !inside(episodesPanel)) closeEpisodes(refocus = false)
        }
        menuItems = findViewById(R.id.player_menu_items)
        placeMenuPanel()
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
            // Settings > Player: movies and episodes in another player app.
            if (!isCatchup && prefs.vodPlayer != 0) {
                if (ExternalPlayer.open(this, prefs.vodPlayer, vodUrl, vodTitle, startPosition)) {
                    watchEntry?.let { prefs.saveProgress(it.copy(position = startPosition, updated = System.currentTimeMillis())) }
                    finish()
                    return
                }
                Toast.makeText(this, "${ExternalPlayer.PLAYERS.getOrNull(prefs.vodPlayer)?.first ?: "That player"} isn't installed. Playing here.", Toast.LENGTH_LONG).show()
            }
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
                    when {
                        menuPanel.visibility == View.VISIBLE -> closeMenu()
                        episodesPanel.visibility == View.VISIBLE -> closeEpisodes()
                        controlsShown() -> controls.hide()
                        else -> controls.show()
                    }
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
                    menuPanel.visibility == View.VISIBLE -> closeMenu()
                    episodesPanel.visibility == View.VISIBLE -> closeEpisodes()
                    channelsPanel.visibility == View.VISIBLE -> closeChannels()
                    isLive && overlay.visibility == View.VISIBLE -> overlay.visibility = View.GONE
                    !isLive && cardAction != CardAction.NONE && !controlsShown() -> dismissCard()
                    !isLive && controlsShown() -> controls.hide()
                    // Back from the corner to full screen: close and show the app underneath.
                    restoredFromMini -> closeToApp()
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
        // Leaving with the resume card up: it would stay on screen over the playing video.
        resumeDialog?.takeIf { it.isShowing }?.dismiss()
        resumeDialog = null
        // The mini player was closed (or the TV went to its home screen): stop for good.
        if (wasMini) {
            wasMini = false
            finish()
        }
        if (::playerView.isInitialized) {
            playerView.removeCallbacks(autoSave)
            playerView.removeCallbacks(ticker)
        }
        releasePlayer()
    }

    private fun initPlayer() {
        if (player != null) return
        val http = PlaybackTuning.http(connectTimeoutMs = 4_000, readTimeoutMs = 30_000)
        // Live: retry a failing URL only once so the .ts fallback / error shows quickly.
        // A stream server that does not answer is retried at once (see QuickRetry).
        val sources = DefaultMediaSourceFactory(http)
            .setLoadErrorHandlingPolicy(PlaybackTuning.QuickRetry(if (isLive) 1 else 3))
        // Settings > Player: decoder and buffer size.
        val p = ExoPlayer.Builder(this, PlaybackTuning.renderers(this, prefs.decoderMode == 1))
            .setMediaSourceFactory(sources)
            .setLoadControl(PlaybackTuning.loadControl(prefs.bufferSize))
            .build()
        p.addListener(playerListener)
        applyTrackPreferences(p)
        player = p
        mediaSession = try {
            // A mini player may still be closing: each player gets its own session id.
            androidx.media3.session.MediaSession.Builder(this, p).setId("player-${System.identityHashCode(this)}").build()
        } catch (e: Exception) {
            null
        }
        playerView.player = if (isLive) p else EpisodeAwarePlayer(p)

        if (isLive) playChannel(channelIndex) else startVod()
    }

    private fun startVod() {
        val p = player ?: return
        cancelPendingSeek()
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

    /** Glass card: resume from the saved spot or start over. */
    private var resumeDialog: android.app.Dialog? = null

    private fun askResumeOrRestart() {
        val pos = startPosition
        val dialog = android.app.Dialog(this)
        resumeDialog = dialog
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val view = layoutInflater.inflate(R.layout.dialog_resume, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val total = watchEntry?.duration?.takeIf { it > 0 }
        view.findViewById<TextView>(R.id.resume_title).text = vodTitle
        view.findViewById<TextView>(R.id.resume_where).text =
            "You stopped at ${formatDuration(pos)}" + (total?.let { " of ${formatDuration(it)}" } ?: "")
        view.findViewById<ProgressBar>(R.id.resume_bar).apply {
            if (total != null) progress = (pos * 1000 / total).toInt().coerceIn(0, 1000) else visibility = View.GONE
        }
        var chosen = false
        view.findViewById<Button>(R.id.resume_go).apply {
            text = "▶  Resume from ${formatDuration(pos)}"
            setOnClickListener {
                chosen = true
                dialog.dismiss()
                player?.playWhenReady = true
            }
            post { requestFocus() }
        }
        view.findViewById<Button>(R.id.resume_restart).setOnClickListener {
            chosen = true
            dialog.dismiss()
            player?.let {
                it.seekTo(0)
                startPosition = 0
                it.playWhenReady = true
            }
        }
        dialog.setOnDismissListener { if (!chosen) player?.playWhenReady = true }
        dialog.show()
    }

    /** With the title, which the TV shows for the mini player ("now playing"). */
    private fun mediaItem(url: String, mimeType: String?): MediaItem =
        MediaItem.Builder().setUri(url)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(if (isLive) currentChannel?.name ?: "" else vodTitle)
                    .setDisplayTitle(if (isLive) currentChannel?.name ?: "" else vodTitle)
                    .build(),
            )
            .build()

    /** Tells the TV what is playing (title in the mini player's corner box). */
    private var mediaSession: androidx.media3.session.MediaSession? = null

    /** Settings > Player > Match frame rate: once per stream, when the frame rate is known. */
    private var frameRateFor: String? = null

    /** Frame rate measured from the decoded frames (streams that don't state it). */
    private var measuredFps = 0f
    private var fpsProbe: androidx.media3.exoplayer.video.VideoFrameMetadataListener? = null

    private fun applyFrameRate() {
        if (!prefs.matchFrameRate) return
        val p = player as? ExoPlayer ?: return
        if (frameRateFor == currentUrl) return
        val stated = p.videoFormat?.frameRate?.takeIf { it > 1f }
        if (stated == null) {
            measureFrameRate(p)
            return
        }
        switchFrameRate(stated)
    }

    private fun switchFrameRate(fps: Float) {
        if (frameRateFor == currentUrl) return
        frameRateFor = currentUrl
        if (PlaybackTuning.matchFrameRate(this, fps)) {
            Toast.makeText(this, "Matching ${"%.3f".format(java.util.Locale.US, fps).trimEnd('0').trimEnd('.')} fps", Toast.LENGTH_SHORT).show()
        }
    }

    /** Time ~40 frames, take the typical gap, and snap it to a standard rate (23.976 … 60). */
    private fun measureFrameRate(p: ExoPlayer) {
        if (fpsProbe != null) return
        val times = ArrayList<Long>()
        val url = currentUrl
        val probe = androidx.media3.exoplayer.video.VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            synchronized(times) {
                if (times.size >= 41) return@VideoFrameMetadataListener
                times += presentationTimeUs
                if (times.size == 41) {
                    val gaps = times.sorted().zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
                    val gap = gaps.getOrNull(gaps.size / 2) ?: return@VideoFrameMetadataListener
                    val raw = 1_000_000f / gap
                    val fps = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f).minByOrNull { Math.abs(it - raw) }!!
                    runOnUiThread {
                        fpsProbe?.let { (player as? ExoPlayer)?.clearVideoFrameMetadataListener(it) }
                        fpsProbe = null
                        measuredFps = fps
                        if (url == currentUrl && Math.abs(fps - raw) < 1.5f) switchFrameRate(fps)
                    }
                }
            }
        }
        fpsProbe = probe
        p.setVideoFrameMetadataListener(probe)
    }

    private fun releasePlayer() {
        val p = player ?: return
        // A jump still waiting: save the place it was going to.
        if (seekTarget >= 0) p.seekTo(seekTarget)
        cancelPendingSeek()
        fpsProbe?.let { (p as? ExoPlayer)?.clearVideoFrameMetadataListener(it) }
        fpsProbe = null
        if (!isLive) saveVodPosition(p)
        p.removeListener(playerListener)
        mediaSession?.release()
        mediaSession = null
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
        override fun onIsPlayingChanged(isPlaying: Boolean) = scheduleScreensaver()

        override fun onPlaybackStateChanged(state: Int) {
            progress.visibility = if (state == Player.STATE_BUFFERING && !isInPictureInPictureModeCompat()) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) {
                errorView.visibility = View.GONE
                vodRetries = 0
                // Show what's playing for a few seconds when a movie or episode starts.
                if (!isLive && !titleShown && !isInPictureInPictureModeCompat()) {
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
                if (prefs.autoNext && hasNextEpisode && !sleepAtEnd) {
                    autoEpisodes++
                    if (autoEpisodes >= STILL_WATCHING_AFTER) askStillWatching() else playEpisodeAt(episodeIndex + 1)
                } else {
                    finish()
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (isLive) showLiveQuality()
            applyFrameRate()
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
            // Live: a 1-connection account is refused for a few seconds after changing channels
            // (the server still counts the old one). Try again a few times before showing an error.
            if (isLive && liveRetries < LIVE_RETRIES && (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
            ) {
                liveRetries++
                p.stop()
                errorView.text = "Connecting…"
                errorView.visibility = View.VISIBLE
                progress.visibility = View.VISIBLE
                playerView.removeCallbacks(retryLive)
                playerView.postDelayed(retryLive, 1_500L * liveRetries)
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

    /** Up / Down zapping: the banner follows every press, the stream starts once you stop. */
    private val startZap = Runnable { playChannel(channelIndex, banner = false) }

    private fun zapBy(step: Int) {
        val list = Session.zapList
        if (list.isEmpty()) return finish()
        channelIndex = Math.floorMod(channelIndex + step, list.size)
        playerView.removeCallbacks(retryLive)
        // Drop the old stream now (one connection), so the next one can start straight away.
        player?.stop()
        errorView.visibility = View.GONE
        showOverlay(list[channelIndex])
        playerView.removeCallbacks(startZap)
        playerView.postDelayed(startZap, 350)
    }

    private fun playChannel(index: Int, banner: Boolean = true, retry: Boolean = false) {
        playerView.removeCallbacks(startZap)
        playerView.removeCallbacks(retryLive)
        if (!retry) liveRetries = 0
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
        if (banner) showOverlay(channel)
    }

    private fun playUrl(url: String, mimeType: String? = null) {
        val p = player ?: return
        // A new channel gets a fresh video decoder: TV decoders (MediaTek) reused across a
        // picture-size change keep playing sound but show a black picture.
        if (isLive) p.stop()
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
                // Zapping fast: only ask for the guide of the channel you stop on.
                kotlinx.coroutines.delay(300)
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

    /** Several episodes in a row with nobody pressing a key: check before going on. */
    private fun askStillWatching() {
        val next = episodes.getOrNull(episodeIndex + 1)
        AlertDialog.Builder(this)
            .setTitle("Are you still watching?")
            .setMessage(next?.let { "Next: ${it.label()}" } ?: "")
            .setPositiveButton("Continue watching") { _, _ ->
                autoEpisodes = 0
                playEpisodeAt(episodeIndex + 1)
            }
            .setNegativeButton("Stop") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    // ---- Mini player (picture-in-picture) ----

    private var wasMini = false

    /** The remote's Home button while a video plays: keep it playing in a corner (Settings > Player). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isFinishing && !leavingForApp) enterMiniPlayer()
    }

    /** This player is opening the app's own screens (not the user leaving): no corner. */
    private var leavingForApp = false

    /** Into the corner (Settings > Player). False if not possible. */
    private fun enterMiniPlayer(): Boolean {
        if (!prefs.miniPlayer || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        // Playing, or about to (buffering counts): not when paused.
        if (player?.playWhenReady != true) return false
        // Clear the picture first so the shrink shows only the video.
        clearForMini()
        return try {
            enterPictureInPictureMode(miniParams())
        } catch (e: Exception) {
            false
        }
    }

    /** The corner window takes the video's shape, and the shrink starts from where the picture is. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun miniParams(): android.app.PictureInPictureParams {
        val size = player?.videoSize
        // Android allows 1:2.39 to 2.39:1.
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            val r = size.width * size.pixelWidthHeightRatio / size.height
            android.util.Rational((r.coerceIn(1f / 2.39f, 2.39f) * 1000).toInt(), 1000)
        } else {
            android.util.Rational(16, 9)
        }
        val hint = android.graphics.Rect()
        playerView.videoSurfaceView?.getGlobalVisibleRect(hint)
        return android.app.PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .apply { if (!hint.isEmpty) setSourceRectHint(hint) }
            .build()
    }

    /** Everything over the video goes, at once (no fades running during the shrink). */
    private fun clearForMini() {
        if (::controls.isInitialized && !isLive) controls.hideNow()
        closePanels(refocus = false)
        if (::overlay.isInitialized) overlay.visibility = View.GONE
        hideScreensaver()
        channelsPanel.visibility = View.GONE
        hideCard()
        progress.visibility = View.GONE
        playFlash.visibility = View.GONE
        seekForward.visibility = View.GONE
        seekBack.visibility = View.GONE
        numberEntry.visibility = View.GONE
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            wasMini = true
            // Only the picture in the corner.
            clearForMini()
            // The app itself came up underneath (not the TV's home screen): no corner inside the
            // app, back to full screen.
            playerView.postDelayed({
                val screen = MiniPlayerBar.appScreen?.get()
                if (wasMini && !isFinishing && screen != null && !screen.isFinishing) expandMini(screen)
            }, 300)
        } else {
            wasMini = false
            if (!isFinishing) restoredFromMini = true
            playerView.requestFocus()
        }
    }

    /** The task this player was opened in (before the corner window moved it to its own). */
    private var originTaskId = -1

    /** Opened again from the corner (the app icon or the corner itself): Back now closes it. */
    private var restoredFromMini = false

    /**
     * The mini player lives in its own task, so finishing it alone would land on the TV's home
     * screen. Bring the app's own screens (the details page underneath) back instead.
     */
    private fun closeToApp() {
        leavingForApp = true
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        // The task the video started in (page, list, Home underneath): it leaves that task
        // when it goes into the corner, so go back to it by its id.
        if (originTaskId != -1 && originTaskId != taskId) {
            try {
                am.moveTaskToFront(originTaskId, 0)
                finish()
                return
            } catch (e: Exception) {
                // Gone (the app was closed meanwhile): open the app normally below.
            }
        }
        // No such task any more: open the app normally.
        val launch = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            if (launch != null) startActivity(launch)
        } catch (e: Exception) {
            // Nothing to bring back: just close.
        }
        finish()
    }

    // ---- Screensaver when paused ----

    private var screensaver: View? = null
    private val showScreensaver = Runnable { showScreensaverNow() }

    /** Movies / episodes paused for a minute: poster, title and clock instead of a still frame. */
    private fun scheduleScreensaver() {
        if (!::playerView.isInitialized) return
        playerView.removeCallbacks(showScreensaver)
        val p = player ?: return
        if (isLive || !prefs.pauseScreensaver || p.isPlaying || p.playbackState != Player.STATE_READY) {
            if (p.isPlaying) hideScreensaver()
            return
        }
        playerView.postDelayed(showScreensaver, 60_000)
    }

    private fun showScreensaverNow() {
        val p = player ?: return
        if (p.isPlaying || isInPictureInPictureModeCompat()) return
        closePanels(refocus = false)
        if (::controls.isInitialized) controls.hide()
        val view = screensaver ?: layoutInflater.inflate(R.layout.view_screensaver, findViewById(android.R.id.content), false).also {
            (findViewById<android.view.ViewGroup>(android.R.id.content)).addView(it)
            screensaver = it
        }
        view.findViewById<android.widget.ImageView>(R.id.saver_poster).load(watchEntry?.image ?: seriesCover) { crossfade(true) }
        view.findViewById<TextView>(R.id.saver_title).text = vodTitle
        val total = p.duration.takeIf { it > 0 }
        view.findViewById<TextView>(R.id.saver_where).text =
            "Paused at ${formatDuration(p.currentPosition)}" + (total?.let { " of ${formatDuration(it)}" } ?: "")
        view.findViewById<TextView>(R.id.saver_clock).text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(600).start()
        // Keep the clock right while it is up.
        view.postDelayed(object : Runnable {
            override fun run() {
                if (view.visibility != View.VISIBLE) return
                view.findViewById<TextView>(R.id.saver_clock).text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                view.postDelayed(this, 30_000)
            }
        }, 30_000)
    }

    private fun hideScreensaver(): Boolean {
        val view = screensaver ?: return false
        if (view.visibility != View.VISIBLE) return false
        view.visibility = View.GONE
        return true
    }

    private fun isInPictureInPictureModeCompat() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Screensaver up: any key just takes it away (and the minute starts again).
        if (screensaver?.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_UP) {
                hideScreensaver()
                scheduleScreensaver()
            }
            return true
        }
        // Somebody is there: start counting episodes again.
        if (event.action == KeyEvent.ACTION_DOWN) autoEpisodes = 0
        // Menu open: plain D-pad navigation; Back (remote or mouse) closes it.
        if (::menuPanel.isInitialized && menuPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) closeMenu()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
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
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> zapBy(1)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> zapBy(-1)
                else -> if (event.repeatCount == 0) toggleOverlay()
            }
        }
        return true
    }

    // ---- Movies & episodes: controls, next/previous, episode list ----

    private val controlsHost = object : VodControls.Host {
        override val player: Player? get() = this@PlayerActivity.player
        override fun positionMs() = seekPosition()
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
        override fun openEpisodes() {
            if (episodesPanel.visibility == View.VISIBLE) closeEpisodes() else this@PlayerActivity.openEpisodes()
        }
        override fun openTracks() {
            closePanels(refocus = false)
            showTrackPicker()
        }
        override fun openSettings() {
            if (menuPanel.visibility == View.VISIBLE) closeMenu() else showSettings()
        }
        override fun beforeAction() = closePanels(refocus = false)
        override fun onControlsHidden() {
            playerView.requestFocus()
        }
    }

    private fun seekVod(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration
        val max = if (duration != C.TIME_UNSET && duration > 0) duration - 1_000 else Long.MAX_VALUE
        requestSeek((seekPosition() + deltaMs).coerceIn(0, max))
    }

    /**
     * Holding a seek key: every seek on a network stream starts a new download, so the presses
     * add up and the player jumps once you let go (a single press still seeks at once).
     */
    private var seekTarget = -1L
    private var lastSeekAt = 0L
    private val commitSeek = Runnable {
        val target = seekTarget
        seekTarget = -1
        if (target >= 0) player?.seekTo(target)
    }

    /** Where playback is, or where it is about to jump to. */
    private fun seekPosition(): Long = if (seekTarget >= 0) seekTarget else player?.currentPosition ?: 0

    private fun requestSeek(target: Long) {
        val now = SystemClock.uptimeMillis()
        val burst = seekTarget >= 0 || now - lastSeekAt < 500
        lastSeekAt = now
        playerView.removeCallbacks(commitSeek)
        if (burst) {
            seekTarget = target
            playerView.postDelayed(commitSeek, 400)
        } else {
            seekTarget = -1
            player?.seekTo(target)
        }
    }

    private fun cancelPendingSeek() {
        seekTarget = -1
        if (::playerView.isInitialized) playerView.removeCallbacks(commitSeek)
    }

    private fun setupVodChrome() {
        val shelf = prefs.episodesStyle == 1
        episodeAdapter = TextAdapter(
            if (shelf) R.layout.item_episode_card else R.layout.item_episode_glass,
            bind = { vh, ep -> bindEpisodeRow(vh, ep) },
            onClick = { _, ep ->
                closeEpisodes()
                val index = episodes.indexOfFirst { it.id == ep.id }
                if (index >= 0 && index != episodeIndex) playEpisodeAt(index)
            },
            onLongClick = { _, ep -> toggleWatched(episodes.indexOfFirst { it.id == ep.id }, ep) },
        )
        episodesList.layoutManager = RowLayoutManager(this, if (shelf) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL, center = true)
        episodesList.adapter = episodeAdapter
        seasonAdapter = TextAdapter(
            R.layout.item_category_chip,
            bind = { vh, season ->
                vh.title.text = "Season $season"
                vh.subtitle?.text = ""
            },
            onClick = { _, season -> showSeason(season) },
        )
        seasonsList.layoutManager = RowLayoutManager(this)
        seasonsList.adapter = seasonAdapter
        // Side panel on the right, or a shelf across the bottom.
        val density = resources.displayMetrics.density
        episodesPanel.layoutParams = (episodesPanel.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            if (shelf) {
                width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                height = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                gravity = android.view.Gravity.BOTTOM
            } else {
                width = (600 * density).toInt()
                height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                gravity = android.view.Gravity.END
            }
        }
        if (shelf) episodesList.layoutParams = episodesList.layoutParams.apply { height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT }

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
        val seasons = episodes.map { it.season }.distinct()
        seasonAdapter.submit(seasons)
        seasonsList.visibility = if (seasons.size > 1) View.VISIBLE else View.GONE
        showSeason(episodes[episodeIndex].season)
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
        val key = "ep_${ep.id}"
        val pos = if (playing) player?.currentPosition ?: prefs.position(key) else prefs.position(key)
        val watched = prefs.isWatched(key)
        vh.subtitle?.text = listOfNotNull(
            when {
                playing -> "▶ Playing"
                pos > 0 -> "Resume ${formatDuration(pos)}"
                watched -> "✓ Watched"
                else -> null
            },
            ep.duration,
        ).joinToString("   ·   ")
        vh.itemView.findViewById<android.widget.ImageView?>(R.id.image)?.loadEpisodeImage(ep, seriesCover)
        vh.itemView.findViewById<android.widget.ProgressBar?>(R.id.watch_progress)?.apply {
            val total = if (playing) player?.duration?.takeIf { it > 0 } else null
            val length = total ?: parseDuration(ep.duration)
            val fraction = when {
                watched && !playing -> 1f
                length != null && length > 0 && pos > 0 -> (pos.toFloat() / length).coerceIn(0f, 1f)
                else -> -1f
            }
            visibility = if (fraction >= 0f) View.VISIBLE else View.GONE
            progress = (fraction.coerceAtLeast(0f) * 1000).toInt()
        }
    }

    /** "00:34:12" / "34:12" / "34 min" -> milliseconds. */
    private fun parseDuration(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(':').mapNotNull { it.trim().toLongOrNull() }
        return when {
            parts.size == 3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            parts.size == 2 -> (parts[0] * 60 + parts[1]) * 1000
            else -> Regex("(\\d+)").find(text)?.value?.toLongOrNull()?.times(60_000)
        }
    }

    /** Episodes panel: show one season (the chips pick it). */
    private fun showSeason(season: Int) {
        shownSeason = season
        episodeAdapter.submit(episodes.filter { it.season == season })
        seasonAdapter.activeIndex = seasonAdapter.items.indexOf(season)
    }

    /** Long-press in the Episodes panel: mark as watched / not watched. */
    private fun toggleWatched(index: Int, ep: Episode) {
        val key = "ep_${ep.id}"
        val watched = !prefs.isWatched(key)
        prefs.setWatched(key, watched)
        if (watched && index != episodeIndex) prefs.removeProgress(key)
        Toast.makeText(this, if (watched) "Marked as watched" else "Marked as not watched", Toast.LENGTH_SHORT).show()
        episodeAdapter.items.indexOfFirst { it.id == ep.id }.takeIf { it >= 0 }?.let { episodeAdapter.notifyItemChanged(it, "watched") }
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
        closeMenu(refocus = false)
        placeEpisodesPanel()
        episodesPanel.visibility = View.VISIBLE
        if (controlsShown()) controls.hold(true)
        // Open on the season and episode that is playing.
        showSeason(episodes[episodeIndex].season)
        val row = episodeAdapter.items.indexOfFirst { it.id == episodes[episodeIndex].id }.coerceAtLeast(0)
        episodeAdapter.activeIndex = row
        episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.itemCount, "progress")
        episodesList.scrollToPosition(row)
        episodesList.post { episodesList.findViewHolderForAdapterPosition(row)?.itemView?.requestFocus() }
    }

    private fun closeEpisodes(refocus: Boolean = true) {
        if (episodesPanel.visibility != View.VISIBLE) return
        episodesPanel.visibility = View.GONE
        if (controlsShown()) {
            controls.hold(false)
            if (refocus) findViewById<View>(R.id.vc_episodes).requestFocus()
        } else if (refocus) {
            playerView.requestFocus()
        }
    }

    /** Settings pop-up and episodes list: only one at a time, and either closes for another action. */
    private fun closePanels(refocus: Boolean) {
        closeMenu(refocus)
        closeEpisodes(refocus)
    }

    /** Episodes list: above the toolbar when it is up (side panel or bottom shelf). */
    private fun placeEpisodesPanel() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val shelf = prefs.episodesStyle == 1
        var bottom = dp(20)
        val bar = findViewById<View>(R.id.vc_row)
        val root = window.decorView
        if (controlsShown() && bar.isShown && root.height > 0) {
            val loc = IntArray(2)
            bar.getLocationInWindow(loc)
            bottom = root.height - loc[1] + dp(12)
        }
        (episodesPanel.layoutParams as android.widget.FrameLayout.LayoutParams).let { lp ->
            lp.setMargins(dp(20), dp(20), dp(20), bottom)
            episodesPanel.layoutParams = lp
        }
        if (!shelf) episodesPanel.requestLayout()
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
        val target = (seekPosition() + deltaMs).coerceIn(0, max)
        requestSeek(target)
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
        if (::episodesPanel.isInitialized && episodesPanel.visibility == View.VISIBLE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && ev.actionButton == MotionEvent.BUTTON_BACK
        ) {
            closeEpisodes()
            return true
        }
        // Menu open: the mouse back button closes it; moving the mouse doesn't touch the toolbar.
        if (::menuPanel.isInitialized && menuPanel.visibility == View.VISIBLE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                ev.actionButton == MotionEvent.BUTTON_BACK
            ) {
                closeMenu()
                return true
            }
            if (ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || ev.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) return true
            return super.dispatchGenericMotionEvent(ev)
        }
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
            episodesPanel.visibility == View.VISIBLE || isInPictureInPictureModeCompat()
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
        channelsList.layoutManager = RowLayoutManager(this, LinearLayoutManager.VERTICAL, center = true)
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

    /**
     * Glass menu (Settings > Player > Player menu): a small pop-up above the buttons or a side
     * panel. "Name:  value" items show the value on the right; [selected] marks a choice with ✓.
     */
    private fun showMenu(title: String, items: List<Pair<String, () -> Unit>>, selected: Int = -1) {
        closeEpisodes(refocus = false)
        placeMenuPanel()
        findViewById<TextView>(R.id.player_menu_title).apply {
            text = title
            textSize = if (prefs.playerMenuStyle == 0) 16f else 19f
        }
        val pad = ((if (prefs.playerMenuStyle == 0) 10 else 14) * resources.displayMetrics.density).toInt()
        menuPanel.setPadding(pad, pad, pad, pad)
        menuItems.removeAllViews()
        items.forEachIndexed { i, (text, action) ->
            val row = layoutInflater.inflate(R.layout.item_settings_option, menuItems, false)
            val name = text.substringBefore(":  ")
            val value = if (text.contains(":  ")) text.substringAfter(":  ") else if (i == selected) "✓" else ""
            row.findViewById<TextView>(R.id.title).text = name
            row.findViewById<TextView>(R.id.subtitle).text = value
            if (prefs.playerMenuStyle == 0) {
                // Compact rows in the pop-up.
                val d = resources.displayMetrics.density
                row.layoutParams = (row.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                    height = (38 * d).toInt()
                    bottomMargin = (3 * d).toInt()
                }
                row.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                row.findViewById<TextView>(R.id.title).textSize = 14f
                row.findViewById<TextView>(R.id.subtitle).textSize = 13f
            }
            row.setOnClickListener {
                closeMenu()
                action()
            }
            menuItems.addView(row)
        }
        menuPanel.visibility = View.VISIBLE
        // Opened from the controls: keep the toolbar up underneath while the menu is open.
        if (!isLive && controlsShown()) controls.hold(true)
        val focus = if (selected in items.indices) selected else 0
        menuItems.post { menuItems.getChildAt(focus)?.requestFocus() }
    }

    /** One of [labels] to pick, the current one ticked. */
    private fun showChoices(title: String, labels: List<String>, selected: Int, pick: (Int) -> Unit) =
        showMenu(title, labels.mapIndexed { i, label -> label to { pick(i) } }, selected)

    private fun closeMenu(refocus: Boolean = true) {
        if (menuPanel.visibility != View.VISIBLE) return
        menuPanel.visibility = View.GONE
        if (!isLive && controlsShown()) {
            controls.hold(false)
            if (refocus) findViewById<View>(R.id.vc_settings).requestFocus()
        } else if (refocus) {
            playerView.requestFocus()
        }
    }

    /**
     * Pop-up: on the right, just above the toolbar when the controls are up, otherwise in the
     * bottom-right corner. Side panel: full height on the right.
     */
    private fun placeMenuPanel() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val side = prefs.playerMenuStyle == 1
        val width = if (side) dp(460) else dp(360)
        var right = dp(40)
        var bottom = dp(40)
        val gear = findViewById<View>(R.id.vc_settings)
        val root = window.decorView
        if (!side && !isLive && controlsShown() && gear.isShown && root.width > 0) {
            val loc = IntArray(2)
            gear.getLocationInWindow(loc)
            // Right-hand side, just above the toolbar.
            bottom = root.height - loc[1] + dp(14)
        }
        menuPanel.layoutParams = (menuPanel.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            this.width = width
            height = if (side) android.widget.FrameLayout.LayoutParams.MATCH_PARENT else android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            gravity = if (side) android.view.Gravity.END else android.view.Gravity.BOTTOM or android.view.Gravity.END
            if (side) setMargins(dp(20), dp(20), dp(20), dp(20)) else setMargins(dp(20), dp(24), right, bottom)
        }
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
        showChoices("Subtitle size", SUBTITLE_SIZES.map { it.first }, prefs.subtitleSize.coerceIn(0, 3)) { which ->
            prefs.subtitleSize = which
            applySubtitleStyle()
        }
    }

    private fun speedLabel(s: Float): String {
        val text = if (s % 1f == 0f) s.toInt().toString() else s.toString()
        return if (s == 1f) "${text}× (normal)" else "$text×"
    }

    private fun pickSpeed() {
        showChoices("Playback speed", SPEEDS.map { speedLabel(it) }, SPEEDS.indexOf(speed).coerceAtLeast(0)) { which ->
            speed = SPEEDS[which]
            player?.setPlaybackSpeed(speed)
        }
    }

    private fun pictureLabel() = PICTURE_SIZES[prefs.pictureSize.coerceIn(0, PICTURE_SIZES.size - 1)].first

    private fun pickPictureSize() {
        showChoices("Picture size", PICTURE_SIZES.map { it.first }, prefs.pictureSize.coerceIn(0, PICTURE_SIZES.size - 1)) { which ->
            prefs.pictureSize = which
            playerView.resizeMode = PICTURE_SIZES[which].second
        }
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
            val fps = (v?.frameRate?.takeIf { it > 0 } ?: measuredFps.takeIf { it > 0 })
                ?.let { "  ·  ${"%.3f".format(java.util.Locale.US, it).trimEnd('0').trimEnd('.')} fps" } ?: ""
            lines += "Resolution:  ${size.width} × ${size.height}$fps"
        }
        // The TV's current refresh rate (Match frame rate changes it).
        window.decorView.display?.let { d -> lines += "TV refresh rate:  ${"%.2f".format(java.util.Locale.US, d.refreshRate).trimEnd('0').trimEnd('.')} Hz" }
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
        numberEntry.postDelayed(commitNumber, 2_000)
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
        // Our own screen, not the user leaving: no mini player for this.
        startActivity(GuideActivity.intent(this, channelIndex, fromPlayer = true).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    }

    override fun onDestroy() {
        PlaybackTuning.restoreFrameRate(this)
        if (current?.get() === this) current = null
        wasMini = false
        if (::playerView.isInitialized) playerView.removeCallbacks(showScreensaver)
        if (::overlay.isInitialized) overlay.removeCallbacks(hideOverlay)
        if (::controls.isInitialized) controls.release()
        if (::playerView.isInitialized) playerView.removeCallbacks(sleepNow)
        if (::playerView.isInitialized) playerView.removeCallbacks(retryVod)
        if (::playerView.isInitialized) playerView.removeCallbacks(startZap)
        if (::playerView.isInitialized) playerView.removeCallbacks(retryLive)
        if (::numberEntry.isInitialized) numberEntry.removeCallbacks(commitNumber)
        super.onDestroy()
    }
}
