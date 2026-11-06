package com.osama.xtreamtv

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Glass theme: the control to put the highlight back on after a choice rebuilds the screen. */
private var pendingFocus: String? = null

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
    private val glass get() = prefs.themeIndex == Appearance.THEME_GLASS
    /** Glass: rows go into the current card; [cardHost] is where new cards go. */
    private var target: android.view.ViewGroup? = null
    private var cardHost: android.view.ViewGroup? = null
    /** Left-hand menu: entry id and the section it opens. */
    private var menu: List<Pair<Int, Section>> = emptyList()

    private val focusMap = FocusMap { currentFocus }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        focusMap.handle(event) || super.dispatchKeyEvent(event)

    /**
     * Two sections. Right from the menu: the open section's first option. Left from the options: back
     * to that section's menu entry. Up and Down stop at the ends of each.
     */
    private fun setupFocusMap() {
        val entries = menu.map { findViewById<View>(it.first) }
        val menuSection = focusMap.section { entries }
        val options = focusMap.section(searchInside = true) { listOf(body) }
        menuSection.exits[View.FOCUS_RIGHT] = {
            focusFirstOption()
            null
        }
        options.exits[View.FOCUS_LEFT] = { menu.firstOrNull { it.second == section }?.let { findViewById<View>(it.first) } }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** A look change rebuilds the screen: take a picture of it first (see onCreate). */
    override fun recreate() {
        val decor = window.decorView
        if (android.os.Build.VERSION.SDK_INT >= 26 && decor.width > 0 && decor.height > 0) {
            try {
                val picture = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(window, picture, { result ->
                    if (result == android.view.PixelCopy.SUCCESS) carriedScreen = picture
                    rebuild()
                }, android.os.Handler(android.os.Looper.getMainLooper()))
                return
            } catch (e: Throwable) {
                // No picture (out of memory, no surface yet): rebuild as before.
            }
        }
        rebuild()
    }

    private fun rebuild() = super.recreate()

    /** The old screen's picture on top of the new one; it fades once the new one has been drawn. */
    private fun coverWith(picture: android.graphics.Bitmap) {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val cover = android.widget.ImageView(this).apply {
            setImageBitmap(picture)
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            isFocusable = false
        }
        root.addView(cover)
        // Two frames later the new screen (and its background) is on screen underneath.
        root.post {
            root.postOnAnimation {
                cover.animate().alpha(0f).setDuration(220).withEndAction {
                    root.removeView(cover)
                    // Back to the theme's own base colour, unless the screen already set its own.
                    if (window.decorView.background is android.graphics.drawable.BitmapDrawable) {
                        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(themeColor(R.attr.appBg)))
                    }
                }.start()
            }
        }
    }

    companion object {
        /** The picture taken by [recreate], handed to the rebuilt screen. */
        private var carriedScreen: android.graphics.Bitmap? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Appearance.apply(this)
        // The screen as it was before a look change rebuilt it: shown over the new one until that
        // is drawn, then faded out (on a slower TV the rebuild showed a black screen for a second).
        val before = carriedScreen
        carriedScreen = null
        if (before != null) window.setBackgroundDrawable(android.graphics.drawable.BitmapDrawable(resources, before))
        setContentView(R.layout.activity_settings)
        if (before != null) coverWith(before)
        body = findViewById(R.id.section_body)
        if (glass) glassFrame()

        menu = listOf(
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
            item.setOnClickListener { show(s); focusFirstOption() }
        }
        setupFocusMap()
        // After changing the colour the screen is rebuilt: come back to the same section.
        val start = savedInstanceState?.getString("section")?.let { runCatching { Section.valueOf(it) }.getOrNull() } ?: Section.HOME
        show(start)
        // Rebuilt after a Look change: back on the section's first option (where the change was made).
        if (savedInstanceState != null) body.post {
            val picked = pendingFocus?.let { body.findViewWithTag<View>(it) }
            pendingFocus = null
            picked?.requestFocus() ?: chosenDot?.takeIf { section == Section.COLOR }?.requestFocus() ?: focusFirstOption()
        }
        else findViewById<View>(menu.first { it.second == start }.first).requestFocus()
    }

    /** Glass: slimmer menu column, smaller titles, more room for the options. */
    private fun glassFrame() {
        val eyebrow = findViewById<View>(R.id.settings_eyebrow)
        eyebrow.visibility = View.VISIBLE
        val column = eyebrow.parent as View
        column.layoutParams = column.layoutParams.apply { width = dp(232) }
        val scroll = body.parent.parent as View
        scroll.layoutParams = (scroll.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply { leftMargin = dp(16) }
        findViewById<TextView>(R.id.section_title).textSize = 21f
        findViewById<TextView>(R.id.section_hint).apply {
            textSize = 13f
            layoutParams = (layoutParams as android.view.ViewGroup.MarginLayoutParams).apply { bottomMargin = dp(12) }
        }
        for ((id, _) in menu) findViewById<TextView>(id).apply {
            textSize = 15f
            layoutParams = (layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                height = dp(44)
                bottomMargin = dp(2)
            }
        }
    }

    /** First option of the shown section, scrolled into view at the top. */
    private fun focusFirstOption() {
        (body.parent.parent as? android.widget.ScrollView)?.scrollTo(0, 0)
        firstFocusable(body)?.requestFocus()
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
        target = null
        cardHost = null
        if (glass) {
            // The open section uses Selected, and an eyebrow names it over the title.
            for ((id, entry) in menu) findViewById<View>(id).isActivated = entry == s
            val eyebrow = findViewById<TextView>(R.id.section_eyebrow)
            eyebrow.visibility = View.VISIBLE
            eyebrow.text = findViewById<TextView>(menu.first { it.second == s }.first).text
        }
        val title = findViewById<TextView>(R.id.section_title)
        val hint = findViewById<TextView>(R.id.section_hint)
        when (s) {
            Section.HOME -> {
                title.text = "Home layout"
                hint.text = "How the first screen after choosing a playlist looks"
                choiceCards("H", Appearance.HOME_NAMES, prefs.homeStyle) { prefs.homeStyle = it }
                if (glass) plainCard()
                option("Most recent movies", listOf(1 to "On", 0 to "Off"), { prefs.homeRecentMovies }, { prefs.homeRecentMovies = it })
                option("Most recent series", listOf(1 to "On", 0 to "Off"), { prefs.homeRecentSeries }, { prefs.homeRecentSeries = it })
                option("Menu button on Home", listOf(0 to "Opens the quick drawer", 1 to "Does nothing"), { prefs.homeMenuButton }, { prefs.homeMenuButton = it })
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
                hint.text = "Theme, colours, background, text size and effects"
                if (glass) {
                    glassLookOptions()
                } else {
                    themeOption()
                    // A theme sets its own colours and background.
                    if (prefs.themeIndex == 0) colors()
                    lookOptions()
                }
            }
            Section.AVATARS -> {
                title.text = "Playlists"
                hint.text = "Which playlist opens at start, and each playlist's avatar"
                val playlists = prefs.playlists()
                if (playlists.size > 1) {
                    option(
                        "Open at start",
                        listOf<Pair<String?, String>>(null to "Ask every time") + playlists.map { it.id to it.name },
                        { prefs.defaultPlaylistId },
                        { prefs.defaultPlaylistId = it },
                    )
                    heading("Avatars", R.drawable.ic_glass_user)
                }
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
                diagnostics()
            }
        }
    }

    /**
     * About: the diagnostics report (see Diag), shared over the home Wi-Fi. Glass: two cards side by
     * side, its settings on the left and the report with its actions on the right.
     */
    private fun diagnostics() {
        var right: LinearLayout? = null
        if (glass) {
            val columns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            }
            fun column(rightMargin: Int) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { this.rightMargin = rightMargin }
            }
            val left = column(dp(12))
            right = column(0)
            columns.addView(left)
            columns.addView(right)
            body.addView(columns)
            cardHost = left
        }
        heading("Diagnostics", R.drawable.ic_glass_pulse)
        (target ?: body).addView(TextView(this).apply {
            setTextColor(themeColor(R.attr.appTextSecondary))
            textSize = 13f
            setPadding(dp(10), 0, dp(10), dp(8))
            text = "Keeps a report of how the app behaves. No addresses, logins or passwords."
        })
        option("Diagnostics", listOf(true to "On", false to "Off"), { Diag.enabled }, { Diag.enabled = it })
        option("Include titles", listOf(true to "On", false to "Off"), { Diag.includeTitles }, { Diag.includeTitles = it })
        option("Keep report for", Diag.KEEP_DAYS.map { it to "$it days" }, { Diag.keepDays }, { Diag.keepDays = it; recreate() })

        if (right != null) cardHost = right
        heading("Report", R.drawable.ic_glass_file)
        val host = target ?: body
        val stats = Diag.stats()
        val fullPercent = stats.kb * 1024 * 100 / (10L * 1024 * 1024)
        host.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // One inset for everything in this card: the same as the rows in the card beside it.
            setPadding(dp(10), dp(2), dp(10), dp(10))
            for ((label, value) in listOf(
                "Entries" to stats.entries.toString(),
                "Size" to "${stats.kb} KB",
                "Days left" to (stats.daysLeft?.toString() ?: "–"),
            )) {
                val lastBox = label == "Days left"
                // Size turns yellow from 80% full and red from 95%.
                val warn = if (label == "Size" && fullPercent >= 95) DiagUi.RED else if (label == "Size" && fullPercent >= 80) DiagUi.YELLOW
                    else if (label == "Days left" && (stats.daysLeft ?: 99) <= 3 && stats.entries > 0) DiagUi.RED else null
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    if (warn != null) {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setColor((warn and 0x00FFFFFF) or 0x22000000)
                            setStroke(dp(1), (warn and 0x00FFFFFF) or 0x66000000)
                        }
                    } else {
                        setBackgroundResource(R.drawable.bg_value_chip)
                    }
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (!lastBox) rightMargin = dp(8) }
                    addView(TextView(context).apply {
                        text = label
                        textSize = 12f
                        setTextColor(themeColor(R.attr.appTextSecondary))
                    })
                    addView(TextView(context).apply {
                        text = value
                        textSize = 17f
                        setTextColor(warn ?: themeColor(R.attr.appText))
                    })
                })
            }
        })
        // Warnings: a crash to send, almost full, entries about to go, marked problems waiting.
        for (w in Diag.warnings()) {
            host.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor((w.color and 0x00FFFFFF) or 0x22000000)
                    setStroke(dp(1), (w.color and 0x00FFFFFF) or 0x66000000)
                }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(10)
                    rightMargin = dp(10)
                    bottomMargin = dp(10)
                }
                addView(TextView(context).apply {
                    text = w.text
                    textSize = 13f
                    setTextColor(w.color)
                })
            })
        }
        fun action(label: String, icon: Int, danger: Boolean = false, onClick: () -> Unit) = pill(label).also { b ->
            b.setOnClickListener { onClick() }
            b.background = DiagUi.buttonBackground(this, 999)
            b.minHeight = dp(46)
            // Icon then words, from the left, like the rows in the card beside it.
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.setPadding(dp(16), 0, dp(16), 0)
            val drawable = androidx.core.content.ContextCompat.getDrawable(this, icon)!!.mutate()
            drawable.setBounds(0, 0, dp(20), dp(20))
            if (danger) androidx.core.graphics.drawable.DrawableCompat.setTint(drawable, 0xFFF2A7A7.toInt())
            b.setCompoundDrawablesRelative(drawable, null, null, null)
            b.compoundDrawablePadding = dp(12)
            b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(10)
            }
            if (danger) b.setTextColor(0xFFF2A7A7.toInt())
            host.addView(b)
        }
        action("Share report", R.drawable.ic_glass_share) { DiagUi.shareFlow(this) }
        action("Mark a problem now", R.drawable.ic_glass_flag) {
            DiagUi.mark("Settings", this)
        }
        action("Clear report", R.drawable.ic_glass_trash, danger = true) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear the report?")
                .setMessage("Everything recorded so far is deleted. Recording goes on if Diagnostics is on.")
                .setPositiveButton("Clear") { _, _ -> Diag.clear(); recreate() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        host.addView(TextView(this).apply {
            text = "Last shared: ${stats.lastShare ?: "not yet"}"
            textSize = 13f
            setTextColor(themeColor(R.attr.appTextSecondary))
            setPadding(dp(12), 0, dp(10), dp(8))
        })
        // Both cards end at the same height.
        right?.let { r ->
            val left = (r.parent as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
            for (column in listOf(left, r)) {
                column.layoutParams = (column.layoutParams as LinearLayout.LayoutParams).apply { height = LinearLayout.LayoutParams.MATCH_PARENT }
                column.getChildAt(0)?.let { card -> card.layoutParams = card.layoutParams.apply { height = LinearLayout.LayoutParams.MATCH_PARENT } }
            }
        }
        cardHost = null
        target = null
    }

    private fun pill(text: String): TextView =
        (LayoutInflater.from(this).inflate(R.layout.item_glass_pill, body, false) as TextView).also { it.text = text }

    /** Four picture cards in two rows; the chosen one is marked and saved at once. */
    private fun choiceCards(prefix: String, names: List<String>, current: Int, save: (Int) -> Unit) {
        val cards = ArrayList<View>()
        var row: LinearLayout? = null
        names.forEachIndexed { index, name ->
            if (index % 2 == 0 && (!glass || index == 0)) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    FocusMap.choices(this)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = if (glass) dp(12) else dp(18) }
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
                    if (glass) { if (index < names.lastIndex) rightMargin = dp(8) } else if (index % 2 == 0) rightMargin = dp(18)
                }
                onFocusChangeListener = scaleOnFocus
            }
            card.addView(LayoutThumbView(this).apply {
                kind = "$prefix$index"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val label = TextView(this).apply {
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.on_focus_text))
                isDuplicateParentStateEnabled = true
                textSize = if (glass) 14f else 17f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            }
            card.addView(label)
            card.tag = label
            fun mark(chosen: Int) {
                cards.forEachIndexed { i, c ->
                    c.isActivated = i == chosen
                    // Glass marks the chosen card with its fill.
                    (c.tag as TextView).text = if (glass) names[i] else (if (i == chosen) "✓  " else "") + "$prefix${i + 1} · ${names[i]}"
                }
            }
            card.setOnClickListener {
                save(index)
                mark(index)
            }
            cards.add(card)
            row!!.addView(card)
            if (!glass && index == names.lastIndex && index % 2 == 0) row!!.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
            if (index == names.lastIndex) mark(current)
        }
    }

    /** Settings > Player: a heading per group, then one row per option (OK picks a value). */
    private fun playerOptions() {
        val languages = listOf(null to "Automatic", "ar" to "Arabic", "en" to "English", "fr" to "French", "tr" to "Turkish")
        val subtitleLanguages = listOf(PlayerActivity.SUBTITLES_OFF to "Off", "ar" to "Arabic", "en" to "English", "fr" to "French", "tr" to "Turkish")
        heading("Playback", R.drawable.ic_glass_play)
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
        if (glass) {
            inlineButtons("Player menu", listOf("Small pop-up", "Side panel", "Cards"), prefs.playerMenuStyle, "player_menu") { prefs.playerMenuStyle = it }
        } else {
            option("Player menu", listOf(0 to "Small pop-up", 1 to "Side panel", 2 to "Cards"), { prefs.playerMenuStyle }, { prefs.playerMenuStyle = it })
        }
        option("Home button during a video", listOf(true to "Keep playing in a corner", false to "Stop the video"), { prefs.miniPlayer }, { prefs.miniPlayer = it })
        option("Screensaver when paused", listOf(true to "On", false to "Off"), { prefs.pauseScreensaver }, { prefs.pauseScreensaver = it })
        option("Hide controls after", listOf(3, 5, 8, 15).map { it to "$it seconds" }, { prefs.controlsHideSeconds }, { prefs.controlsHideSeconds = it })
        heading("Picture and sound", R.drawable.ic_glass_picture)
        option("Match frame rate", listOf(false to "Off", true to "On (TV switches to the video's rate)"), { prefs.matchFrameRate }, { prefs.matchFrameRate = it })
        option("Decoder", PlaybackTuning.DECODER_NAMES.mapIndexed { i, n -> i to n }, { prefs.decoderMode }, { prefs.decoderMode = it })
        option("Buffer size", PlaybackTuning.BUFFER_NAMES.mapIndexed { i, n -> i to n }, { prefs.bufferSize }, { prefs.bufferSize = it })
        option("Picture size", PlayerActivity.PICTURE_SIZES.mapIndexed { i, size -> i to size.first }, { prefs.pictureSize }, { prefs.pictureSize = it })
        option("Audio language", languages, { prefs.audioLanguage }, { prefs.audioLanguage = it })
        option("Subtitles", subtitleLanguages, { prefs.subtitleLanguage ?: PlayerActivity.SUBTITLES_OFF }, { prefs.subtitleLanguage = it })
        option("Subtitle size", listOf(0 to "Small", 1 to "Medium", 2 to "Large", 3 to "Extra large"), { prefs.subtitleSize }, { prefs.subtitleSize = it })
        option("Subtitle background", listOf(false to "Off", true to "Dark box"), { prefs.subtitleBackground }, { prefs.subtitleBackground = it })
        heading("Live TV", R.drawable.ic_glass_tv)
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
                    false -> setTextColor(themeColor(R.attr.appError))
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


    private fun themeOption() =
        option("Theme", Appearance.THEMES, { prefs.themeIndex }, { prefs.themeIndex = it; recreate() })

    /** Look with Theme = Glass: the Screen card beside the Selected item and Effects cards. */
    private fun glassLookOptions() {
        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun column(weight: Float, rightMargin: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { this.rightMargin = rightMargin }
        }
        val left = column(1f, dp(12))
        val right = column(1.3f, 0)
        columns.addView(left)
        columns.addView(right)
        body.addView(columns)

        cardHost = left
        heading("Screen", R.drawable.ic_glass_screen)
        themeOption()
        labelRow("Background")
        sceneSwatches()
        labelRow("Glass style")
        buttonRow(Appearance.GLASS_STYLE_NAMES, prefs.glassStyle, "glass_style") { prefs.glassStyle = it }
        option("Interface size", Look.UI_SIZES, { prefs.uiSize }, { prefs.uiSize = it; recreate() })
        option("Text size", Look.TEXT_SIZE_NAMES.mapIndexed { i, n -> i to n }, { prefs.textSize }, { prefs.textSize = it; recreate() })

        cardHost = right
        heading("Selected item", R.drawable.ic_glass_focus)
        labelRow("How the item under the remote looks")
        buttonRow(Appearance.FOCUS_NAMES, prefs.focusStyle, "focus_style") { prefs.focusStyle = it }
        heading("Effects", R.drawable.ic_glass_spark)
        option("Moving background", listOf(true to "On", false to "Off"), { prefs.movingBackground }, { prefs.movingBackground = it; pendingFocus = "moving"; recreate() }, "moving")
        option("Background follows the poster", listOf(true to "On", false to "Off"), { prefs.backdropFollows }, { prefs.backdropFollows = it })
        option("Highlighted poster", listOf(true to "Rises with a glow", false to "Simple"), { prefs.posterGlow }, { prefs.posterGlow = it })
        option("Featured banner on Home", listOf(true to "On", false to "Off"), { prefs.featuredBanner }, { prefs.featuredBanner = it })
        cardHost = null
        target = null
    }

    /** A plain label inside a card. */
    private fun labelRow(text: String) {
        (target ?: body).addView(TextView(this).apply {
            this.text = text
            setTextColor(themeColor(R.attr.appText))
            textSize = 14f
            setPadding(dp(10), dp(10), 0, dp(6))
        })
    }

    /** One choice per button; the current one uses Selected. [rebuild]: the choice changes the look. */
    private fun buttonRow(names: List<String>, current: Int, tagPrefix: String, rebuild: Boolean = true, pick: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            FocusMap.choices(this)
            orientation = LinearLayout.HORIZONTAL
            // One-line and two-line labels stay lined up.
            isBaselineAligned = false
        }
        names.forEachIndexed { i, name ->
            row.addView(TextView(this).apply {
                text = name
                tag = "$tagPrefix$i"
                gravity = Gravity.CENTER
                textSize = 13f
                setPadding(dp(4), dp(3), dp(4), dp(3))
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.on_focus_text))
                setBackgroundResource(R.drawable.bg_glass_choice)
                isFocusable = true
                isClickable = true
                isActivated = i == current
                maxLines = 2
                // Grows with its text; the others in the row match it.
                minHeight = dp(38)
                // Sized from its name, so a longer name gets more of the row and no word breaks in two.
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (i < names.lastIndex) rightMargin = dp(6) }
                setOnClickListener {
                    pick(i)
                    if (rebuild) {
                        pendingFocus = "$tagPrefix$i"
                        recreate()
                    } else {
                        for (c in 0 until row.childCount) row.getChildAt(c).isActivated = c == i
                    }
                }
            })
        }
        val host = target ?: body
        host.addView(row)
        if (host is LinearLayout && host.orientation == LinearLayout.VERTICAL && host !== body) {
            row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(8)
            }
        }
    }

    /** Name on the left, its choices as buttons on the right, on one line. */
    private fun inlineButtons(name: String, names: List<String>, current: Int, tagPrefix: String, pick: (Int) -> Unit) {
        val host = target ?: body
        addDivider(host)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(TextView(context).apply {
                text = name
                textSize = 14f
                setTextColor(themeColor(R.attr.appText))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        host.addView(line)
        target = line
        buttonRow(names, current, tagPrefix, rebuild = false, pick = pick)
        target = host as? LinearLayout
        line.getChildAt(1).layoutParams = LinearLayout.LayoutParams(dp(104) * names.size, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    /** Faint line between rows of a card. */
    private fun addDivider(host: android.view.ViewGroup) {
        if (host === body || host.childCount == 0) return
        if (host.getChildAt(host.childCount - 1).tag == "divider") return
        host.addView(View(this).apply {
            tag = "divider"
            setBackgroundColor(themeColor(R.attr.appRowDivider))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
            }
        })
    }

    /** Background scenes as swatch tiles (3 a row, wide enough for their names), picked directly. */
    private fun sceneSwatches() {
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            FocusMap.choices(this)
        }
        (target ?: body).addView(host)
        var row: LinearLayout? = null
        Appearance.SCENE_NAMES.forEachIndexed { i, name ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = dp(10)
                        rightMargin = dp(10)
                        bottomMargin = dp(6)
                    }
                }
                host.addView(row)
            }
            val colors = Appearance.SCENE_SWATCHES[i]
            val gradient = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(colors[1], colors[2]))
            val highlight = android.graphics.drawable.GradientDrawable().apply {
                gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(44).toFloat()
                setGradientCenter(0.15f, 0.1f)
                this.colors = intArrayOf(colors[0], colors[0] and 0x00FFFFFF)
            }
            val swatch = View(this).apply {
                background = android.graphics.drawable.LayerDrawable(arrayOf(gradient, highlight))
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat())
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = "scene$i"
                setBackgroundResource(R.drawable.bg_glass_choice)
                setPadding(dp(5), dp(5), dp(5), dp(3))
                isFocusable = true
                isClickable = true
                isActivated = i == prefs.glassScene
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i % 3 != 2) rightMargin = dp(6) }
                addView(swatch)
                addView(TextView(context).apply {
                    text = name
                    gravity = Gravity.CENTER
                    textSize = 11f
                    isSingleLine = true
                    duplicateParentStateEnabled()
                    setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.on_focus_text))
                    setPadding(0, dp(3), 0, 0)
                })
                setOnClickListener {
                    if (prefs.glassScene != i) {
                        prefs.glassScene = i
                        pendingFocus = "scene$i"
                        recreate()
                    }
                }
            }
            row!!.addView(tile)
        }
        // Keep the last row's tiles the same size as the first row's.
        if (Appearance.SCENE_NAMES.size % 3 != 0) row!!.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, (3 - Appearance.SCENE_NAMES.size % 3).toFloat())
        })
    }

    private fun TextView.duplicateParentStateEnabled() {
        isDuplicateParentStateEnabled = true
    }

    /** Glass: an empty card that the next rows go into. */
    private fun plainCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            FocusBox.open(this)
            setBackgroundResource(R.drawable.bg_settings_card)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        (cardHost ?: body).addView(card)
        target = card
        return card
    }

    /** Glass: a heading starts a new card, with an accent icon. */
    private fun startCard(text: String, icon: Int) {
        val card = plainCard()
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), 0, dp(8))
            addView(android.widget.ImageView(context).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) }
            })
            addView(TextView(context).apply {
                this.text = text
                setTextColor(themeColor(R.attr.appText))
                textSize = 15f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
        })
        addDivider(card)
    }

    private fun heading(text: String, icon: Int = R.drawable.ic_glass_spark) {
        if (glass) return startCard(text, icon)
        body.addView(TextView(this).apply {
            this.text = text
            setTextColor(themeColor(R.attr.appTextSecondary))
            textSize = 15f
            setPadding(dp(6), if (body.childCount == 0) 0 else dp(16), 0, dp(6))
        })
    }

    /** A row showing the current choice; OK opens the list of choices. */
    private fun <T> option(name: String, choices: List<Pair<T, String>>, get: () -> T, set: (T) -> Unit, tag: String? = null) {
        val host = target ?: body
        val row = LayoutInflater.from(this).inflate(R.layout.item_settings_option, host, false)
        row.tag = tag
        val value = row.findViewById<TextView>(R.id.subtitle)
        row.findViewById<TextView>(R.id.title).text = name
        if (glass) {
            // Slimmer rows inside the cards.
            row.layoutParams = (row.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                height = dp(48)
                bottomMargin = 0
            }
            row.setPadding(dp(10), 0, dp(10), 0)
            addDivider(host)
            row.findViewById<TextView>(R.id.title).textSize = 14f
            value.textSize = 13f
        }
        // On/off rows are switches in every theme (not "On"/"Off" words).
        val isSwitch = choices.size == 2 && choices.map { it.second }.toSet() == setOf("On", "Off")
        if (isSwitch) {
            // On/off rows: a switch, flipped straight away.
            value.visibility = View.GONE
            val toggle = GlassSwitch(this, glass && prefs.glassScene == Appearance.SCENE_DAYLIGHT, solid = !glass)
            fun onChoice() = choices.first { it.second == "On" }.first
            toggle.on = get() == onChoice()
            (row as android.view.ViewGroup).addView(toggle)
            row.setOnClickListener {
                val next = if (get() == onChoice()) choices.first { it.second == "Off" }.first else onChoice()
                set(next)
                toggle.on = next == onChoice()
            }
            host.addView(row)
            return
        }
        fun show() {
            val label = choices.firstOrNull { it.first == get() }?.second ?: choices.first().second
            value.text = if (glass) "$label  ▾" else label
        }
        show()
        if (glass) {
            // Pick-from-a-list rows: the value as a small button.
            value.setBackgroundResource(R.drawable.bg_value_chip)
            value.setPadding(dp(12), dp(6), dp(12), dp(6))
            value.setTextColor(androidx.core.content.ContextCompat.getColorStateList(this, R.color.on_focus_text))
        }
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
        host.addView(row)
    }

    /** Settings > Look: background, text size and the visual effects. */
    private fun lookOptions() {
        heading("Screen", R.drawable.ic_glass_screen)
        if (prefs.paletteIndex == 0 && prefs.themeIndex == 0) {
            option("Background", Look.BACKGROUND_NAMES.mapIndexed { i, n -> i to n }, { prefs.backgroundStyle }, { prefs.backgroundStyle = it; recreate() })
        }
        option("Interface size", Look.UI_SIZES, { prefs.uiSize }, { prefs.uiSize = it; recreate() })
        option("Text size", Look.TEXT_SIZE_NAMES.mapIndexed { i, n -> i to n }, { prefs.textSize }, { prefs.textSize = it; recreate() })
        heading("Effects")
        option("Background follows the poster", listOf(true to "On", false to "Off"), { prefs.backdropFollows }, { prefs.backdropFollows = it })
        option("Highlighted poster", listOf(true to "Rises with a glow", false to "Simple"), { prefs.posterGlow }, { prefs.posterGlow = it })
        option("Featured banner on Home", listOf(true to "On", false to "Off"), { prefs.featuredBanner }, { prefs.featuredBanner = it })
    }

    /** The theme colours; picking one rebuilds this screen in the new colour. */
    /** The selected colour dot (the highlight returns to it after the screen is rebuilt). */
    private var chosenDot: View? = null

    private fun colors() {
        val dots = ArrayList<View>()
        val name = TextView(this).apply {
            setTextColor(themeColor(R.attr.appTextSecondary))
            textSize = 16f
            setPadding(dp(4), dp(12), 0, 0)
            text = "Current: " + if (prefs.paletteIndex != 0) Appearance.PALETTE_NAMES[prefs.paletteIndex] else
                Appearance.ACCENT_NAMES[prefs.accentIndex.coerceIn(0, Appearance.ACCENT_NAMES.size - 1)]
        }
        fun addDot(color: Int, label: String, active: Boolean, pick: () -> Unit) {
            val dot = AvatarPicker.colorDot(this, color)
            dot.layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                rightMargin = dp(8)
                bottomMargin = dp(6)
            }
            (dot.getChildAt(0).layoutParams as android.widget.FrameLayout.LayoutParams).apply { width = dp(38); height = dp(38) }
            dot.isActivated = active
            if (active) chosenDot = dot
            dot.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) name.text = label }
            dot.setOnClickListener { if (!active) { pick(); recreate() } }
            dots.add(dot)
        }
        // Theme colours: the accent only (background chosen below)…
        Appearance.ACCENT_COLORS.forEachIndexed { index, color ->
            addDot(color, Appearance.ACCENT_NAMES[index], prefs.paletteIndex == 0 && index == prefs.accentIndex) {
                prefs.paletteIndex = 0
                prefs.accentIndex = index
            }
        }
        // …and palettes, which recolour the whole app, background included.
        for (i in 1 until Appearance.PALETTE_NAMES.size) {
            addDot(Appearance.PALETTE_DOTS[i], Appearance.PALETTE_NAMES[i] + " (whole app)", prefs.paletteIndex == i) {
                prefs.paletteIndex = i
            }
        }
        // One row when they fit, more when they don't: every colour is on screen, none cut at the edge.
        val rows = FlowRow(this).apply { FocusMap.choices(this) }
        dots.forEach(rows::addView)
        body.addView(rows, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(name)
    }

    /** One row per playlist: its avatar, name and "Change". */
    private fun avatars() {
        val playlists = prefs.playlists()
        if (playlists.isEmpty()) {
            body.addView(TextView(this).apply {
                text = "No playlists yet."
                setTextColor(themeColor(R.attr.appTextSecondary))
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
