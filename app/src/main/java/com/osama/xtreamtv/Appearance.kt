package com.osama.xtreamtv

import android.app.Activity
import android.content.res.ColorStateList
import android.widget.TextView

/** Settings > appearance: layouts, theme colour and playlist avatars. */
object Appearance {

    // Home layouts (Settings > Home layout).
    const val HOME_HERO = 0
    const val HOME_SIDE_MENU = 1
    const val HOME_TILES = 2
    const val HOME_TABS = 3
    val HOME_NAMES = listOf("Hero", "Side menu", "Big tiles", "Top tabs")

    // Movies and series layouts (Settings > Movies and series).
    const val BROWSE_SIDEBAR = 0
    const val BROWSE_FOLDING = 1
    const val BROWSE_CHIPS = 2
    const val BROWSE_PREVIEW = 3
    val BROWSE_NAMES = listOf("Sidebar", "Folding", "Chips", "Preview")

    /** Theme colours: name, colour, and the matching style in themes_accent.xml. */
    val ACCENT_NAMES = listOf("Blue", "Purple", "Teal", "Orange", "Pink", "Green", "Red", "Gold")
    val ACCENT_COLORS = intArrayOf(
        0xFF3D8BFD.toInt(), 0xFF8A5CF6.toInt(), 0xFF14B8A6.toInt(), 0xFFF97316.toInt(),
        0xFFEC4899.toInt(), 0xFF22C55E.toInt(), 0xFFE11D48.toInt(), 0xFFEAB308.toInt(),
    )
    private val ACCENT_STYLES = intArrayOf(
        R.style.Accent_Blue, R.style.Accent_Purple, R.style.Accent_Teal, R.style.Accent_Orange,
        R.style.Accent_Pink, R.style.Accent_Green, R.style.Accent_Red, R.style.Accent_Gold,
    )

    /** Avatar picker groups (A2). */
    val AVATAR_GROUPS = listOf(
        "TV and movies" to listOf("📺", "🎬", "🍿", "🎞️", "🎭", "📡", "🎥", "⭐"),
        "Sports" to listOf("⚽", "🏀", "🎾", "🏎️", "🥊", "🏆", "🏈", "🏐"),
        "Family" to listOf("🏠", "👨‍👩‍👧", "🧒", "👵", "👴", "💑", "🧸", "🎈"),
        "Animals" to listOf("🦁", "🐯", "🐼", "🦊", "🐬", "🦅", "🐎", "🐱"),
        "Fun" to listOf("🎮", "🎵", "🌙", "🔥", "🚀", "💎", "🌍", "☕"),
    )

    /** Palettes offered with the theme colours in Settings > Look (0 = none: accent + background). */
    val PALETTE_NAMES = listOf("Default", "Coastal", "Coconut")
    private val PALETTE_STYLES = intArrayOf(0, R.style.Palette_Coastal, R.style.Palette_Coconut)
    /** Colour of each palette's dot in Settings > Look (its accent). */
    val PALETTE_DOTS = intArrayOf(0, 0xFF8FB0C4.toInt(), 0xFFC9A88A.toInt())

    /** Settings > Look > Theme: whole looks (colours, shapes, highlight, font, background art). */
    val THEME_NAMES = listOf("Default", "Cinema", "Coastal", "Coconut")
    private val THEME_STYLES = arrayOf(
        intArrayOf(),
        intArrayOf(R.style.Palette_Cinema, R.style.Look_Cinema),
        intArrayOf(R.style.Palette_Coastal, R.style.Look_Coastal),
        intArrayOf(R.style.Palette_Coconut, R.style.Look_Coconut),
    )
    const val THEME_CINEMA = 1

    /** Call in onCreate before setContentView: applies the chosen theme colour and palette. */
    fun apply(activity: Activity) {
        val prefs = Prefs(activity)
        val index = prefs.accentIndex.coerceIn(0, ACCENT_STYLES.size - 1)
        activity.theme.applyStyle(ACCENT_STYLES[index], true)
        val theme = prefs.themeIndex.coerceIn(0, THEME_STYLES.size - 1)
        if (theme != 0) {
            // A theme brings everything: its colours win over the colour dots and background.
            for (style in THEME_STYLES[theme]) activity.theme.applyStyle(style, true)
        } else {
            // Last, so a palette wins over the colour and background (which stay saved for Default).
            val palette = PALETTE_STYLES.getOrElse(prefs.paletteIndex) { 0 }
            if (palette != 0) activity.theme.applyStyle(palette, true)
        }
        Look.applyBackground(activity)
    }

    /** A playlist's avatar circle: its emoji (or first letter) on its colour. */
    fun bindAvatar(view: TextView, prefs: Prefs, playlistId: String, name: String, letterSizeSp: Float, emojiSizeSp: Float) {
        val emoji = prefs.avatar(playlistId)
        if (emoji != null) {
            view.text = emoji
            view.textSize = emojiSizeSp
        } else {
            view.text = name.trim().firstOrNull()?.uppercase() ?: "?"
            view.textSize = letterSizeSp
        }
        val colorIndex = prefs.avatarColor(playlistId)
        val color = if (colorIndex in ACCENT_COLORS.indices) ACCENT_COLORS[colorIndex] else avatarColor(playlistId)
        view.backgroundTintList = ColorStateList.valueOf(color)
    }
}
