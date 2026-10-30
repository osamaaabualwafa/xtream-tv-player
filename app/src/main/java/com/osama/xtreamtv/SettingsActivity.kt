package com.osama.xtreamtv

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings (S2): Home layout, movies and series layout (picture cards), theme colour,
 * playlist avatars and About. Moving through the menu on the left shows each section.
 */
class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Look.wrap(newBase))

    private enum class Section { HOME, BROWSE, PLAYER, COLOR, AVATARS, CONNECTION, ABOUT }

    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout
    private var section: Section? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Appearance.apply(this)
        setContentView(R.layout.activity_settings)
        body = findViewById(R.id.section_body)

        val menu = listOf(
            R.id.menu_home to Section.HOME,
            R.id.menu_browse to Section.BROWSE,
            R.id.menu_player to Section.PLAYER,
            R.id.menu_color to Section.COLOR,
            R.id.menu_avatars to Section.AVATARS,
            R.id.menu_connection to Section.CONNECTION,
            R.id.menu_about to Section.ABOUT,
        )
        for ((id, s) in menu) {
            val item = findViewById<View>(id)
            item.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) show(s) }
            item.setOnClickListener { show(s); body.getChildAt(0)?.let { firstFocusable(it)?.requestFocus() } }
        }
        // After changing the colour the screen is rebuilt: come back to the same section.
        val start = savedInstanceState?.getString("section")?.let { runCatching { Section.valueOf(it) }.getOrNull() } ?: Section.HOME
        show(start)
        findViewById<View>(menu.first { it.second == start }.first).requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        section?.let { outState.putString("section", it.name) }
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun show(s: Section) {
        if (section == s) return
        section = s
        body.removeAllViews()
        val title = findViewById<TextView>(R.id.section_title)
        val hint = findViewById<TextView>(R.id.section_hint)
        when (s) {
            Section.HOME -> {
                title.text = "Home layout"
                hint.text = "How the first screen after choosing a playlist looks"
                choiceCards("H", Appearance.HOME_NAMES, prefs.homeStyle) { prefs.homeStyle = it }
            }
            Section.BROWSE -> {
                title.text = "Movies and series"
                hint.text = "How the screen after opening Movies or Series looks"
                choiceCards("B", Appearance.BROWSE_NAMES, prefs.browseStyle) { prefs.browseStyle = it }
            }
            Section.PLAYER -> {
                title.text = "Player"
                hint.text = "How movies, episodes and live channels play"
                playerOptions()
            }
            Section.COLOR -> {
                title.text = "Look"
                hint.text = "Colours, background, text size and effects"
                colors()
                lookOptions()
            }
            Section.AVATARS -> {
                title.text = "Playlist avatars"
                hint.text = "Without an avatar, a playlist shows the first letter of its name"
                avatars()
            }
            Section.CONNECTION -> {
                title.text = "Connection check"
                hint.text = "Tests your account, the server and your internet speed"
                connectionCheck()
            }
            Section.ABOUT -> {
                title.text = "About"
                hint.text = "Xtream TV  ·  Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
                val check = pill("Check for updates")
                check.setOnClickListener { Updates.checkNow(this, lifecycleScope) }
                body.addView(check)
            }
        }
    }

    private fun pill(text: String): TextView =
        (LayoutInflater.from(this).inflate(R.layout.item_glass_pill, body, false) as TextView).also { it.text = text }

    /** Four picture cards in two rows; the chosen one is marked and saved at once. */
    private fun choiceCards(prefix: String, names: List<String>, current: Int, save: (Int) -> Unit) {
        val cards = ArrayList<View>()
        var row: LinearLayout? = null
        names.forEachIndexed { index, name ->
            if (index % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = dp(18) }
                }
                body.addView(row)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_glass_choice)
                setPadding(dp(10), dp(10), dp(10), dp(8))
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index % 2 == 0) rightMargin = dp(18)
                }
                onFocusChangeListener = scaleOnFocus
            }
            card.addView(LayoutThumbView(this).apply {
                kind = "$prefix$index"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val label = TextView(this).apply {
                setTextColor(getColor(R.color.text))
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            }
            card.addView(label)
            card.tag = label
            fun mark(chosen: Int) {
                cards.forEachIndexed { i, c ->
                    c.isActivated = i == chosen
                    (c.tag as TextView).text = (if (i == chosen) "✓  " else "") + "$prefix${i + 1} · ${names[i]}"
                }
            }
            card.setOnClickListener {
                save(index)
                mark(index)
            }
            cards.add(card)
            row!!.addView(card)
            if (index == names.lastIndex) mark(current)
        }
    }

    /** Settings > Player: a heading per group, then one row per option (OK picks a value). */
    private fun playerOptions() {
        val languages = listOf(null to "Automatic", "ar" to "Arabic", "en" to "English", "fr" to "French", "tr" to "Turkish")
        val subtitleLanguages = listOf(PlayerActivity.SUBTITLES_OFF to "Off", "ar" to "Arabic", "en" to "English", "fr" to "French", "tr" to "Turkish")
        heading("Playback")
        option("Forward jump", listOf(5, 10, 15, 30, 60).map { it to "$it seconds" }, { prefs.seekForwardSeconds }, { prefs.seekForwardSeconds = it })
        option("Back jump", listOf(5, 10, 15, 30).map { it to "$it seconds" }, { prefs.seekBackSeconds }, { prefs.seekBackSeconds = it })
        option("Saved position", listOf(0 to "Ask each time", 1 to "Always resume", 2 to "Start from the beginning"), { prefs.resumeMode }, { prefs.resumeMode = it })
        option("Next episode", listOf(true to "Play automatically", false to "Stop at the end"), { prefs.autoNext }, { prefs.autoNext = it })
        option("Next episode countdown", listOf(10, 20, 30, 60).map { it to "$it seconds before the end" }, { prefs.nextCountdownSeconds }, { prefs.nextCountdownSeconds = it })
        option(
            "Play movies and series in",
            ExternalPlayer.PLAYERS.mapIndexed { i, p -> i to (p.first + if (i > 0 && ExternalPlayer.installedPackage(this, i) == null) "  (not installed)" else "") },
            { prefs.vodPlayer }, { prefs.vodPlayer = it },
        )
        option("Episodes list", listOf(0 to "Side panel", 1 to "Bottom shelf"), { prefs.episodesStyle }, { prefs.episodesStyle = it })
        option("Player menu", listOf(0 to "Small pop-up", 1 to "Side panel"), { prefs.playerMenuStyle }, { prefs.playerMenuStyle = it })
        option("Back from the player", listOf(true to "Keep playing in a corner", false to "Close the video"), { prefs.miniPlayer }, { prefs.miniPlayer = it })
        option("Screensaver when paused", listOf(true to "On", false to "Off"), { prefs.pauseScreensaver }, { prefs.pauseScreensaver = it })
        option("Hide controls after", listOf(3, 5, 8, 15).map { it to "$it seconds" }, { prefs.controlsHideSeconds }, { prefs.controlsHideSeconds = it })
        heading("Picture and sound")
        option("Match frame rate", listOf(false to "Off", true to "On (TV switches to the video's rate)"), { prefs.matchFrameRate }, { prefs.matchFrameRate = it })
        option("Decoder", PlaybackTuning.DECODER_NAMES.mapIndexed { i, n -> i to n }, { prefs.decoderMode }, { prefs.decoderMode = it })
        option("Buffer size", PlaybackTuning.BUFFER_NAMES.mapIndexed { i, n -> i to n }, { prefs.bufferSize }, { prefs.bufferSize = it })
        option("Picture size", listOf(0 to "Fit", 1 to "Zoom (fill screen)", 2 to "Stretch"), { prefs.pictureSize }, { prefs.pictureSize = it })
        option("Audio language", languages, { prefs.audioLanguage }, { prefs.audioLanguage = it })
        option("Subtitles", subtitleLanguages, { prefs.subtitleLanguage ?: PlayerActivity.SUBTITLES_OFF }, { prefs.subtitleLanguage = it })
        option("Subtitle size", listOf(0 to "Small", 1 to "Medium", 2 to "Large", 3 to "Extra large"), { prefs.subtitleSize }, { prefs.subtitleSize = it })
        option("Subtitle background", listOf(false to "Off", true to "Dark box"), { prefs.subtitleBackground }, { prefs.subtitleBackground = it })
        heading("Live TV")
        option("Channel preview in the list", listOf(true to "On", false to "Off"), { prefs.channelPreview }, { prefs.channelPreview = it })
        option("Stream type", listOf(0 to "Automatic", 1 to "HLS (m3u8)", 2 to "MPEG-TS"), { prefs.streamType }, { prefs.streamType = it })
    }

    /** Account, server response time and download speed (5 seconds of a live channel). */
    private fun connectionCheck() {
        val run = pill("Run check")
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(run)
        body.addView(results)
        fun line(name: String, value: String, good: Boolean? = null) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_settings_option, results, false)
            row.isFocusable = false
            row.findViewById<TextView>(R.id.title).text = name
            row.findViewById<TextView>(R.id.subtitle).apply {
                text = value
                when (good) {
                    true -> setTextColor(0xFF4ADE80.toInt())
                    false -> setTextColor(getColor(R.color.error))
                    null -> {}
                }
            }
            results.addView(row)
        }
        var running = false
        run.setOnClickListener {
            // (Not disabled while running: a disabled button would send the highlight back to the menu.)
            if (running) return@setOnClickListener
            val api = Session.api(this) ?: return@setOnClickListener line("Playlist", "Open a playlist first", false)
            results.removeAllViews()
            running = true
            run.text = "Checking…"
            lifecycleScope.launch {
                try {
                    // Account and server response time.
                    val t0 = System.currentTimeMillis()
                    val info = try { api.userInfo() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    val ms = System.currentTimeMillis() - t0
                    if (info == null) {
                        line("Server", "Not reachable", false)
                        return@launch
                    }
                    line("Server response", "$ms ms", ms < 1500)
                    line("Account", (info.status ?: "Unknown") + (info.expDate?.let { "  ·  until " + formatDate(it) } ?: ""), accountProblem(info) == null)
                    val used = info.activeCons?.toIntOrNull()
                    val max = info.maxConnections?.toIntOrNull()
                    if (used != null && max != null) line("Connections in use", "$used of $max", used < max)
                    // Download speed: a movie file downloads as fast as the line allows
                    // (a live channel only arrives at its own bitrate), so test with a movie.
                    val movie = (Session.streamCache["MOVIE:__all__"]
                        ?: api.categories(ContentType.MOVIE).firstOrNull()?.let { api.streams(ContentType.MOVIE, it.id) })?.firstOrNull()
                    val testUrl = movie?.let { api.movieUrl(it.id, it.ext) }
                        ?: (Session.streamCache["LIVE:__all__"]
                            ?: api.categories(ContentType.LIVE).firstOrNull()?.let { api.streams(ContentType.LIVE, it.id) })?.firstOrNull()?.let { api.liveUrl(it.id, "ts") }
                    if (testUrl == null) {
                        line("Download speed", "Nothing to test with", null)
                        return@launch
                    }
                    run.text = "Measuring speed…"
                    val mbps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { measureSpeed(testUrl) }
                    if (mbps == null) {
                        line("Download speed", "Couldn't measure", false)
                    } else {
                        val verdict = when {
                            mbps >= 25 -> "great for 4K"
                            mbps >= 8 -> "good for HD"
                            mbps >= 3 -> "enough for SD"
                            else -> "too slow, expect buffering"
                        }
                        line("Download speed", String.format(java.util.Locale.US, "%.1f Mbps  ·  %s", mbps, verdict), mbps >= 8)
                    }
                } finally {
                    running = false
                    run.text = "Run check again"
                }
            }
        }
    }

    /** Megabits per second over ~5 seconds of a stream; null when nothing could be read. */
    private fun measureSpeed(url: String): Double? {
        // Streams are often redirected to another server (sometimes http <-> https): follow by hand.
        var target = url
        var conn: java.net.HttpURLConnection? = null
        try {
            for (hop in 0 until 5) {
                val c = (java.net.URL(target).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: return null
                    c.disconnect()
                    target = java.net.URL(java.net.URL(target), next).toString()
                    continue
                }
                if (code !in 200..299) {
                    android.util.Log.w("SpeedTest", "HTTP $code")
                    c.disconnect()
                    return null
                }
                conn = c
                break
            }
            val input = conn?.inputStream ?: return null
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            val start = System.nanoTime()
            val stop = start + 5_000_000_000L
            while (System.nanoTime() < stop && total < 40L * 1024 * 1024) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
            val seconds = (System.nanoTime() - start) / 1e9
            if (total == 0L) android.util.Log.w("SpeedTest", "no data")
            return if (total == 0L || seconds <= 0) null else total * 8 / seconds / 1_000_000
        } catch (e: Exception) {
            android.util.Log.w("SpeedTest", "failed: ${e.javaClass.simpleName}")
            return null
        } finally {
            conn?.disconnect()
        }
    }


    private fun heading(text: String) {
        body.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_secondary))
            textSize = 15f
            setPadding(dp(6), if (body.childCount == 0) 0 else dp(16), 0, dp(6))
        })
    }

    /** A row showing the current choice; OK opens the list of choices. */
    private fun <T> option(name: String, choices: List<Pair<T, String>>, get: () -> T, set: (T) -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_settings_option, body, false)
        val value = row.findViewById<TextView>(R.id.subtitle)
        row.findViewById<TextView>(R.id.title).text = name
        fun show() {
            value.text = choices.firstOrNull { it.first == get() }?.second ?: choices.first().second
        }
        show()
        row.setOnClickListener {
            val current = choices.indexOfFirst { it.first == get() }.coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(name)
                .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), current) { d, which ->
                    set(choices[which].first)
                    show()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        body.addView(row)
    }

    /** Settings > Look: background, text size and the visual effects. */
    private fun lookOptions() {
        heading("Screen")
        option("Background", Look.BACKGROUND_NAMES.mapIndexed { i, n -> i to n }, { prefs.backgroundStyle }, { prefs.backgroundStyle = it; recreate() })
        option("Text size", Look.TEXT_SIZE_NAMES.mapIndexed { i, n -> i to n }, { prefs.textSize }, { prefs.textSize = it; recreate() })
        heading("Effects")
        option("Background follows the poster", listOf(true to "On", false to "Off"), { prefs.backdropFollows }, { prefs.backdropFollows = it })
        option("Highlighted poster", listOf(true to "Rises with a glow", false to "Simple"), { prefs.posterGlow }, { prefs.posterGlow = it })
        option("Featured banner on Home", listOf(true to "On", false to "Off"), { prefs.featuredBanner }, { prefs.featuredBanner = it })
    }

    /** The theme colours; picking one rebuilds this screen in the new colour. */
    private fun colors() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val name = TextView(this).apply {
            setTextColor(getColor(R.color.text_secondary))
            textSize = 16f
            setPadding(dp(4), dp(12), 0, 0)
            text = "Current: " + Appearance.ACCENT_NAMES[prefs.accentIndex.coerceIn(0, Appearance.ACCENT_NAMES.size - 1)]
        }
        Appearance.ACCENT_COLORS.forEachIndexed { index, color ->
            val dot = AvatarPicker.colorDot(this, color)
            dot.layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(10) }
            (dot.getChildAt(0).layoutParams as android.widget.FrameLayout.LayoutParams).apply { width = dp(42); height = dp(42) }
            dot.isActivated = index == prefs.accentIndex
            dot.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) name.text = Appearance.ACCENT_NAMES[index] }
            dot.setOnClickListener {
                if (prefs.accentIndex != index) {
                    prefs.accentIndex = index
                    recreate()
                }
            }
            row.addView(dot)
        }
        body.addView(row)
        body.addView(name)
    }

    /** One row per playlist: its avatar, name and "Change". */
    private fun avatars() {
        val playlists = prefs.playlists()
        if (playlists.isEmpty()) {
            body.addView(TextView(this).apply {
                text = "No playlists yet."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 16f
            })
            return
        }
        for (p in playlists) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_settings_playlist, body, false)
            Appearance.bindAvatar(row.findViewById(R.id.initial), prefs, p.id, p.name, letterSizeSp = 20f, emojiSizeSp = 24f)
            row.findViewById<TextView>(R.id.title).text = p.name
            row.setOnClickListener {
                AvatarPicker.show(this, p) {
                    Appearance.bindAvatar(row.findViewById(R.id.initial), prefs, p.id, p.name, letterSizeSp = 20f, emojiSizeSp = 24f)
                }
            }
            body.addView(row)
        }
    }
}
