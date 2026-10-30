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

    /** Call in onCreate before setContentView: applies the chosen theme colour. */
    fun apply(activity: Activity) {
        val index = Prefs(activity).accentIndex.coerceIn(0, ACCENT_STYLES.size - 1)
        activity.theme.applyStyle(ACCENT_STYLES[index], true)
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
