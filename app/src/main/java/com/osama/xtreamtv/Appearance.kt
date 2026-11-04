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
    const val HOME_DOCK = 4
    val HOME_NAMES = listOf("Hero", "Side menu", "Big tiles", "Top tabs", "Dock")

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
    val THEMES = listOf(0 to "Default", 1 to "Cinema", 4 to "Glass")
    const val THEME_CINEMA = 1
    const val THEME_GLASS = 4

    /** Glass theme: Settings > Look > Background. */
    val SCENE_NAMES = listOf("Aurora", "Dusk", "Ember", "Midnight", "Coastal", "Coconut", "Ocean", "Sunset", "Daylight")
    const val SCENE_MIDNIGHT = 3
    const val SCENE_DAYLIGHT = 8
    private val SCENE_STYLES = intArrayOf(
        R.style.Scene_Aurora, R.style.Scene_Dusk, R.style.Scene_Ember, R.style.Scene_Midnight, R.style.Scene_Coastal,
        R.style.Scene_Coconut, R.style.Scene_Ocean, R.style.Scene_Sunset, R.style.Scene_Daylight,
    )
    /** Picker swatches: highlight, then the two colours of the gradient. */
    val SCENE_SWATCHES = arrayOf(
        intArrayOf(0xFF71A78A.toInt(), 0xFF273F45.toInt(), 0xFF274268.toInt()),
        intArrayOf(0xFFB481B8.toInt(), 0xFF50305F.toInt(), 0xFF2C4878.toInt()),
        intArrayOf(0xFFC39167.toInt(), 0xFF713D3A.toInt(), 0xFF4E3A61.toInt()),
        intArrayOf(0xFF45566E.toInt(), 0xFF0B1120.toInt(), 0xFF0B1120.toInt()),
        intArrayOf(0xFF718E9E.toInt(), 0xFF243641.toInt(), 0xFF4A3A2E.toInt()),
        intArrayOf(0xFFB59376.toInt(), 0xFF3D291D.toInt(), 0xFF4A4A33.toInt()),
        intArrayOf(0xFF3F8F9A.toInt(), 0xFF0F2027.toInt(), 0xFF2C5364.toInt()),
        intArrayOf(0xFFB0606E.toInt(), 0xFF3A1C4A.toInt(), 0xFF5A2A3A.toInt()),
        intArrayOf(0xFFCFE3D6.toInt(), 0xFFF1EFE8.toInt(), 0xFFD6E2F3.toInt()),
    )
    val GLASS_STYLE_NAMES = listOf("Clear", "Frosted", "Smoked")
    private val GLASS_STYLES = intArrayOf(R.style.Glass_Clear, R.style.Glass_Frosted, R.style.Glass_Smoked)
    val FOCUS_NAMES = listOf("Accent ring", "White border", "Solid colour", "Lift and glow")
    private val FOCUS_STYLES = intArrayOf(R.style.Focus_Ring, R.style.Focus_White, R.style.Focus_Solid, R.style.Focus_Lift)
    private val FOCUS_STYLES_DAYLIGHT = intArrayOf(R.style.Focus_DaylightRing, R.style.Focus_DaylightWhite, R.style.Focus_DaylightSolid, R.style.Focus_DaylightLift)

    /** Glass: the scene, glass style and focus style on top of its palette and look. */
    private fun applyGlass(activity: Activity, prefs: Prefs) {
        var scene = prefs.glassScene.coerceIn(0, SCENE_STYLES.size - 1)
        // Video stays on dark glass whatever the scene: the player has no wallpaper to be light on.
        if (scene == SCENE_DAYLIGHT && activity is PlayerActivity) scene = SCENE_MIDNIGHT
        val daylight = scene == SCENE_DAYLIGHT
        val focus = prefs.focusStyle.coerceIn(0, FOCUS_STYLES.size - 1)
        activity.theme.applyStyle(SCENE_STYLES[scene], true)
        activity.theme.applyStyle(
            when {
                daylight -> R.style.Glass_Daylight
                Look.isLightDevice(activity) -> R.style.Glass_Perf // "Performance mode": no see-through panels on weak TVs.
                else -> GLASS_STYLES[prefs.glassStyle.coerceIn(0, GLASS_STYLES.size - 1)]
            },
            true,
        )
        activity.theme.applyStyle(if (daylight) FOCUS_STYLES_DAYLIGHT[focus] else FOCUS_STYLES[focus], true)
    }

    /** Call in onCreate before setContentView: applies the chosen theme colour and palette. */
    fun apply(activity: Activity) {
        val prefs = Prefs(activity)
        val index = prefs.accentIndex.coerceIn(0, ACCENT_STYLES.size - 1)
        activity.theme.applyStyle(ACCENT_STYLES[index], true)
        prefs.moveRemovedThemes()
        when (prefs.themeIndex) {
            // A theme brings everything: its colours win over the colour dots and background.
            THEME_CINEMA -> {
                activity.theme.applyStyle(R.style.Palette_Cinema, true)
                activity.theme.applyStyle(R.style.Look_Cinema, true)
            }
            THEME_GLASS -> {
                activity.theme.applyStyle(R.style.Palette_Glass, true)
                activity.theme.applyStyle(R.style.Look_Glass, true)
                applyGlass(activity, prefs)
            }
            else -> {
                // Last, so a palette wins over the colour and background (which stay saved for Default).
                val palette = PALETTE_STYLES.getOrElse(prefs.paletteIndex) { 0 }
                if (palette != 0) activity.theme.applyStyle(palette, true)
            }
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
