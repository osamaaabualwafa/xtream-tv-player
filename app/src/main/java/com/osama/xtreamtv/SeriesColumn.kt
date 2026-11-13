package com.osama.xtreamtv

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The series page's column (owner, 2026-11-13): everything except the scrolling top part is measured
 * first at its full height (season tabs, episodes, More like this), then the top part gets what is
 * left, and no more than it needs. So the episodes are never squeezed or cut, the seasons sit right
 * under the buttons with the same gap on every series, and leftover space ends up at the bottom.
 */
class SeriesColumn @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (orientation != VERTICAL || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val height = MeasureSpec.getSize(heightMeasureSpec)
        var used = paddingTop + paddingBottom
        var top: ScrollView? = null
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (c is ScrollView && top == null) { top = c; continue }
            val lp = c.layoutParams as LayoutParams
            measureChildWithMargins(c, widthMeasureSpec, 0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0)
            used += c.measuredHeight + lp.topMargin + lp.bottomMargin
        }
        // (measureChildWithMargins takes off the column's own padding itself: only the other children count.)
        top?.let { measureChildWithMargins(it, widthMeasureSpec, 0, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY), used - paddingTop - paddingBottom) }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }
}
