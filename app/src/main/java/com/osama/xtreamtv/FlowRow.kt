package com.osama.xtreamtv

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** Lays its items out in one row while they fit and starts another row only when they don't. */
class FlowRow(context: Context) : ViewGroup(context) {

    private inline fun place(limit: Int, action: (View, Int, Int) -> Unit): Int {
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            // The last item's right margin is only a gap, so it may stick out.
            if (x > 0 && x + lp.leftMargin + child.measuredWidth > limit) {
                x = 0
                y += rowHeight
                rowHeight = 0
            }
            action(child, x + lp.leftMargin, y + lp.topMargin)
            x += lp.leftMargin + child.measuredWidth + lp.rightMargin
            rowHeight = maxOf(rowHeight, lp.topMargin + child.measuredHeight + lp.bottomMargin)
        }
        return y + rowHeight
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        for (i in 0 until childCount) measureChildWithMargins(getChildAt(i), widthSpec, 0, heightSpec, 0)
        val limit = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthSpec)
        val height = place(limit) { _, _, _ -> }
        setMeasuredDimension(resolveSize(if (limit == Int.MAX_VALUE) 0 else limit, widthSpec), resolveSize(height, heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(r - l) { child, x, y -> child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight) }
    }

    override fun checkLayoutParams(p: LayoutParams?) = p is MarginLayoutParams
    override fun generateDefaultLayoutParams() = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet?) = MarginLayoutParams(context, attrs)
    override fun generateLayoutParams(p: LayoutParams?) = MarginLayoutParams(p)
}
