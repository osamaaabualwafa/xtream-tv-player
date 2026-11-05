package com.osama.xtreamtv

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The one place the item under the remote grows, and the rule that keeps it from being cut off:
 * - growth is limited in dp ([MAX_GROW_DP] a side), not only a percentage, so wide cards grow as
 *   little as small ones;
 * - scrolling areas keep [ROOM_DP] around the focused item ([FocusBox], [FocusScrollView] and the
 *   list layout managers do this), and scroll far enough to show it.
 *
 * Don't scale focused views or change clipChildren / clipToPadding elsewhere:
 * scripts/check_focus.py fails the build, and debug builds log "FocusClip" if an item is cut.
 */
object FocusFx {
    const val MAX_GROW_DP = 8f
    /** Growth plus the small rise of a lifted poster. */
    const val ROOM_DP = 12f
    private const val LIFT_GROW_DP = 4f
    private const val RISE_DP = 4f

    /** Debug builds: also check the text in a pop-up, which has a window of its own. */
    fun auditPopup(window: android.view.Window?) {
        if ((BuildConfig.DEBUG || Diag.enabled) && window != null) FocusAudit.watchText(window.decorView)
    }

    /** Set up each screen (in onCreate, with the theme). */
    fun install(activity: Activity) {
        val observer = activity.window.decorView.viewTreeObserver
        val value = TypedValue()
        activity.theme.resolveAttribute(R.attr.appFocusScale, value, true)
        val liftScale = if (value.type == TypedValue.TYPE_FLOAT) value.float else 1f
        if (liftScale > 1.001f) {
            // "Lift and glow": whatever the remote is on rises, not only posters and cards.
            observer.addOnGlobalFocusChangeListener { old, new ->
                old?.takeUnless(::growsItself)?.let { settle(it) }
                new?.takeUnless(::growsItself)?.let { lift(it, liftScale) }
            }
        }
        if (BuildConfig.DEBUG || Diag.enabled) FocusAudit.install(activity)
    }

    /** Marks a view that grows through [grow] (a poster in a list, a tile). */
    fun markGrows(v: View) = v.setTag(R.id.focus_fx, true)

    private fun growsItself(v: View) = v.onFocusChangeListener === scaleOnFocus || v.getTag(R.id.focus_fx) == true

    /** Posters, cards and tiles: [scaleOnFocus] calls this. */
    fun grow(v: View, hasFocus: Boolean) {
        val prefs = Prefs(v.context)
        val glass = prefs.themeIndex == Appearance.THEME_GLASS
        val inList = v.parent is RecyclerView
        val glow = prefs.posterGlow
        val style = if (glass) (if (inList) 1.06f else 1.04f) else if (glow) 1.1f else 1.06f
        val d = v.resources.displayMetrics.density
        val rise = if (hasFocus && glow && (!glass || inList)) -RISE_DP * d else 0f
        val scale = if (hasFocus) scaleFor(v, style, MAX_GROW_DP) else 1f
        if (RemoteHold.active) {
            // A direction held: grow at once, no shadow; the shadow comes when the button is let go.
            v.animate().cancel()
            v.scaleX = scale
            v.scaleY = scale
            v.translationY = rise
            v.elevation = 0f
            if (hasFocus) RemoteHold.onRelease(object : () -> Unit {
                override fun invoke() {
                    RemoteHold.removeOnRelease(this)
                    if (v.isFocused) grow(v, true)
                }
            })
            return
        }
        v.animate().scaleX(scale).scaleY(scale).translationY(rise).setDuration(140).start()
        v.elevation = if (hasFocus) (if (glass) 6 * d else if (glow) 10 * d else 8f) else 0f
        if (glow) accentShadow(v)
    }

    /** Playlist tiles: grow only, no rise or shadow. */
    fun growTile(v: View, hasFocus: Boolean) {
        val style = if (Prefs(v.context).themeIndex == Appearance.THEME_GLASS) 1.04f else 1.08f
        val scale = if (hasFocus) scaleFor(v, style, MAX_GROW_DP) else 1f
        v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
    }

    private fun lift(v: View, style: Float) {
        val scale = scaleFor(v, style, LIFT_GROW_DP)
        if (RemoteHold.active) {
            v.animate().cancel()
            v.scaleX = scale
            v.scaleY = scale
        } else {
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
        v.elevation = 12 * v.resources.displayMetrics.density
        accentShadow(v)
    }

    private fun settle(v: View) {
        if (RemoteHold.active) {
            v.animate().cancel()
            v.scaleX = 1f
            v.scaleY = 1f
        } else {
            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        v.elevation = 0f
    }

    private fun accentShadow(v: View) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val accent = v.context.themeColor(R.attr.appAccent)
        v.outlineSpotShadowColor = accent
        v.outlineAmbientShadowColor = accent
    }

    /** At most [style], at most [growDp] a side, and never past what would cut it off across the scroll. */
    private fun scaleFor(v: View, style: Float, growDp: Float): Float {
        val size = maxOf(v.width, v.height)
        if (size <= 0) return 1f
        val grow = minOf(growDp * v.resources.displayMetrics.density, room(v))
        return minOf(style, 1f + 2 * grow / size)
    }

    /**
     * Room around the view before anything cuts it: every container that clips on the way up, and
     * the screen. Along the direction a scrolling area moves, that area scrolls the room into view
     * (a stable answer, unlike mid-scroll positions), so it doesn't count there.
     */
    private fun room(v: View): Float {
        var left = v.left.toFloat()
        var top = v.top.toFloat()
        var right = v.right.toFloat()
        var bottom = v.bottom.toFloat()
        var room = Float.MAX_VALUE
        var p = v.parent as? ViewGroup
        while (p != null) {
            left -= p.scrollX; right -= p.scrollX; top -= p.scrollY; bottom -= p.scrollY
            val rv = p as? RecyclerView
            val scrollsSideways = p is HorizontalScrollView || (rv?.layoutManager as? LinearLayoutManager)?.orientation == RecyclerView.HORIZONTAL
            val scrollsDown = p is ScrollView || (rv != null && !scrollsSideways)
            fun limit(l: Float, t: Float, r: Float, b: Float) {
                if (!scrollsSideways) room = minOf(room, left - l, r - right)
                if (!scrollsDown) room = minOf(room, top - t, b - bottom)
            }
            val w = p.width.toFloat()
            val h = p.height.toFloat()
            if (p.clipToPadding && (p.paddingLeft or p.paddingTop or p.paddingRight or p.paddingBottom) != 0) {
                limit(p.paddingLeft.toFloat(), p.paddingTop.toFloat(), w - p.paddingRight, h - p.paddingBottom)
            }
            val up = p.parent as? ViewGroup
            if (up == null || up.clipChildren) limit(0f, 0f, w, h)
            left += p.left; right += p.left; top += p.top; bottom += p.top
            p = up
        }
        return room.coerceAtLeast(0f)
    }
}

/** Gives containers the room the rule needs, without moving anything on screen. */
object FocusBox {

    /**
     * A scrolling area keeps [FocusFx.ROOM_DP] of padding at each side, taken from its own margin
     * and its parent's padding (equal negative margin, so its content stays exactly where it was).
     * Where there is no space to take, it reserves what it can and items grow less.
     */
    fun reserve(v: View) {
        if (v.getTag(R.id.focus_box) == true) return
        v.setTag(R.id.focus_box, true)
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val parent = v.parent as? ViewGroup ?: return
        val room = (FocusFx.ROOM_DP * v.resources.displayMetrics.density).toInt()
        fun extra(padding: Int, margin: Int, parentPadding: Int) =
            (room - padding).coerceAtLeast(0).coerceAtMost(margin.coerceAtLeast(0) + parentPadding)
        val left = extra(v.paddingLeft, lp.leftMargin, parent.paddingLeft)
        val top = extra(v.paddingTop, lp.topMargin, parent.paddingTop)
        val right = extra(v.paddingRight, lp.rightMargin, parent.paddingRight)
        val bottom = extra(v.paddingBottom, lp.bottomMargin, parent.paddingBottom)
        if (v is ViewGroup) v.clipToPadding = false
        if (left + top + right + bottom == 0) return
        v.setPadding(v.paddingLeft + left, v.paddingTop + top, v.paddingRight + right, v.paddingBottom + bottom)
        lp.setMargins(lp.leftMargin - left, lp.topMargin - top, lp.rightMargin - right, lp.bottomMargin - bottom)
        v.layoutParams = lp
        parent.clipToPadding = false
    }

    /** A plain container whose children may grow past its padding and bounds (a card of rows). */
    fun open(v: ViewGroup) {
        v.clipChildren = false
        v.clipToPadding = false
    }

    /** A list whose rows may use its padding while scrolling. */
    fun usePadding(v: ViewGroup) {
        v.clipToPadding = false
    }
}

/** The scroll area for screens the remote moves through. */
class FocusScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    private val room = Rect()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The visible area is cut by this view's own bounds; its content column must not also cut
        // what grows past the column's sides.
        clipChildren = false
        FocusBox.reserve(this)
    }

    // Scrolling to the focused item stops with room around it, not flush at the edge.
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        room.set(rect)
        room.inset(0, -(FocusFx.ROOM_DP * resources.displayMetrics.density).toInt())
        return super.computeScrollDeltaToGetChildRectOnScreen(room)
    }
}

/**
 * Screen checks, after each move of the highlight: a highlight cut by its container, text cut or too
 * small, items on top of each other or partly off screen, hard-to-read colours, and long jumps of the
 * highlight. Debug builds log them for the test tools ("FocusClip", "SizeClip", "UiCheck"); with
 * Settings > About > Diagnostics on they also go into the report, once per screen and problem.
 */
private object FocusAudit {
    fun install(activity: Activity) {
        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            new ?: return@addOnGlobalFocusChangeListener
            // Long enough for the growth and for scrolling to the item to finish.
            decor.postDelayed({ if (new.isFocused) check(activity, new) }, 900)
            if (old != null && old.isAttachedToWindow && old.rootView === new.rootView) farJump(old, new)
            scheduleText(new)
        }
    }

    fun watchText(root: View) {
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, new -> new?.let(::scheduleText) }
    }

    /** Waits for the screen to settle, then looks at everything on it. */
    private fun scheduleText(new: View) {
        new.rootView.removeCallbacks(textCheck)
        textRoot = new.rootView
        new.rootView.postDelayed(textCheck, 500)
    }

    private const val SHORT_LABEL = 14
    private var textRoot: View? = null
    private val seen = HashSet<String>()
    private val textCheck = Runnable { textRoot?.let { scan(it) } }

    private fun found(tag: String, logLine: String, reportLine: String) {
        if (!seen.add("${Diag.screen} $tag $logLine")) return
        if (BuildConfig.DEBUG) Log.w(tag, logLine)
        Diag.log("ui", reportLine)
    }

    private fun scan(root: View) {
        val focusables = ArrayList<View>()
        walk(root, focusables)
        overlaps(focusables)
        offScreen(root, focusables)
    }

    private fun walk(v: View, focusables: MutableList<View>) {
        if (!v.isShown) return
        if (v.isFocusable && v !is androidx.recyclerview.widget.RecyclerView && v.width > 0) focusables.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), focusables)
        (v as? android.widget.TextView)?.let(::checkText)
    }

    private fun checkText(t: android.widget.TextView) {
        val layout = t.layout ?: return
        if (t.text.isEmpty() || t.width <= 0 || t.height <= 0 || t.javaClass.simpleName.contains("EditText")) return
        val last = layout.lineCount - 1
        val room = t.width - t.compoundPaddingLeft - t.compoundPaddingRight
        val shortened = (0..last).any { layout.getEllipsisCount(it) > 0 }
        val problem = when {
            t.ellipsize == android.text.TextUtils.TruncateAt.MARQUEE -> null
            // A long film title may end in "…"; a label or a button name must fit whole.
            shortened && t.text.length <= SHORT_LABEL -> "short label shortened"
            !shortened && (0..last).any { layout.getLineMax(it) > room + 2 } -> "text wider than its box"
            !shortened && layout.getLineEnd(last) < t.text.length -> "text is missing letters"
            (0 until last).any { i ->
                val end = layout.getLineEnd(i)
                end in 1 until t.text.length && !t.text[end - 1].isWhitespace() && t.text[end - 1] != '-' && !t.text[end].isWhitespace()
            } -> "word broken in two"
            layout.height + t.compoundPaddingTop + t.compoundPaddingBottom > t.height + 2 -> "text taller than its box"
            else -> null
        }
        val where = runCatching { t.resources.getResourceEntryName(t.id) }.getOrDefault(t.javaClass.simpleName)
        val quote = "\"${t.text.toString().take(24)}\""
        if (problem != null) found("SizeClip", "$where: $problem ($quote)", "text cut on ${Diag.screen}: $quote ($problem)")
        // Hard to read from the sofa: under about 1.8% of the screen height.
        if (t.textSize < t.rootView.height * 0.018f) {
            found("UiCheck", "$where: text too small ($quote)", "text too small to read on ${Diag.screen}: $quote")
        }
        // Single letters, signs and emoji are pictures, not text to read.
        if (t.text.count { it.isLetter() } >= 3) contrast(t)?.let { ratio ->
            if (ratio < 3f) found("UiCheck", "$where: low contrast ${"%.1f".format(ratio)} ($quote)", "hard-to-read colours on ${Diag.screen}: $quote (contrast ${"%.1f".format(ratio)}:1)")
        }
    }

    /** Text against the nearest solid background behind it; null when there isn't one to measure. */
    private fun contrast(t: android.widget.TextView): Float? {
        var v: View? = t
        while (v != null) {
            val bg = v.background
            val color = when {
                bg is android.graphics.drawable.ColorDrawable -> bg.color
                bg is android.graphics.drawable.GradientDrawable && android.os.Build.VERSION.SDK_INT >= 24 -> bg.color?.getColorForState(v.drawableState, 0)
                else -> null
            }
            if (color != null && (color ushr 24) >= 0xE0) return ratio(t.currentTextColor, color)
            if (color != null) return null
            v = v.parent as? View
        }
        return null
    }

    private fun ratio(a: Int, b: Int): Float {
        fun lum(c: Int): Double {
            fun ch(x: Int): Double { val s = x / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val (x, y) = lum(a) to lum(b)
        return ((maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)).toFloat()
    }

    private fun rectOf(v: View): android.graphics.Rect = android.graphics.Rect().also { v.getGlobalVisibleRect(it) }

    private fun isInside(child: View, parent: View): Boolean {
        var p = child.parent as? View
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    /** Two items drawn over each other (the focused one may grow a little: that is fine). */
    private fun overlaps(all: List<View>) {
        // The full-screen video (and anything as big) sits under everything by design.
        val screenArea = all.firstOrNull()?.rootView?.let { it.width * it.height } ?: return
        val views = all.filter { it.width * it.height < screenArea * 0.4f }
        val rects = views.map(::rectOf)
        for (i in views.indices) for (j in i + 1 until views.size) {
            val a = rects[i]
            val b = rects[j]
            if (a.isEmpty || b.isEmpty || isInside(views[i], views[j]) || isInside(views[j], views[i])) continue
            val o = android.graphics.Rect()
            if (!o.setIntersect(a, b)) continue
            val smaller = minOf(a.width() * a.height(), b.width() * b.height())
            if (o.width() * o.height() > smaller * 0.3f) {
                val names = "${Diag.viewName(views[i])} and ${Diag.viewName(views[j])}"
                found("UiCheck", "overlap: $names", "items on top of each other on ${Diag.screen}: $names")
            }
        }
    }

    /** Items cut by the screen edge outside a scrolling list (where peeking past the edge is normal). */
    private fun offScreen(root: View, views: List<View>) {
        for (v in views) {
            var p = v.parent as? View
            var scrolls = false
            while (p != null) {
                if (p is androidx.recyclerview.widget.RecyclerView || p is android.widget.ScrollView) scrolls = true
                p = p.parent as? View
            }
            if (scrolls) continue
            val r = rectOf(v)
            val full = v.width * v.height
            val shown = r.width() * r.height()
            if (full > 0 && shown in 1 until (full * 0.9f).toInt()) {
                found("UiCheck", "partly off screen: ${Diag.viewName(v)}", "item partly off screen on ${Diag.screen}: ${Diag.viewName(v)}")
            }
        }
    }

    /** The highlight landing much further away than one step (corner to corner, say). */
    private fun farJump(old: View, new: View) {
        val a = rectOf(old)
        val b = rectOf(new)
        if (a.isEmpty || b.isEmpty) return
        val dx = (a.centerX() - b.centerX()).toDouble()
        val dy = (a.centerY() - b.centerY()).toDouble()
        val root = new.rootView
        val diagonal = Math.hypot(root.width.toDouble(), root.height.toDouble())
        if (Math.hypot(dx, dy) > diagonal * 0.6) {
            val names = "${Diag.viewName(old)} → ${Diag.viewName(new)}"
            found("UiCheck", "far jump: $names", "highlight jumped far on ${Diag.screen}: $names")
        }
    }

    private fun check(activity: Activity, v: View) {
        val r = RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())
        var node = v
        var parent = v.parent as? ViewGroup
        while (parent != null) {
            // Into the parent's visible coordinates.
            node.matrix.mapRect(r)
            r.offset(node.left.toFloat() - parent.scrollX, node.top.toFloat() - parent.scrollY)
            if (parent.clipToPadding && (parent.paddingLeft or parent.paddingTop or parent.paddingRight or parent.paddingBottom) != 0) {
                report(activity, v, parent, r, parent.paddingLeft.toFloat(), parent.paddingTop.toFloat(), (parent.width - parent.paddingRight).toFloat(), (parent.height - parent.paddingBottom).toFloat())
            }
            val up = parent.parent as? ViewGroup
            if (up != null && up.clipChildren) report(activity, v, parent, r, 0f, 0f, parent.width.toFloat(), parent.height.toFloat())
            node = parent
            parent = up
        }
    }

    private fun report(activity: Activity, v: View, clipper: View, r: RectF, left: Float, top: Float, right: Float, bottom: Float) {
        val over = maxOf(left - r.left, r.right - right, maxOf(top - r.top, r.bottom - bottom))
        if (over < 1.5f) return
        fun name(x: View) = x.javaClass.simpleName + if (x.id != View.NO_ID) "#" + runCatching { x.resources.getResourceEntryName(x.id) }.getOrDefault("?") else ""
        val line = "${activity.javaClass.simpleName} ${name(v)} cut ${"%.1f".format(over)}px by ${name(clipper)}"
        if (BuildConfig.DEBUG) Log.w("FocusClip", line)
        if (seen.add("${Diag.screen} highlight ${name(v)}")) Diag.log("ui", "highlight cut on ${Diag.screen}: ${Diag.viewName(v)}")
    }
}
