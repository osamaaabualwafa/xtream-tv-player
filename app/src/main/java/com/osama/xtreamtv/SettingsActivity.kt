package com.osama.xtreamtv

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope

/**
 * Settings (S2): Home layout, movies and series layout (picture cards), theme colour,
 * playlist avatars and About. Moving through the menu on the left shows each section.
 */
class SettingsActivity : AppCompatActivity() {

    private enum class Section { HOME, BROWSE, PLAYER, COLOR, AVATARS, ABOUT }

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
                title.text = "Theme colour"
                hint.text = "Used for highlights, buttons and progress bars"
                colors()
            }
            Section.AVATARS -> {
                title.text = "Playlist avatars"
                hint.text = "Without an avatar, a playlist shows the first letter of its name"
                avatars()
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
        option("Hide controls after", listOf(3, 5, 8, 15).map { it to "$it seconds" }, { prefs.controlsHideSeconds }, { prefs.controlsHideSeconds = it })
        heading("Picture and sound")
        option("Picture size", listOf(0 to "Fit", 1 to "Zoom (fill screen)", 2 to "Stretch"), { prefs.pictureSize }, { prefs.pictureSize = it })
        option("Audio language", languages, { prefs.audioLanguage }, { prefs.audioLanguage = it })
        option("Subtitles", subtitleLanguages, { prefs.subtitleLanguage ?: PlayerActivity.SUBTITLES_OFF }, { prefs.subtitleLanguage = it })
        option("Subtitle size", listOf(0 to "Small", 1 to "Medium", 2 to "Large", 3 to "Extra large"), { prefs.subtitleSize }, { prefs.subtitleSize = it })
        option("Subtitle background", listOf(false to "Off", true to "Dark box"), { prefs.subtitleBackground }, { prefs.subtitleBackground = it })
        heading("Live TV")
        option("Channel preview in the list", listOf(true to "On", false to "Off"), { prefs.channelPreview }, { prefs.channelPreview = it })
        option("Stream type", listOf(0 to "Automatic", 1 to "HLS (m3u8)", 2 to "MPEG-TS"), { prefs.streamType }, { prefs.streamType = it })
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
