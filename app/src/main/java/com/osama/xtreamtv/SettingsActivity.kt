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

    private enum class Section { HOME, BROWSE, RATINGS, PLAYER, COLOR, AVATARS, PHONE, CONNECTION, ABOUT }

    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout
    private var section: Section? = null

    // Library data: where the backup file goes, and which file to restore.
    private val createLibrary = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) libraryWork("Backing up library data", false) { _, _ ->
            val tmp = java.io.File(cacheDir, "library-export.db")
            try {
                Library.export(this, tmp)
                contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } } ?: throw java.io.IOException("The file could not be written")
            } finally {
                tmp.delete()
            }
            "Library data saved"
        }
    }
    private val openLibrary = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreLibrary { tmp -> contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: throw java.io.IOException("The file could not be read") }
    }
    private val glass get() = prefs.themeIndex == Appearance.THEME_GLASS
    /** Glass: rows go into the current card; [cardHost] is where new cards go. */
    private var target: android.view.ViewGroup? = null
    private var cardHost: android.view.ViewGroup? = null
    /** Left-hand menu: entry id and the section it opens. */
    private var menu: List<Pair<Int, Section>> = emptyList()

    private var focusMap = FocusMap { currentFocus }

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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Appearance.apply(this)
        val name = savedInstanceState?.getString("section") ?: intent.getStringExtra(EXTRA_SECTION)
        val start = name?.let { runCatching { Section.valueOf(it) }.getOrNull() } ?: Section.HOME
        val rebuilt = savedInstanceState != null || intent.getBooleanExtra(EXTRA_REBUILT, false)
        build(start, rebuilt)
        // Opened over the old screen by a look change: close that one once this one is on screen.
        if (intent.getBooleanExtra(EXTRA_REBUILT, false)) {
            val decor = window.decorView
            decor.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    decor.post {
                        decor.viewTreeObserver.removeOnDrawListener(this)
                        val old = previous?.get()
                        previous = null
                        if (old != null && old !== this@SettingsActivity && !old.isFinishing) {
                            old.finish()
                            old.overridePendingTransition(0, 0)
                        }
                    }
                }
            })
        }
    }

    /** The whole screen, opened on [start]; [rebuilt]: after a look change, back on the option that changed. */
    private fun build(start: Section, rebuilt: Boolean) {
        setContentView(R.layout.activity_settings)
        body = findViewById(R.id.section_body)
        target = null
        cardHost = null
        if (glass) glassFrame()

        menu = listOf(
            R.id.menu_home to Section.HOME,
            R.id.menu_browse to Section.BROWSE,
            R.id.menu_ratings to Section.RATINGS,
            R.id.menu_player to Section.PLAYER,
            R.id.menu_color to Section.COLOR,
            R.id.menu_avatars to Section.AVATARS,
            R.id.menu_phone to Section.PHONE,
            R.id.menu_connection to Section.CONNECTION,
            R.id.menu_about to Section.ABOUT,
        )
        for ((id, s) in menu) {
            val item = findViewById<View>(id)
            item.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) show(s) }
            item.setOnClickListener { show(s); focusFirstOption() }
        }
        focusMap = FocusMap { currentFocus }
        setupFocusMap()
        section = null
        chosenDot = null
        show(start)
        if (rebuilt) body.post {
            // Back on the option that changed; else the chosen colour; else the first option.
            val picked = pendingFocus?.let { body.findViewWithTag<View>(it) }
            pendingFocus = null
            val landed = picked?.requestFocus() == true ||
                (section == Section.COLOR && chosenDot?.takeIf { it.isAttachedToWindow }?.requestFocus() == true)
            if (!landed) focusFirstOption()
        }
        else findViewById<View>(menu.first { it.second == start }.first).requestFocus()
    }

    /**
     * A look change (theme, background, colours, sizes…): a new Settings screen opens over this one,
     * with no animation, and this one closes once the new one is drawn. Android's usual reopen
     * left the TV black for about half a second between the two windows, and rebuilding in place
     * (1.3.4) kept the old text colours on Android 10.
     */
    override fun recreate() {
        previous = java.lang.ref.WeakReference(this)
        startActivity(
            android.content.Intent(this, SettingsActivity::class.java)
                .putExtra(EXTRA_SECTION, (section ?: Section.HOME).name)
                .putExtra(EXTRA_REBUILT, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val EXTRA_SECTION = "section"
        private const val EXTRA_REBUILT = "rebuilt"
        /** The screen being replaced by a look change (closed by the new one once it is drawn). */
        private var previous: java.lang.ref.WeakReference<SettingsActivity>? = null
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
                // 1.1A: which rows Home shows, and their order (its own screen, concept C).
                startAction("Arrange Home rows", R.drawable.ic_glass_list, "home_rows") {
                    startActivity(android.content.Intent(this, HomeRowsActivity::class.java))
                }
                option("Menu button on Home", listOf(0 to "Opens the quick drawer", 1 to "Does nothing"), { prefs.homeMenuButton }, { prefs.homeMenuButton = it })
                // Package 3 (2026-11-13): how Picked for you and Trending show movies and series, and the reason line.
                option("Picked for you and Trending", listOf(0 to "One mixed row", 1 to "Movies / Series switch"), { prefs.suggestRowsKind }, { prefs.suggestRowsKind = it })
                option("Say why a title was picked", listOf(true to "On", false to "Off"), { prefs.pickedWhy }, { prefs.pickedWhy = it })
            }
            Section.BROWSE -> {
                title.text = "Movies and series"
                hint.text = "How the screen after opening Movies or Series looks"
                choiceCards("B", Appearance.BROWSE_NAMES, prefs.browseStyle) { prefs.browseStyle = it }
                // 2026-11-13: official posters (sharper, original language) for titles matched to TMDB.
                option("Posters", listOf(false to "From the provider", true to "Official (TMDB) when known"), { prefs.officialPosters }, { prefs.officialPosters = it })
            }
            Section.RATINGS -> {
                title.text = "Ratings"
                hint.text = "IMDb and Rotten Tomatoes scores on posters and details"
                ratingsOptions()
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
            Section.PHONE -> {
                // Package 5, item 2 (approved 2026-11-13): its own place in the menu (owner: not under Home layout).
                title.text = "Use Phone"
                hint.text = "Search, play and use your phone as a remote, on the same Wi-Fi"
                startAction("Add a phone or remove one", R.drawable.ic_glass_phone, "phone_panel") { phonePanelCard() }
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
                        // A long number ("13957") stays on one line: it shrinks to fit its box instead of
                        // breaking in two at the biggest text sizes (size check, 2026-11-11).
                        // (maxLines, not singleLine: a single-line field scrolls sideways and never shrinks.)
                        maxLines = 1
                        ellipsize = null
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
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
            b.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_settings_action)
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

    /** Show the open section again (after the key window or an action changes what it shows). */
    private fun reshow(focusTag: String? = null) {
        val s = section ?: return
        // The highlight waits on this section's menu entry while the options are rebuilt: removing the
        // highlighted button would hand it to the menu's first entry, which opens Home layout.
        menu.firstOrNull { it.second == s }?.let { findViewById<View>(it.first).requestFocus() }
        section = null
        show(s)
        val v = focusTag?.let { body.findViewWithTag<View>(it) } ?: firstFocusable(body)
        v?.requestFocus()
    }

    /**
     * Settings > Ratings (1.1E). Before a key: Get started (what it is, Add key, How it works). With a key,
     * two cards as approved (visual 3): the key and its actions on the left; the switches, Check matching
     * and today's usage on the right. Other themes: the same cards one under the other.
     */
    private fun ratingsOptions() {
        if (!Ratings.hasKey) {
            // Get started: one card (unchanged from the first build).
            fun note(text: String) = (target ?: body).addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(themeColor(R.attr.appTextSecondary))
                setPadding(dp(10), dp(4), dp(10), dp(8))
            })
            heading("Get started", R.drawable.ic_glass_key)
            note("Show real scores from IMDb and Rotten Tomatoes on posters and details, like this:")
            (target ?: body).addView(TextView(this).apply {
                text = RatingBadges.line(context, RatingBadges.Scores("7.8", 92), themeColor(R.attr.appText))
                textSize = 17f
                setPadding(dp(10), 0, dp(10), dp(10))
            })
            note("It needs a free key from MDBList (about 2 minutes). Add it once with your phone, or type it with the remote.")
            startAction("Add key", R.drawable.ic_glass_key, "r_add") { RatingsUi.keyWindow(this) { reshow("r_add") } }
            startAction("How it works", R.drawable.ic_glass_file, "r_help") { RatingsUi.help(this) { RatingsUi.keyWindow(this) { reshow("r_add") } } }
            note("Your key stays on this TV. MDBList only gets a title's ID, or its name and year.")
            target = null
            return
        }
        var right: LinearLayout? = null
        if (glass) {
            val columns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
        fun small(text: CharSequence, tint: Int? = null, top: Int = 4) = (target ?: body).addView(TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(tint ?: themeColor(R.attr.appTextSecondary))
            setPadding(dp(10), dp(top), dp(10), dp(8))
        })
        run {
            heading("Your keys", R.drawable.ic_glass_key)
            small("Scores show like this:", top = 6)
            (target ?: body).addView(TextView(this).apply {
                text = RatingBadges.line(context, RatingBadges.Scores("7.8", 92), themeColor(R.attr.appText))
                textSize = 17f
                setPadding(dp(10), 0, dp(10), dp(8))
            })
            linkRow("MDBList", "Scores, awards, trailers", if (Ratings.keyWorks) "●  Valid · ••••${Ratings.keyEnd}" else "●  Not accepted · ••••${Ratings.keyEnd}", "r_key",
                tint = if (Ratings.keyWorks) DiagUi.GREEN else DiagUi.RED, onClick = null)
            linkRow("TMDB", "Finds titles that have no ID", when {
                !Tmdb.hasKey -> "Not added"
                Tmdb.keyWorks -> "●  Valid · ••••${Tmdb.keyEnd}"
                else -> "●  Not accepted · ••••${Tmdb.keyEnd}"
            }, "r_tmdb", tint = when {
                !Tmdb.hasKey -> themeColor(R.attr.appTextSecondary)
                Tmdb.keyWorks -> DiagUi.GREEN
                else -> DiagUi.RED
            }, onClick = null)
            addDivider(target ?: body)
            (target ?: body).addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10)) })
            // The same round buttons as the Report card in About: icon, then words.
            startAction("Add or change MDBList key", R.drawable.ic_glass_key, "r_add") { RatingsUi.keyWindow(this) { reshow("r_add") } }
            startAction(if (Tmdb.hasKey) "Add or change TMDB key" else "Add TMDB key", R.drawable.ic_glass_key, "r_add_tmdb") {
                RatingsUi.keyWindow(this, kind = RatingsUi.TMDB) { reshow("r_add_tmdb") }
            }
            startAction("Refresh saved scores", R.drawable.ic_glass_refresh, "r_refresh") {
                // Asks first (2026-11-10: pressed to see what it does, and the whole playlist started again from 0).
                val m = Ratings.matching()
                val lookups = (m.byId + m.byTmdb + m.byName + RatingsFill.BATCH - 1) / RatingsFill.BATCH
                confirm(
                    "Refresh saved scores?",
                    "Every saved score is asked again from MDBList" + (if (lookups > 0) ", about $lookups of today's lookups" else "") + ". " +
                        "Scores stay on screen until the new ones arrive, but the Whole playlist progress starts again from 0.\n\n" +
                        "Scores already refresh by themselves every week (recent titles) or month (older ones), so this is rarely needed.",
                    "Refresh",
                ) {
                    Ratings.refreshAll()
                    showToast(this, "Scores are being asked again", android.widget.Toast.LENGTH_SHORT)
                }
            }
            startAction("How it works", R.drawable.ic_glass_file, "r_help") { RatingsUi.help(this, null) }
            // Remove: with both keys, a choice of which; with one, a question about that one.
            startAction(if (Tmdb.hasKey) "Remove keys" else "Remove key", R.drawable.ic_glass_trash, "r_remove", danger = true) {
                fun removeMdblist(both: Boolean) = confirm(
                    if (both) "Remove both keys?" else "Remove the MDBList key?",
                    if (both) "Ratings switch off, and both keys and every saved score are deleted from this TV."
                    else "Ratings switch off, and the MDBList key and every saved score are deleted from this TV." + if (Tmdb.hasKey) " The TMDB key stays." else "",
                    "Remove",
                ) {
                    Ratings.removeKey(keepTmdb = !both)
                    Diag.log("ratings", if (both) "both keys removed" else "MDBList key removed")
                    reshow("r_add")
                }
                fun removeTmdb() = confirm(
                    "Remove the TMDB key?",
                    "Titles without an ID are no longer recognised by name. Your saved scores and the MDBList key stay.",
                    "Remove",
                ) {
                    Tmdb.removeKey()
                    RatingsFill.rebuild()
                    Diag.log("ratings", "TMDB key removed")
                    reshow("r_add_tmdb")
                }
                if (!Tmdb.hasKey) removeMdblist(both = false) else {
                    DiagUi.card(this, 520) { c ->
                        c.title("Which key to remove?", R.drawable.ic_glass_trash, 0xFFF2A7A7.toInt())
                        c.line("MDBList gives the scores. TMDB recognises titles that have no ID.", 13f)
                        fun choice(label: String, top: Int, action: (() -> Unit)?) = c.pill(c.row(c.dp(top)), label) { c.dialog.dismiss(); action?.invoke() }
                        choice("MDBList key", 14) { removeMdblist(both = false) }
                        choice("TMDB key", 8) { removeTmdb() }
                        choice("Both keys", 8) { removeMdblist(both = true) }
                        choice("Cancel", 8, null).let { v -> v.post { v.requestFocus() } }
                    }
                }
            }
        }
        small("Your keys stay on this TV. MDBList and TMDB only get a title's ID, or its name and year.", top = 8)

        // The right card: what is shown, matching, today's usage.
        if (right != null) cardHost = right
        heading("Ratings", R.drawable.ic_glass_star)
        option("Show ratings", listOf(true to "On", false to "Off"), { Ratings.enabled }, { Ratings.enabled = it })
        option("Show on posters", listOf(true to "On", false to "Off"), { Ratings.showOnPosters }, { Ratings.showOnPosters = it })
        option("Find by name", listOf(true to "On", false to "Off"), { Ratings.findByName }, { Ratings.findByName = it })
        small("No ID: the same name and year, and the same poster.", top = 0)
        run {
            val m = Ratings.matching()
            val tried = m.byId + m.byTmdb + m.byName + m.notFound
            val u = Ratings.usage()
            val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(u?.resetsAt ?: System.currentTimeMillis()))
            linkRow("Today", "Resets at $at · scores kept 7 to 30 days", u?.let { "${"%,d".format(it.used)} of ${"%,d".format(it.limit)}" } ?: "–", "r_today",
                tint = if (u != null && u.used * 10 >= u.limit * 8) DiagUi.YELLOW else null, onClick = null)
            linkRow("Matching so far", if (tried == 0) "Nothing looked up yet" else Ratings.matchingText(m), null, "r_tried", onClick = null)
            addDivider(target ?: body)
            (target ?: body).addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10)) })
            startAction("Check matching", R.drawable.ic_glass_check, "r_check") { RatingsUi.matching(this) }
        }

        // Glass: both cards end at the same height.
        right?.let { r ->
            val left = (r.parent as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
            for (column in listOf(left, r)) {
                column.layoutParams = (column.layoutParams as LinearLayout.LayoutParams).apply { height = LinearLayout.LayoutParams.MATCH_PARENT }
                column.getChildAt(0)?.let { c -> c.layoutParams = c.layoutParams.apply { height = LinearLayout.LayoutParams.MATCH_PARENT } }
            }
        }
        cardHost = null
        target = null
        wholePlaylistCard()
        target = null
        libraryCard()
        target = null
    }

    /**
     * Settings > Ratings > Library data (agreed 2026-11-10): Back up, Restore, Undo last change and
     * Clear for what the app has looked up, and what the last restore matched, playlist by playlist.
     * A playlist that is not on this TV is never named: the file carries no names.
     */
    private fun libraryCard() {
        heading("Library data", R.drawable.ic_glass_download)
        val host = target ?: body
        fun n(v: Int) = "%,d".format(v)
        fun small(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(themeColor(R.attr.appTextSecondary))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            host.addView(this)
        }
        val info = small("…")
        Thread {
            val (titles, works) = runCatching { Library.sizes() }.getOrDefault(0 to 0)
            runOnUiThread { info.text = "${n(works)} movies and series with scores are saved on this TV, for ${n(titles)} titles in your playlists. The backup file has no logins, addresses, keys or playlist names." }
        }.start()
        Library.lastSummary(this)?.let { s ->
            val day = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            small("Last restore: ${time.format(java.util.Date(s.at))}" + if (s.backupAt > 0) " · from the backup of ${day.format(java.util.Date(s.backupAt))}" else "")
            val names = prefs.playlists().associate { it.id to it.name }
            var other = 0
            s.sections.forEachIndexed { i, sec ->
                val name = sec.playlist?.let { names[it] }
                if (name != null) {
                    fun part(label: String, found: Int, of: Int) = if (of > 0) "$label ${n(found)} of ${n(of)}" else "$label ${n(found)}"
                    val known = sec.moviesOf + sec.seriesOf > 0
                    val missing = (sec.moviesOf - sec.movies).coerceAtLeast(0) + (sec.seriesOf - sec.series).coerceAtLeast(0)
                    linkRow(name, part("Movies", sec.movies, sec.moviesOf) + " · " + part("Series", sec.series, sec.seriesOf) + if (known) " matched · ${n(missing)} not matched" else " matched",
                        "●  On this TV", "lib_$i", tint = DiagUi.GREEN, onClick = null)
                } else {
                    other++
                    linkRow(if (other == 1) "Another playlist" else "Another playlist $other", "${n(sec.titles)} titles · kept for when you add it", "●  Not on this TV", "lib_$i",
                        tint = themeColor(R.attr.appTextSecondary), onClick = null)
                }
            }
            linkRow("Scores and details", "${n(s.worksNew)} were new on this TV · ${n(s.worksHad)} were already here", "●  Shared", "lib_works", tint = DiagUi.GREEN, onClick = null)
        }
        // One row when they fit, a second only when needed (the biggest sizes broke "Restore" in two).
        val buttons = FlowRow(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10); bottomMargin = dp(10); leftMargin = dp(6); rightMargin = dp(6)
            }
        }
        // The same buttons as the rest of Settings (height, shape, icon then words).
        fun button(label: String, icon: Int, tag: String, danger: Boolean = false, onClick: () -> Unit) = pill(label).also { b ->
            b.tag = tag
            b.setOnClickListener { onClick() }
            b.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_settings_action)
            b.minHeight = dp(46)
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.setPadding(dp(16), 0, dp(16), 0)
            val drawable = androidx.core.content.ContextCompat.getDrawable(this, icon)!!.mutate()
            drawable.setBounds(0, 0, dp(20), dp(20))
            if (danger) androidx.core.graphics.drawable.DrawableCompat.setTint(drawable, 0xFFF2A7A7.toInt())
            b.setCompoundDrawablesRelative(drawable, null, null, null)
            b.compoundDrawablePadding = dp(12)
            if (danger) b.setTextColor(0xFFF2A7A7.toInt())
            b.minWidth = dp(150)
            b.layoutParams = android.view.ViewGroup.MarginLayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(4); rightMargin = dp(4); topMargin = dp(4); bottomMargin = dp(4)
            }
            buttons.addView(b)
        }
        button("Back up", R.drawable.ic_glass_download, "lib_backup") { backUpLibrary() }
        button("Restore", R.drawable.ic_glass_file, "lib_restore") { pickLibrary() }
        if (Library.hasUndo(this)) button("Undo", R.drawable.ic_glass_refresh, "lib_undo") {
            confirm("Undo the last change?", "The library data goes back to how it was before the last Restore or Clear.", "Undo") {
                libraryWork("Putting the library data back", false) { _, _ -> if (Library.undo(this)) "Last change undone" else "Nothing to undo" }
            }
        }
        button("Clear", R.drawable.ic_glass_trash, "lib_clear", danger = true) {
            confirm("Clear library data?", "Every saved score and detail on this TV is deleted. The fill will look them up again. Playlists, favourites, settings and keys are not touched.", "Clear") {
                libraryWork("Clearing library data", false) { _, _ -> Library.clear(this); "Library data cleared" }
            }
        }
        host.addView(buttons)
    }

    /** A question with the highlight on Cancel, so a second press of OK does nothing. */
    private fun confirm(title: String, message: String, yes: String, onYes: () -> Unit) {
        val d = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .create()
        d.setOnShowListener { d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.requestFocus() }
        d.show()
    }

    private fun backUpLibrary() {
        if (Library.hasFilePicker(this, android.content.Intent.ACTION_CREATE_DOCUMENT)) {
            try {
                createLibrary.launch(Library.fileName())
                return
            } catch (e: android.content.ActivityNotFoundException) {
                // fall through to the app folder
            }
        }
        // No file picker on this TV: save in the app's own folder.
        val file = Library.fallbackFile(this)
        libraryWork("Backing up library data", false) { _, _ ->
            Library.export(this, file)
            "Saved to:\n${file.absolutePath}\n\nCopy this file to the other TV, then choose Restore there."
        }
    }

    private fun pickLibrary() {
        if (Library.hasFilePicker(this, android.content.Intent.ACTION_OPEN_DOCUMENT)) {
            try {
                openLibrary.launch(arrayOf("*/*"))
                return
            } catch (e: android.content.ActivityNotFoundException) {
                // fall through to the app folder
            }
        }
        val file = Library.newestFallback(this)
        if (file != null) {
            restoreLibrary { tmp -> file.copyTo(tmp, overwrite = true) }
        } else {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("No library data file found")
                .setMessage("This TV has no file picker. Put the file in this folder, then choose Restore again:\n${Library.fallbackFile(this).parentFile?.absolutePath}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** Restore: the pop-up with its bar while the backup is matched with this TV; the result then stays on the card. */
    private fun restoreLibrary(fetch: (java.io.File) -> Unit) {
        libraryWork("Restoring library data", true) { progress, cancelled ->
            val tmp = java.io.File(cacheDir, "library-import.db")
            try {
                progress(1, "Reading the file")
                fetch(tmp)
                Library.import(this, tmp, progress, cancelled)
            } finally {
                tmp.delete()
                java.io.File(tmp.path + "-journal").delete()
            }
            "Library data restored"
        }
    }

    /**
     * A pop-up with a bar while [work] runs off the screen's thread; then the page is drawn again and
     * [work]'s message shown. [work] gets (percent, what it is doing) and "was Cancel pressed?".
     */
    private fun libraryWork(title: String, canCancel: Boolean, work: ((Int, String) -> Unit, () -> Boolean) -> String) {
        var cancel = false
        var bar: android.widget.ProgressBar? = null
        var label: TextView? = null
        val card = DiagUi.card(this, 520) { c ->
            c.title(title, R.drawable.ic_glass_download, c.color(R.attr.appAccent))
            c.line(if (canCancel) "Matching the backup with this TV's playlists" else "One moment", 13f)
            bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = !canCancel
                progressTintList = android.content.res.ColorStateList.valueOf(c.color(R.attr.appAccent))
                indeterminateTintList = android.content.res.ColorStateList.valueOf(c.color(R.attr.appAccent))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, c.dp(6)).apply { topMargin = c.dp(14) }
            }
            c.body.addView(bar)
            label = c.line(" ", 12.5f, top = 8)
            if (canCancel) {
                val buttons = c.row(c.dp(14))
                c.pill(buttons, "Cancel") { cancel = true; label?.text = "Cancelling…" }.let { v -> v.post { v.requestFocus() } }
            }
        }
        card.dialog.setCancelable(false)
        Thread {
            val result = runCatching {
                work({ percent, text -> runOnUiThread { bar?.progress = percent; if (!cancel) label?.text = text } }, { cancel })
            }
            runOnUiThread {
                runCatching { card.dialog.dismiss() }
                if (isFinishing || isDestroyed) return@runOnUiThread
                val e = result.exceptionOrNull()
                val message = when {
                    e is Library.Cancelled -> "Cancelled. Nothing was changed."
                    e != null -> "It didn't work: ${e.message ?: e.javaClass.simpleName}"
                    else -> result.getOrNull() ?: ""
                }
                if (e != null && e !is Library.Cancelled) Diag.log("ratings", "library data: ${e.javaClass.simpleName}")
                if (section == Section.RATINGS) reshow("lib_backup")
                if (message.contains('\n')) {
                    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Library data").setMessage(message).setPositiveButton("OK", null).show()
                } else {
                    showToast(this, message, android.widget.Toast.LENGTH_LONG)
                }
            }
        }.start()
    }

    /**
     * Settings > Ratings > Whole playlist (work list Section 5, approved 2026-11-10): the fill's two
     * switches on the left; its progress and what it is doing now on the right, kept up to date while
     * the page is open. Other themes: the same rows one under the other.
     */
    private fun wholePlaylistCard() {
        heading("Whole playlist", R.drawable.ic_glass_list)
        val card = target
        var rightColumn: LinearLayout? = null
        if (glass && card != null) {
            val columns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            fun column(rightMargin: Int) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { this.rightMargin = rightMargin }
            }
            val leftColumn = column(dp(24))
            rightColumn = column(0)
            columns.addView(leftColumn)
            columns.addView(rightColumn)
            card.addView(columns)
            target = leftColumn
        }
        option("Fill whole playlist", listOf(true to "On", false to "Off"), { Ratings.fillWhole }, { Ratings.fillWhole = it; RatingsFill.rebuild() }, tag = "r_fill")
        option("Fill during playback", listOf(true to "On", false to "Off"), { Ratings.fillDuringPlayback }, { Ratings.fillDuringPlayback = it }, tag = "r_fill_play")
        // Approved 2026-11-11: the owner can stop the fill from asking their provider at all.
        option("Ask the provider for missing IDs", listOf(true to "On", false to "Off"), { Ratings.askProvider }, { Ratings.askProvider = it; RatingsFill.rebuild() }, tag = "r_fill_provider")
        // Approved 2026-11-12 (mock-up): the fill goes on under the screensaver unless this is off.
        option("Keep filling when the TV is idle", listOf(true to "On", false to "Off"), { Ratings.fillWhenIdle }, { Ratings.fillWhenIdle = it }, tag = "r_fill_idle")
        for ((rowTag, name, sub) in listOf(
            Triple("r_fill_provider", "Ask the provider for missing IDs", "One movie every 5 seconds. Off: TMDB only"),
            Triple("r_fill_idle", "Keep filling when the TV is idle", "Goes on under the screensaver and with the screen off. Stops when another app is opened"),
        )) (target ?: body).findViewWithTag<View>(rowTag)?.let { row ->
            val title = row.findViewById<TextView>(R.id.title)
            title.isSingleLine = false
            title.text = android.text.SpannableStringBuilder(name).append("\n").also { b ->
                val start = b.length
                b.append(sub)
                b.setSpan(android.text.style.RelativeSizeSpan(0.8f), start, b.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                b.setSpan(android.text.style.ForegroundColorSpan(themeColor(R.attr.appTextSecondary)), start, b.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // Two lines: the row grows with its text (the size rule: a minimum height, never a fixed one).
            row.layoutParams = row.layoutParams.apply { height = LinearLayout.LayoutParams.WRAP_CONTENT }
            row.minimumHeight = dp(if (glass) 48 else 58)
            row.setPadding(row.paddingLeft, dp(6), row.paddingRight, dp(6))
        }
        if (rightColumn != null) target = rightColumn
        linkRow("Progress", null, "–", "r_progress", onClick = null)
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(themeColor(R.attr.appAccent))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(themeColor(R.attr.appTextSecondary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)).apply {
                leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(8)
            }
        }
        (target ?: body).addView(bar)
        linkRow("Status", null, "–", "r_status", tint = DiagUi.GREEN, onClick = null)
        if (card != null) target = card
        (target ?: body).addView(TextView(this).apply {
            text = "Works while the app is open, under the screensaver and with the screen off. Scores, cast, director, genre, year and length are saved on this TV."
            textSize = 12.5f
            setTextColor(themeColor(R.attr.appTextSecondary))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        })

        val host = body
        fun row(tag: String) = host.findViewWithTag<View>(tag)
        fun setRow(tag: String, name: String, sub: String?, value: String, tint: Int?) {
            val r = row(tag) ?: return
            val title = r.findViewById<TextView>(R.id.title)
            val shown = if (sub == null) name else "$name\n$sub"
            if (title.text.toString() != shown) {
                title.text = if (sub == null) name else android.text.SpannableStringBuilder(name).append("\n").also { b ->
                    val start = b.length
                    b.append(sub)
                    b.setSpan(android.text.style.RelativeSizeSpan(0.8f), start, b.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    b.setSpan(android.text.style.ForegroundColorSpan(themeColor(R.attr.appTextSecondary)), start, b.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            val v = r.findViewById<TextView>(R.id.subtitle)
            if (v.text.toString() != value) v.text = value
            v.setTextColor(tint ?: themeColor(R.attr.appAccent))
        }
        fun about(seconds: Long): String = when {
            seconds < 90 * 60 -> "about ${(seconds / 60).coerceAtLeast(1)} minutes left"
            seconds < 48 * 3600 -> "about ${(seconds + 1800) / 3600} hours left"
            else -> "about ${(seconds + 43_200) / 86_400} days left"
        }
        val grey = themeColor(R.attr.appTextSecondary)
        var ticks = 0
        val update = object : Runnable {
            override fun run() {
                if (section != Section.RATINGS || row("r_status") == null) return
                val p = RatingsFill.progress()
                setRow("r_progress", "Progress", null, if (p.total == 0) "–" else "${"%,d".format(p.ready)} of ${"%,d".format(p.total)}", null)
                bar.progress = if (p.total == 0) 0 else (p.ready * 1000L / p.total).toInt()
                // One line: the dot's colour, the state, and the reason beside it.
                val (label, tint) = when (p.state) {
                    RatingsFill.State.OFF -> (if (Ratings.fillWhole) "●  Off · ratings are off" else "●  Off · switched off") to grey
                    RatingsFill.State.STARTING -> "●  Starting · playlist loading" to grey
                    RatingsFill.State.FILLING -> "●  Filling · ${about(p.secondsLeft)}" to DiagUi.GREEN
                    RatingsFill.State.REFRESHING -> "●  Refreshing scores · ${about(p.secondsLeft)}" to DiagUi.GREEN
                    RatingsFill.State.IDS_ONLY -> "●  Daily limit reached · still finding IDs" to DiagUi.YELLOW
                    RatingsFill.State.DETAILS -> "●  Adding cast and genres · ${"%,d".format(p.detailsLeft)} left" to DiagUi.GREEN
                    RatingsFill.State.BROWSING -> "●  Paused · remote in use" to grey
                    RatingsFill.State.PLAYBACK -> "●  Paused · something is playing" to grey
                    RatingsFill.State.TOMORROW -> "●  Waiting · daily limit reached" to DiagUi.YELLOW
                    RatingsFill.State.OFFLINE -> "●  Waiting · no internet" to DiagUi.YELLOW
                    RatingsFill.State.DONE -> "●  Done · ${"%,d".format(p.ready)} titles filled" to DiagUi.GREEN
                    RatingsFill.State.BAD_KEY -> "●  Stopped · key not accepted" to DiagUi.RED
                    RatingsFill.State.STOPPED -> "●  Stopped · could not connect" to DiagUi.RED
                }
                val sub: String? = null
                setRow("r_status", "Status", sub, label, tint)
                // Today's lookups and the matching counts move while the fill runs: kept up to date too.
                if (ticks++ % 5 == 0) {
                    Ratings.usage()?.let { u ->
                        val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(u.resetsAt))
                        setRow("r_today", "Today", "Resets at $at · scores kept 7 to 30 days", "${"%,d".format(u.used)} of ${"%,d".format(u.limit)}",
                            if (u.used * 10 >= u.limit * 8) DiagUi.YELLOW else null)
                    }
                    val m = Ratings.matching()
                    if (m.byId + m.byTmdb + m.byName + m.notFound > 0) setRow("r_tried", "Matching so far", Ratings.matchingText(m), "", null)
                }
                host.postDelayed(this, 1000)
            }
        }
        host.removeCallbacks(fillTicker)
        fillTicker = update
        host.post(update)
    }

    private var fillTicker: Runnable? = null

    /** Get started's round buttons: icon then words, the card's full width. */
    /** Settings → Use my phone: a code for a new phone, and the phones that joined, each with Remove. */
    private fun phonePanelCard() {
        DiagUi.card(this, 760) { c ->
            fun build() {
                c.clear()
                c.title("Use Phone", R.drawable.ic_glass_phone, c.color(R.attr.appAccent))
                c.line("Search, play and use your phone as a remote. Same Wi-Fi as this TV; nothing goes through the internet.", 13f)
                val url = PhonePanel.joinAddress()
                if (url != null) {
                    val row = c.row(c.dp(12))
                    row.addView(android.widget.ImageView(this).apply {
                        setImageBitmap(PhonePage.qr(url, c.dp(170)))
                        layoutParams = android.widget.LinearLayout.LayoutParams(c.dp(170), c.dp(170)).apply { rightMargin = c.dp(18) }
                    })
                    row.addView(TextView(this).apply {
                        text = listOf("Scan with the phone you want to add.", "It stays connected for 30 days.", "This code works for 5 minutes.").joinToString("\n")
                        textSize = 15f
                        setTextColor(c.color(R.attr.appText))
                    })
                } else c.line("The phone panel needs the TV on your home Wi-Fi.", 15f, top = 12)
                c.line("Your phones", 16f, top = 16)
                val phones = PhonePanel.phones()
                if (phones.isEmpty()) c.line("No phones yet.", 14f, top = 4)
                val fmt = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.US)
                for (p in phones) {
                    val r = c.row(c.dp(8))
                    r.addView(TextView(this).apply {
                        text = "${p.name} · last used ${fmt.format(java.util.Date(p.lastSeen))}"
                        textSize = 15f
                        setTextColor(c.color(R.attr.appText))
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    c.pill(r, "Remove", weight = 0f) { PhonePanel.remove(p.id); build() }
                }
                val buttons = c.row(c.dp(16))
                c.pill(buttons, "Done") { c.dialog.dismiss() }.also { it.post { it.requestFocus() } }
            }
            build()
        }
    }

    private fun startAction(label: String, icon: Int, tag: String, danger: Boolean = false, onClick: () -> Unit) = pill(label).also { b ->
        b.tag = tag
        b.setOnClickListener { onClick() }
        b.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_settings_action)
        b.minHeight = dp(46)
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
        (target ?: body).addView(b)
    }

    /**
     * A row like the option rows: a name (and a small line under it) on the left. With [onClick] it is an
     * action: OK does it, and there is nothing on the right (no "▾": there is nothing to choose). Without,
     * it shows a [value] on the right (the key's state, today's usage) and can't be selected.
     */
    private fun linkRow(name: String, sub: String?, value: String?, tag: String, tint: Int? = null, onClick: (() -> Unit)?) {
        val host = target ?: body
        val row = LayoutInflater.from(this).inflate(R.layout.item_settings_option, host, false)
        row.tag = tag
        val title = row.findViewById<TextView>(R.id.title)
        val valueView = row.findViewById<TextView>(R.id.subtitle)
        title.isSingleLine = false
        title.text = if (sub == null) name else android.text.SpannableStringBuilder(name).append("\n").also { b ->
            val start = b.length
            b.append(sub)
            b.setSpan(android.text.style.RelativeSizeSpan(0.8f), start, b.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(android.text.style.ForegroundColorSpan(themeColor(R.attr.appTextSecondary)), start, b.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (tint != null && onClick != null) title.setTextColor(tint)
        valueView.text = value
        valueView.visibility = if (value == null) View.GONE else View.VISIBLE
        if (glass) {
            row.layoutParams = (row.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                height = LinearLayout.LayoutParams.WRAP_CONTENT
                bottomMargin = 0
            }
            row.minimumHeight = dp(48)
            row.setPadding(dp(10), dp(6), dp(10), dp(6))
            addDivider(host)
            title.textSize = 14f
            valueView.textSize = 13f
        } else {
            row.layoutParams = row.layoutParams.apply { height = LinearLayout.LayoutParams.WRAP_CONTENT }
            row.minimumHeight = dp(58)
            row.setPadding(row.paddingLeft, dp(8), row.paddingRight, dp(8))
        }
        if (tint != null && onClick == null) valueView.setTextColor(tint)
        if (onClick == null) {
            row.isFocusable = false
            row.isClickable = false
        } else {
            row.setOnClickListener { onClick() }
        }
        host.addView(row)
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
        option("Theme", Appearance.THEMES, { prefs.themeIndex }, { prefs.themeIndex = it; pendingFocus = "theme"; recreate() }, "theme")

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
        // Interface size and Text size: kept in the app's data, not offered to the user (owner, 2026-11-13).

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
        // Interface size and Text size: kept in the app's data, not offered to the user (owner, 2026-11-13).
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
