package com.osama.xtreamtv

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The filter's two doors on the Movies and Series screen (approved 2026-11-12, pictures F1-F3):
 * - the quick line above the posters, one chip per section; OK on a chip lists its choices with their
 *   counts and a choice applies at once;
 * - the full panel (the Filter button, or Info held on the posters) with every section; nothing
 *   applies until "Show N", and Back closes it without changing anything.
 * Both read and write the same [TitleFilter.State], kept by the screen.
 */
class FilterUi(
    private val activity: Activity,
    private val type: ContentType,
    /** The current choices. */
    private val state: () -> TitleFilter.State,
    /** Counts for a state (off the screen's thread), delivered on the screen's thread. */
    private val count: (TitleFilter.State, (TitleFilter.Result) -> Unit) -> Unit,
    /** New choices from the line or the panel. */
    private val apply: (TitleFilter.State) -> Unit,
) {
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private val noun = if (type == ContentType.MOVIE) "movies" else "series"

    // ---- The line ----

    val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val lineScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        clipToPadding = false
        setPadding(dp(4), dp(6), dp(4), dp(4))
        addView(line, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val chips = LinkedHashMap<TitleFilter.Section, TextView>()

    init {
        for (s in TitleFilter.sections(type)) {
            val chip = choiceView(s.label).apply {
                tag = "filter_${s.name}"
                setOnClickListener { sectionList(s, this) }
            }
            chips[s] = chip
            line.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
        }
        refreshLine()
    }

    /** The chips' words and highlight follow the choices. */
    fun refreshLine() {
        val st = state()
        for ((s, chip) in chips) {
            val v = st.get(s)
            chip.text = TitleFilter.lineLabel(s, v)
            chip.isActivated = v.isNotEmpty()
        }
    }

    val chipViews: List<View> get() = chips.values.toList()

    /** The chip nearest to a horizontal position on screen (Up from the posters lands above them). */
    fun chipNear(screenX: Int): View? = chips.values.minByOrNull { c ->
        val at = IntArray(2); c.getLocationOnScreen(at)
        kotlin.math.abs(at[0] + c.width / 2 - screenX)
    }

    private fun choiceView(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 15f
        isSingleLine = true
        setTextColor(ContextCompat.getColorStateList(activity, R.color.on_focus_text))
        setBackgroundResource(R.drawable.bg_glass_choice)
        gravity = Gravity.CENTER
        minHeight = dp(38)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        isFocusable = true
        isClickable = true
    }

    private fun glassBox(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(activity.themeColor(R.attr.appOverlay) or 0xF0000000.toInt())
        val accent = activity.themeColor(R.attr.appAccent)
        setStroke(dp(1).coerceAtLeast(1), (accent and 0x00FFFFFF) or 0xB3000000.toInt())
    }

    private fun anyLabel(s: TitleFilter.Section) = if (s == TitleFilter.Section.WATCHED) "All" else "Any"
    private fun fmt(n: Int) = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(n)

    /** OK on a chip: that section's choices under it, each with its count; a choice applies at once. */
    private fun sectionList(s: TitleFilter.Section, chip: View) {
        count(state()) { result ->
            val choices = result.choices[s] ?: return@count
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = glassBox()
            }
            val popup = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
            val current = state().get(s)
            var focusRow: View? = null
            for ((value, n) in choices.values) {
                val chosen = value == current
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_menu_row)
                    minimumWidth = dp(280)
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    isActivated = chosen
                    // A choice that would give nothing is shown dimmed and skipped (unless it is the current one).
                    isFocusable = n > 0 || chosen
                    isClickable = isFocusable
                    alpha = if (isFocusable) 1f else 0.45f
                    addView(TextView(activity).apply {
                        text = value.ifEmpty { anyLabel(s) }
                        textSize = 16f
                        setTextColor(ContextCompat.getColorStateList(activity, R.color.on_focus_text))
                        isDuplicateParentStateEnabled = true
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(activity).apply {
                        text = fmt(n)
                        textSize = 15f
                        setTextColor(ContextCompat.getColorStateList(activity, R.color.on_focus_text))
                        isDuplicateParentStateEnabled = true
                        setPadding(dp(18), 0, 0, 0)
                    })
                    setOnClickListener {
                        popup.dismiss()
                        if (!chosen) apply(state().with(s, value))
                    }
                }
                if (chosen) focusRow = row
                box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            }
            // The list opens below the chip, or above it when there is more room there; a long list
            // (genres) scrolls inside, never past the screen's edge.
            val content = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                isFillViewport = true
                background = box.background
                box.background = null
                addView(box)
            }
            popup.contentView = content
            val at = IntArray(2); chip.getLocationOnScreen(at)
            val screenH = activity.resources.displayMetrics.heightPixels
            val below = screenH - (at[1] + chip.height) - dp(24)
            val above = at[1] - dp(24)
            content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val needed = content.measuredHeight
            val room = maxOf(below, above)
            popup.height = minOf(needed, room)
            popup.setBackgroundDrawable(ColorDrawable(0))
            popup.elevation = dp(12).toFloat()
            popup.isOutsideTouchable = true
            popup.setOnDismissListener { chip.requestFocus() }
            if (needed <= below || below >= above) popup.showAsDropDown(chip, 0, dp(6), Gravity.START)
            else popup.showAsDropDown(chip, 0, -(chip.height + popup.height + dp(6)), Gravity.START)
            box.post {
                val target = focusRow ?: (0 until box.childCount).map { box.getChildAt(it) }.firstOrNull { it.isFocusable }
                target?.requestFocus()
                target?.let { t -> content.post { content.smoothScrollTo(0, (t.top - dp(40)).coerceAtLeast(0)) } }
            }
        }
    }

    // ---- The full panel ----

    private var panel: PopupWindow? = null

    /** The Filter button, or Info held on the posters. [onClosed] puts the highlight back. */
    fun openPanel(onClosed: () -> Unit) {
        if (panel?.isShowing == true) return
        var pending = state()
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBox().apply { cornerRadius = dp(26).toFloat() }
            setPadding(dp(30), dp(22), dp(30), dp(20))
        }
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(activity).apply {
            text = "Filter $noun"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(activity.themeColor(R.attr.appText))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val known = TextView(activity).apply { textSize = 14f; setTextColor(activity.themeColor(R.attr.appTextSecondary)) }
        head.addView(known)
        outer.addView(head)
        val sectionsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(sectionsBox) }
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val show = choiceView("Show").apply { isActivated = true; minWidth = dp(260); textSize = 17f }
        val clear = choiceView("Clear all").apply { textSize = 17f }
        buttons.addView(show, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(14) })
        buttons.addView(clear)
        // The hint has a line of its own above the buttons, so it never runs out of room.
        outer.addView(TextView(activity).apply {
            text = "Back closes without changing"
            textSize = 13f
            setTextColor(activity.themeColor(R.attr.appTextSecondary))
            setPadding(0, dp(10), 0, 0)
        })
        outer.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val w = (activity.resources.displayMetrics.widthPixels * 0.53f).toInt()
        val h = activity.resources.displayMetrics.heightPixels - dp(48)
        val popup = PopupWindow(outer, w, h, true)
        panel = popup

        fun draw(focusTag: String?) {
            count(pending) { r ->
                known.text = "${fmt(r.shownTotal)} $noun · ${fmt(r.withFacts)} with scores and details"
                sectionsBox.removeAllViews()
                for (s in TitleFilter.sections(type)) {
                    val choices = r.choices[s] ?: continue
                    sectionsBox.addView(TextView(activity).apply {
                        text = TitleFilter.panelLabel(s)
                        textSize = 14f
                        setTextColor(activity.themeColor(R.attr.appTextSecondary))
                        setPadding(0, dp(12), 0, dp(6))
                    })
                    val flow = FlowRow(activity)
                    val current = pending.get(s)
                    // Genres: the most common first; the rest stay reachable in the quick line's list.
                    val list = if (s == TitleFilter.Section.GENRE) choices.values.take(9).let { top ->
                        if (current.isNotEmpty() && top.none { it.first == current }) top + choices.values.filter { it.first == current } else top
                    } else choices.values
                    for ((value, n) in list) {
                        val chosen = value == current
                        val b = choiceView("${value.ifEmpty { anyLabel(s) }}   ${fmt(n)}").apply {
                            tag = "p_${s.name}_$value"
                            isActivated = chosen
                            isFocusable = n > 0 || chosen
                            alpha = if (isFocusable) 1f else 0.45f
                            setOnClickListener {
                                if (!chosen) { pending = pending.with(s, value); draw(tag as String) }
                            }
                        }
                        flow.addView(b, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10); bottomMargin = dp(10) })
                    }
                    sectionsBox.addView(flow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                show.text = "Show ${fmt(r.shown.size)} $noun"
                val target = focusTag?.let { outer.findViewWithTag<View>(it) }
                    ?: outer.findViewWithTag<View>("p_${TitleFilter.Section.WATCHED.name}_${pending.get(TitleFilter.Section.WATCHED)}")
                outer.post { target?.requestFocus() }
            }
        }
        show.setOnClickListener {
            popup.dismiss()
            apply(pending)
        }
        clear.setOnClickListener {
            pending = TitleFilter.State()
            draw(null)
        }
        popup.setBackgroundDrawable(ColorDrawable(0))
        popup.elevation = dp(16).toFloat()
        popup.isOutsideTouchable = true
        popup.setOnDismissListener { panel = null; onClosed() }
        popup.showAtLocation(activity.window.decorView, Gravity.END or Gravity.CENTER_VERTICAL, dp(24), 0)
        draw(null)
    }
}
