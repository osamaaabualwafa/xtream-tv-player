package com.osama.xtreamtv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.ReplacementSpan

/**
 * Review item 1.1E: real IMDb and Rotten Tomatoes scores, shown as logos and numbers only
 * ("IMDb" on its yellow label, then the score; the red tomato for 60% or more, the green splat
 * below). A missing score is left out, never shown as 0 or N/A.
 */
object RatingBadges {

    class Scores(val imdb: String?, val tomatoes: Int?) {
        val isEmpty get() = imdb == null && tomatoes == null
    }

    /** One line of text: [IMDb] 7.8   🍅 92%, in the given text colour for the numbers. */
    fun line(context: Context, scores: Scores, numberColor: Int): CharSequence {
        val out = SpannableStringBuilder()
        scores.imdb?.let { score ->
            val start = out.length
            out.append("IMDb")
            out.setSpan(ImdbLabel(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(" ")
            appendNumber(out, score, numberColor)
        }
        scores.tomatoes?.let { percent ->
            if (out.isNotEmpty()) out.append("    ")
            val start = out.length
            out.append("o")
            val icon = androidx.core.content.ContextCompat.getDrawable(context, if (percent >= 60) R.drawable.ic_rt_fresh else R.drawable.ic_rt_rotten)!!
            out.setSpan(TomatoSpan(icon), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(" ")
            appendNumber(out, "$percent%", numberColor)
        }
        return out
    }

    private fun appendNumber(out: SpannableStringBuilder, text: String, color: Int) {
        val start = out.length
        out.append(text)
        out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** "IMDb" in black bold on IMDb's yellow, a little smaller than the text around it. */
    private class ImdbLabel : ReplacementSpan() {
        private val rect = RectF()

        private fun labelPaint(paint: Paint) = Paint(paint).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = paint.textSize * 0.82f
        }

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val p = labelPaint(paint)
            return (p.measureText(text, start, end) + paint.textSize * 0.5f).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val p = labelPaint(paint)
            val width = getSize(paint, text, start, end, null).toFloat()
            val h = paint.textSize * 0.95f
            val cy = y - paint.textSize * 0.35f
            rect.set(x, cy - h / 2, x + width, cy + h / 2)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5C518.toInt() }
            canvas.drawRoundRect(rect, h * 0.18f, h * 0.18f, fill)
            p.color = 0xFF000000.toInt()
            p.isAntiAlias = true
            val textWidth = p.measureText(text, start, end)
            canvas.drawText(text, start, end, x + (width - textWidth) / 2, cy + p.textSize * 0.36f, p)
        }
    }

    /** The tomato or splat, the height of the text, centred on it. */
    private class TomatoSpan(drawable: android.graphics.drawable.Drawable) : ImageSpan(drawable) {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (paint.textSize * 1.05f).toInt()

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val size = (paint.textSize * 1.05f).toInt()
            val cy = y - paint.textSize * 0.35f
            drawable.setBounds(0, 0, size, size)
            canvas.save()
            canvas.translate(x, cy - size / 2f)
            drawable.draw(canvas)
            canvas.restore()
        }
    }

    /**
     * Test builds only, for screenshots before the real lookups exist (Settings toggle
     * "dbg_sample_ratings"): fixed sample scores for about half the titles. Never in the release app.
     */
    fun sample(context: Context, id: String): Scores? {
        if (!BuildConfig.DEBUG) return null
        if (!context.getSharedPreferences("xtream", Context.MODE_PRIVATE).getBoolean("dbg_sample_ratings", false)) return null
        val h = (id.hashCode() and 0x7FFFFFFF)
        if (h % 2 == 1) return null
        val imdb = "%.1f".format(java.util.Locale.US, 5.0 + (h % 40) / 10.0)
        val tomatoes = if (h % 7 == 0) null else 30 + (h / 7) % 68
        return Scores(imdb, tomatoes)
    }
}
