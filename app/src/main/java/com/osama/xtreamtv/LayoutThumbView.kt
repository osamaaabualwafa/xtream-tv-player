package com.osama.xtreamtv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Small drawing of a Home or browse layout for the Settings picture cards (S2).
 * Blocks are drawn on a 16:9 grid of 100 x 56 units; accent blocks use the theme colour.
 */
class LayoutThumbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** "H0".."H3" for Home layouts, "B0".."B3" for movies and series layouts. */
    var kind: String = "H2"
        set(value) {
            field = value
            invalidate()
        }

    private val plain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val a = context.obtainStyledAttributes(intArrayOf(R.attr.appAccent))
        color = a.getColor(0, 0xFF3D8BFD.toInt())
        a.recycle()
        alpha = 190
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 56 / 100)
    }

    override fun onDraw(canvas: Canvas) {
        val u = width / 100f
        fun box(x: Float, y: Float, w: Float, h: Float, p: Paint = plain) {
            rect.set(x * u, y * u, (x + w) * u, (y + h) * u)
            canvas.drawRoundRect(rect, 1.6f * u, 1.6f * u, p)
        }
        fun posters(x: Float, y: Float, count: Int, w: Float, h: Float, gap: Float = 2f) {
            for (i in 0 until count) box(x + i * (w + gap), y, w, h)
        }
        when (kind) {
            "H0" -> { // hero banner, small tiles, a row
                box(4f, 4f, 92f, 22f, accent)
                for (i in 0 until 4) box(4f + i * 23.5f, 29f, 21.5f, 9f)
                posters(4f, 41f, 9, 8.4f, 11f, 1.8f)
            }
            "H1" -> { // side menu + rows
                box(4f, 4f, 18f, 48f, accent)
                for (r in 0 until 3) posters(26f, 5f + r * 16f, 5, 12.6f, 12f, 1.8f)
            }
            "H2" -> { // big tiles + rows
                box(4f, 6f, 28f, 18f, accent)
                box(34f, 6f, 21f, 18f); box(57f, 6f, 21f, 18f); box(80f, 6f, 16f, 18f)
                posters(4f, 29f, 6, 13.6f, 9f, 2f)
                posters(4f, 41f, 8, 10f, 9f, 2f)
            }
            "H3" -> { // tab bar + rows
                box(28f, 3f, 44f, 6f, accent)
                for (r in 0 until 3) posters(4f, 13f + r * 14f, 7, 11.4f, 11f, 2f)
            }
            "B0" -> { // sidebar + grid + info strip
                box(4f, 4f, 22f, 48f, faint)
                for (i in 0 until 6) box(6f, 7f + i * 7f, 18f, 5f, if (i == 2) accent else plain)
                for (r in 0 until 2) posters(30f, 5f + r * 19f, 6, 9.6f, 16f, 1.6f)
                box(30f, 45f, 66f, 7f, faint)
            }
            "B1" -> { // folding rail + wide grid
                box(4f, 4f, 6f, 48f, accent)
                for (r in 0 until 3) posters(14f, 5f + r * 16f, 8, 8.8f, 14f, 1.8f)
            }
            "B2" -> { // chips on top + full grid
                for (i in 0 until 6) box(4f + i * 15.5f, 4f, 13.5f, 5f, if (i == 2) accent else plain)
                for (r in 0 until 3) posters(4f, 13f + r * 14.5f, 8, 9.9f, 12.5f, 2f)
            }
            "B3" -> { // big preview + one row
                box(4f, 4f, 92f, 28f, faint)
                box(8f, 9f, 30f, 4f); box(8f, 16f, 20f, 3f); box(8f, 22f, 36f, 3f)
                box(62f, 6f, 30f, 24f, accent)
                posters(4f, 36f, 9, 8.4f, 15f, 1.8f)
            }
        }
    }
}
