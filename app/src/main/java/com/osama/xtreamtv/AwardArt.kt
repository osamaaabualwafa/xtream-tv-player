package com.osama.xtreamtv

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/**
 * Award winners and nominees (1.1E, approved 2026-11-07, option "A and D, light text"): on the title
 * page a chip like the others (same shape and size) in see-through gold or silver glass, fading lighter
 * to the right, with a polished statuette and light text; on posters a corner ribbon of the same
 * tinted glass, winners only. Gold: Oscar, Golden Globe, BAFTA. Silver: Emmy.
 */
object AwardArt {

    enum class Kind(val ribbon: String, val gold: Boolean) {
        OSCAR("OSCAR", true), EMMY("EMMY", false), GLOBE("GLOBE", true), BAFTA("BAFTA", true)
    }

    class Award(val kind: Kind, val winner: Boolean)

    /** "Oscar winner", "Emmy nominee", … (from [RatingsExtras.awardBadge]) -> the award; null for anything else. */
    fun parse(badge: String?): Award? {
        val b = badge ?: return null
        val kind = when {
            b.startsWith("Oscar") -> Kind.OSCAR
            b.startsWith("Emmy") -> Kind.EMMY
            b.startsWith("Golden Globe") -> Kind.GLOBE
            b.startsWith("BAFTA") -> Kind.BAFTA
            else -> return null
        }
        return Award(kind, b.endsWith("winner"))
    }

    // The metal: the chip's fill (left, right), its edge, the text, and the statuette (top, bottom).
    private class Metal(val left: Int, val right: Int, val edge: Int, val text: Int, val shine: Int, val shade: Int)

    private val GOLD = Metal(0x61E2BE64, 0x2EF6E4AA, 0xB3FFECAA.toInt(), 0xFFFFF4D2.toInt(), 0xFFFFF3C4.toInt(), 0xFFD9B35A.toInt())
    private val SILVER = Metal(0x5CC8CFD8, 0x29F0F3F7, 0xB3FFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFB8BFC8.toInt())
    private val GOLD_NOMINEE = Metal(0x38ECD28A, 0x14ECD28A, 0x73ECD28A, 0xFFF1E2B0.toInt(), 0xFFECD28A.toInt(), 0xFFECD28A.toInt())
    private val SILVER_NOMINEE = Metal(0x33DFE4EA, 0x12DFE4EA, 0x66DFE4EA, 0xFFE4E8ED.toInt(), 0xFFDFE4EA.toInt(), 0xFFDFE4EA.toInt())

    // Daylight (a light background): the same metals, deeper, with dark text so it stays readable.
    private val GOLD_DAY = Metal(0x8CD9B35A.toInt(), 0x4DE9C766, 0xCC9C7A28.toInt(), 0xFF4A3508.toInt(), 0xFFB8902E.toInt(), 0xFF7A5C16.toInt())
    private val SILVER_DAY = Metal(0x80A9B0BA.toInt(), 0x4DC9CED6, 0xCC7A818B.toInt(), 0xFF22272E.toInt(), 0xFF8A919B.toInt(), 0xFF4E555E.toInt())

    private fun metal(context: Context, award: Award): Metal {
        val text = context.themeColor(R.attr.appText)
        val light = android.graphics.Color.red(text) + android.graphics.Color.green(text) + android.graphics.Color.blue(text) < 3 * 128
        return when {
            light -> if (award.kind.gold) GOLD_DAY else SILVER_DAY
            !award.winner -> if (award.kind.gold) GOLD_NOMINEE else SILVER_NOMINEE
            else -> if (award.kind.gold) GOLD else SILVER
        }
    }

    /** The title page's chip, shaped like the other chips there ([R.drawable.bg_meta_chip]). */
    fun styleChip(chip: TextView, badge: String): Boolean {
        val award = parse(badge) ?: return false
        val context = chip.context
        val m = metal(context, award)
        val density = context.resources.displayMetrics.density
        val corner = context.obtainStyledAttributes(intArrayOf(R.attr.appCornerMeta)).let { a ->
            a.getDimension(0, 14 * density).also { a.recycle() }
        }
        chip.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(m.left, m.right)).apply {
            cornerRadius = corner
            setStroke((1 * density).toInt().coerceAtLeast(1), m.edge)
        }
        chip.setTextColor(m.text)
        val icon = Statuette(award.kind, m.shine, m.shade, outline = !award.winner)
        val h = (chip.textSize * 1.25f).toInt()
        icon.setBounds(0, 0, (h * icon.ratio).toInt(), h)
        chip.setCompoundDrawablesRelative(icon, null, null, null)
        chip.compoundDrawablePadding = (6 * density).toInt()
        return true
    }

    /** The poster's corner ribbon, drawn by [PosterImageView] over the picture. */
    fun drawRibbon(canvas: Canvas, width: Int, kind: Kind, context: Context) {
        val m = metal(context, Award(kind, true))
        val w = width.toFloat()
        // Far enough from the corner that the band's inner edge (2 x from long) holds the whole word:
        // closer in, "OSCAR" was cut by the poster's edges (2026-11-07).
        val band = w * 0.19f
        val from = w * 0.26f
        canvas.save()
        canvas.translate(w, 0f)
        canvas.rotate(45f)
        val half = from + band + w * 0.1f
        val rect = RectF(-half, from, half, from + band)
        // The app's dark frosted glass first, so the light text reads on bright posters too (on a white
        // poster the tint alone was washed out, 2026-11-07); then the gold or silver tint over it.
        val darkText = android.graphics.Color.red(m.text) + android.graphics.Color.green(m.text) + android.graphics.Color.blue(m.text) < 3 * 128
        canvas.drawRect(rect, Paint().apply { color = if (darkText) 0xB3FFFFFF.toInt() else 0x9E0B1018.toInt() })
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(-half, 0f, half, 0f, intArrayOf(m.right, m.left, m.right), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(rect, fill)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = (w * 0.008f).coerceAtLeast(1f) }
        line.color = 0x8CFFFFFF.toInt()
        canvas.drawLine(-half, from, half, from, line)
        line.color = 0x26000000
        canvas.drawLine(-half, from + band, half, from + band, line)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = m.text
            textSize = band * 0.55f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.1f
            if (!darkText) setShadowLayer(band * 0.12f, 0f, band * 0.04f, 0xCC000000.toInt())
        }
        val label = kind.ribbon
        val textWidth = text.measureText(label)
        val iconH = band * 0.62f
        val icon = Statuette(kind, m.shine, m.shade, outline = false)
        val iconW = iconH * icon.ratio
        val gap = band * 0.18f
        val start = -(iconW + gap + textWidth) / 2f
        val top = from + (band - iconH) / 2f
        icon.setBounds(start.toInt(), top.toInt(), (start + iconW).toInt(), (top + iconH).toInt())
        icon.draw(canvas)
        val baseline = from + band / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, start + iconW + gap, baseline, text)
        canvas.restore()
    }

    /** The award's statuette, polished (light at the top, deeper at the base), or outlined for nominees. */
    class Statuette(private val kind: Kind, private val shine: Int, private val shade: Int, private val outline: Boolean) : Drawable() {
        /** Width / height. */
        val ratio = if (kind == Kind.OSCAR) 12f / 18f else 14f / 18f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val units = if (kind == Kind.OSCAR) 12f else 14f
            val s = b.height() / 18f
            canvas.save()
            canvas.translate(b.left + (b.width() - units * s) / 2f, b.top.toFloat())
            canvas.scale(s, s)
            paint.shader = LinearGradient(0f, 0f, 0f, 18f, shine, shade, Shader.TileMode.CLAMP)
            paint.style = if (outline) Paint.Style.STROKE else Paint.Style.FILL
            paint.strokeWidth = 1.1f
            canvas.drawPath(path(), paint)
            canvas.restore()
        }

        private fun path() = Path().apply {
            when (kind) {
                Kind.OSCAR -> {
                    addCircle(6f, 3f, 2.3f, Path.Direction.CW)
                    moveTo(4.2f, 5.6f); lineTo(7.8f, 5.6f); lineTo(7.2f, 12.2f); lineTo(4.8f, 12.2f); close()
                    addRoundRect(RectF(2.3f, 12.6f, 9.7f, 14.4f), 0.6f, 0.6f, Path.Direction.CW)
                    addRoundRect(RectF(3.3f, 14.9f, 8.7f, 16.7f), 0.6f, 0.6f, Path.Direction.CW)
                }
                Kind.EMMY -> {
                    moveTo(7f, 0.8f); lineTo(8.3f, 3.5f); lineTo(11.3f, 3.7f); lineTo(9f, 5.6f); lineTo(9.7f, 8.5f)
                    lineTo(7f, 7.1f); lineTo(4.3f, 8.5f); lineTo(5f, 5.6f); lineTo(2.7f, 3.7f); lineTo(5.7f, 3.5f); close()
                    addRect(6.2f, 8.3f, 7.8f, 12.9f, Path.Direction.CW)
                    addRoundRect(RectF(3.4f, 13.2f, 10.6f, 16.6f), 0.8f, 0.8f, Path.Direction.CW)
                }
                Kind.GLOBE -> {
                    addCircle(7f, 6f, 4.6f, Path.Direction.CW)
                    addRect(5.4f, 10.6f, 8.6f, 13.2f, Path.Direction.CW)
                    addRoundRect(RectF(3f, 13.4f, 11f, 16.6f), 0.8f, 0.8f, Path.Direction.CW)
                }
                Kind.BAFTA -> {
                    moveTo(2f, 4f); cubicTo(5f, 2.4f, 9f, 2.4f, 12f, 4f)
                    cubicTo(12f, 9f, 10f, 13.5f, 7f, 15f); cubicTo(4f, 13.5f, 2f, 9f, 2f, 4f); close()
                }
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
