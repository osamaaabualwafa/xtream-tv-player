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
        if (BuildConfig.DEBUG) FocusAudit.install(activity)
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
        v.animate().scaleX(if (hasFocus) scaleFor(v, style, MAX_GROW_DP) else 1f)
            .scaleY(if (hasFocus) scaleFor(v, style, MAX_GROW_DP) else 1f)
            .translationY(rise).setDuration(140).start()
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
        v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        v.elevation = 12 * v.resources.displayMetrics.density
        accentShadow(v)
    }

    private fun settle(v: View) {
        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
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
 * Debug builds: after each focus change, works out whether the focused item is drawn past anything
 * that clips it, and logs "FocusClip" if so. The screenshot walk counts these lines.
 */
private object FocusAudit {
    fun install(activity: Activity) {
        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnGlobalFocusChangeListener { _, new ->
            new ?: return@addOnGlobalFocusChangeListener
            // Long enough for the growth and for scrolling to the item to finish.
            decor.postDelayed({ if (new.isFocused) check(activity, new) }, 900)
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
        Log.w("FocusClip", "${activity.javaClass.simpleName} ${name(v)} cut ${"%.1f".format(over)}px by ${name(clipper)}")
    }
}
