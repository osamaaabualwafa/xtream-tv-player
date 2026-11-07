package com.osama.xtreamtv

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** Image that is always 2:3 (portrait poster) for its width, so whole posters fit without cropping. */
class PosterImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width * 3 / 2)
    }

    /** An award winner's corner ribbon (1.1E): gold or silver glass with its statuette; null for none. */
    var award: AwardArt.Kind? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        award?.let { AwardArt.drawRibbon(canvas, width, it, context) }
    }
}
