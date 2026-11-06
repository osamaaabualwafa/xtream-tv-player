package com.osama.xtreamtv

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings > Home layout > Arrange rows (1.1A, concepts C + A): a small Home inside a TV frame, drawn
 * in the chosen Home layout, each row with its title and a strip of plain cards. Up and
 * Down choose a row; OK picks it up, Up and Down move it, OK drops it; Right (or Menu) shows or hides
 * it. The layout's own parts (tiles, Hero's panel, the side menu, the tab bar) stay put and can't be
 * chosen. Saved at once.
 */
class HomeRowsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Look.wrap(newBase))

    private lateinit var prefs: Prefs
    private lateinit var column: LinearLayout
    private lateinit var subtitle: TextView
    private var order = mutableListOf<String>()
    private var hidden = mutableSetOf<String>()
    private var moving: String? = null
    private var style = Appearance.HOME_TILES

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Appearance.apply(this)
        style = prefs.homeStyle
        order = prefs.homeRowOrder.toMutableList()
        hidden = prefs.homeRowHidden.toMutableSet()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(26), dp(56), dp(18))
            // The theme's own background, like Settings.
            background = obtainStyledAttributes(intArrayOf(R.attr.appBackground)).let { a -> a.getDrawable(0).also { a.recycle() } }
        }
        root.addView(TextView(this).apply {
            text = "Arrange Home"
            textSize = 24f
            setTextColor(themeColor(R.attr.appText))
        })
        subtitle = TextView(this).apply {
            textSize = 14f
            setTextColor(themeColor(R.attr.appTextSecondary))
            setPadding(0, dp(2), 0, dp(12))
        }
        root.addView(subtitle)

        // The TV frame with the small Home inside.
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x2E000000)
                setStroke(dp(2), 0x40FFFFFF)
            }
        }
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (style == Appearance.HOME_SIDE_MENU) {
            // The side menu, as on Home: one box per item (fixed).
            val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (item in listOf("Live TV", "Movies", "Series", "Search", "More", "Settings")) menu.addView(part(item),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
            frame.addView(menu, LinearLayout.LayoutParams(dp(118), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) })
        }
        // Scrolls when the rows don't all fit (the highlight brings the next one into view).
        val scroll = FocusScrollView(this).apply { isVerticalScrollBarEnabled = false }
        frame.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The keys, in one line under the frame.
        val keys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val hints = if (style == Appearance.HOME_DOCK) listOf("Back" to "done") else
            listOf("▲ ▼" to "choose", "OK" to "pick up, drop", "▶" to "show or hide", "Back" to "done")
        for ((key, what) in hints) {
            keys.addView(TextView(this).apply {
                text = key
                textSize = 12f
                setTextColor(themeColor(R.attr.appText))
                setPadding(dp(7), dp(1), dp(7), dp(1))
                background = GradientDrawable().apply {
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), 0x66FFFFFF)
                }
            })
            keys.addView(TextView(this).apply {
                text = what
                textSize = 13f
                setTextColor(themeColor(R.attr.appTextSecondary))
                setPadding(dp(6), 0, dp(24), 0)
            })
        }
        root.addView(keys)
        setContentView(root)
        build(order.firstOrNull())
    }

    /** All the blocks again, the highlight on [focusKey]. */
    private fun build(focusKey: String?) {
        column.removeAllViews()
        val layoutName = Appearance.HOME_NAMES.getOrElse(style) { "Home" }
        if (style == Appearance.HOME_DOCK) {
            column.addView(fixed("The Dock layout shows no rows on Home: its dock opens each section.\nChoose another Home layout to arrange rows."))
            subtitle.text = "$layoutName layout"
            return
        }
        // The layout's own parts, where they are on Home.
        when (style) {
            Appearance.HOME_HERO -> {
                column.addView(fixed("Resume: the last thing watched", tall = true))
                column.addView(tiles())
            }
            Appearance.HOME_TABS -> column.addView(tabs())
            Appearance.HOME_SIDE_MENU -> Unit
            else -> column.addView(tiles())
        }
        for (key in order) column.addView(block(key))
        subtitle.text = "$layoutName layout · " + (moving?.let { "moving ${Prefs.HOME_ROW_NAMES[it]}: ▲ ▼ to move, OK to drop" } ?: "${order.count { it !in hidden }} of ${order.size} rows shown")
        column.post { column.findViewWithTag<View>(focusKey)?.requestFocus() }
    }

    /** A part of the layout that stays put (not selectable). */
    private fun fixed(text: String, tall: Boolean = false, vertical: Boolean = false): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(themeColor(R.attr.appTextSecondary))
        gravity = if (vertical) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(if (tall) 22 else 9), dp(12), dp(if (tall) 22 else 9))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0x14FFFFFF)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    /** One fixed piece of the layout (a tile, a tab, a menu item): a box with an edge, not selectable. */
    private fun part(name: String, tall: Boolean = false, round: Boolean = false, big: Boolean = false): TextView = TextView(this).apply {
        text = name
        textSize = if (big) 16f else 12f
        maxLines = 1
        gravity = if (round) Gravity.CENTER else Gravity.CENTER_VERTICAL
        setTextColor(themeColor(if (big) R.attr.appText else R.attr.appTextSecondary))
        setPadding(dp(14), dp(if (big) 20 else if (tall) 12 else 6), dp(14), dp(if (big) 20 else if (tall) 12 else 6))
        background = GradientDrawable().apply {
            cornerRadius = dp(if (round) 999 else 10).toFloat()
            setColor(0x14FFFFFF)
            setStroke(dp(1), 0x33FFFFFF)
        }
    }

    /** The four tiles, as they are on Home (fixed). */
    private fun tiles(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((i, name) in listOf("Live TV", "Movies", "Series", "Search").withIndex()) {
            addView(part(name, big = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(10) })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    /** The tab bar, as on Home (fixed): centred, the tabs inside one bar. */
    private fun tabs(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(5), dp(5), dp(5), dp(5))
        background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(0x14FFFFFF)
            setStroke(dp(1), 0x33FFFFFF)
        }
        for ((i, name) in listOf("Live TV", "Movies", "Series", "Search").withIndex()) {
            addView(part(name, tall = true, round = true), LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(6) })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        }
    }

    /** One row: its title, then a strip of plain cards (what a row looks like on Home). */
    private fun block(key: String): View {
        val accent = themeColor(R.attr.appAccent)
        val name = Prefs.HOME_ROW_NAMES[key] ?: key
        val isHidden = key in hidden
        val isMoving = key == moving
        return LinearLayout(this).apply {
            tag = key
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            isFocusable = true
            alpha = if (isHidden) 0.38f else 1f
            background = if (isMoving) GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor((accent and 0x00FFFFFF) or 0x59000000)
                setStroke(dp(2), accent)
            } else androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_arrange_row)
            addView(TextView(context).apply {
                text = when {
                    isMoving -> "⇅  $name"
                    isHidden -> "$name  ·  hidden"
                    else -> name
                }
                textSize = 14f
                maxLines = 1
                isDuplicateParentStateEnabled = true
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.on_focus_text))
            }, LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT))
            val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 8) {
                strip.addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(4).toFloat())
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(0x24FFFFFF)
                    }
                }, LinearLayout.LayoutParams(dp(20), dp(28)).apply { rightMargin = dp(5) })
            }
            addView(strip)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        }
    }

    private fun save() {
        prefs.homeRowOrder = order
        prefs.homeRowHidden = hidden.toSet()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = currentFocus?.tag as? String
        if (event.action != KeyEvent.ACTION_DOWN || key == null) return super.dispatchKeyEvent(event)
        val at = order.indexOf(key)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount > 0) return true
                moving = if (moving == null) key else null
                if (moving == null) save()
                build(key)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                if (moving == key) {
                    val to = at + step
                    if (to in order.indices) {
                        order.removeAt(at)
                        order.add(to, key)
                        save()
                        build(key)
                    }
                    return true
                }
                // The first and last rows keep the highlight (the layout's parts can't be chosen).
                if ((step < 0 && at == 0) || (step > 0 && at == order.lastIndex)) return true
                column.findViewWithTag<View>(order[at + step])?.requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU -> {
                if (moving != null || event.repeatCount > 0) return true
                if (!hidden.remove(key)) hidden.add(key)
                save()
                build(key)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> return true
            KeyEvent.KEYCODE_BACK -> {
                if (moving != null) {
                    moving = null
                    save()
                    build(key)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
