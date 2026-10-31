package com.osama.xtreamtv

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import coil.load

/** Settings > Look: background style, text size, and the small visual effects around the app. */
object Look {

    val BACKGROUND_NAMES = listOf("Glow", "Pure black (OLED)", "Ocean", "Sunset")
    private val BACKGROUND_STYLES = intArrayOf(R.style.Bg_Glow, R.style.Bg_Black, R.style.Bg_Ocean, R.style.Bg_Sunset)

    /** Interface sizes in the order Settings lists them, with the stored value of each. */
    val UI_SIZES = listOf(3 to "Small", 0 to "Compact", 1 to "Normal", 2 to "Large")
    /** How many dp fit across the screen for each stored value (more = everything smaller). */
    private val UI_WIDTHS_DP = intArrayOf(1080, 960, 860, 1200)

    val TEXT_SIZE_NAMES = listOf("Small", "Normal", "Large", "Extra large")
    private val TEXT_SCALES = floatArrayOf(0.9f, 1f, 1.12f, 1.25f)

    /** In onCreate before setContentView (with the theme colour). */
    fun applyBackground(activity: Activity) {
        val prefs = Prefs(activity)
        // A palette or theme brings its own background.
        if (prefs.paletteIndex == 0 && prefs.themeIndex == 0) {
            val index = prefs.backgroundStyle.coerceIn(0, BACKGROUND_STYLES.size - 1)
            activity.theme.applyStyle(BACKGROUND_STYLES[index], true)
        }
        // Once the screen has its size (after setContentView and the first layout).
        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            private var titlesDone = false
            override fun onGlobalLayout() {
                if (!titlesDone) titlesDone = styleTitles(activity)
                if (flattenBackground(activity)) decor.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    /**
     * Screen titles (tagged "screen_title") in the theme's title style: Cinema bold spaced
     * capitals, Coastal light wide capitals, Coconut serif italic. True once done.
     */
    private fun styleTitles(activity: Activity): Boolean {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        if (content.childCount == 0) return false
        val theme = Prefs(activity).themeIndex
        if (theme == 0) return true
        fun walk(v: View) {
            if (v.tag == "screen_title" && v is android.widget.TextView) {
                when (theme) {
                    1 -> { v.isAllCaps = true; v.letterSpacing = 0.12f; v.typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD) }
                    2 -> { v.isAllCaps = true; v.letterSpacing = 0.2f; v.typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL) }
                    3 -> {
                        v.typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD_ITALIC)
                        // Italic letters lean past the text box: a little room so the edge letter isn't cut.
                        val extra = (8 * v.resources.displayMetrics.density).toInt()
                        v.setPadding(v.paddingLeft + extra, v.paddingTop, v.paddingRight + extra, v.paddingBottom)
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(content)
        return true
    }

    /** The screen background painted once, shared by every screen (same style, colour and size). */
    private var flatKey: String? = null
    private var flatBitmap: android.graphics.Bitmap? = null

    /**
     * The glow backgrounds are three full-screen layers (a fill and two soft gradients) on top of
     * the window's own fill, all repainted on every frame while a list scrolls: too much for TV
     * graphics chips. Paint them once into a picture and drop the hidden window fill.
     */
    /** True when done (or nothing to do); false to try again after the next layout. */
    private fun flattenBackground(activity: Activity): Boolean {
        if (activity.isFinishing) return true
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val holder = if (content.background != null) content else content.getChildAt(0) ?: return false
        val bg = holder.background ?: return true
        val w = holder.width
        val h = holder.height
        if (w <= 0 || h <= 0) return false
        // Full-screen screens only (not a pop-up window), and only the drawn glow kinds.
        if (w < activity.resources.displayMetrics.widthPixels / 2) return true
        if (bg is android.graphics.drawable.ColorDrawable || bg is android.graphics.drawable.BitmapDrawable) {
            if (bg is android.graphics.drawable.ColorDrawable && bg.alpha == 255) activity.window.setBackgroundDrawable(null)
            return true
        }
        val prefs = Prefs(activity)
        val key = "${prefs.themeIndex}/${prefs.paletteIndex}/${prefs.backgroundStyle}/${prefs.accentIndex}/${w}x$h"
        val bitmap = flatBitmap.takeIf { flatKey == key } ?: try {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                bg.setBounds(0, 0, w, h)
                bg.draw(Canvas(b))
                flatBitmap = b
                flatKey = key
            }
        } catch (e: OutOfMemoryError) {
            return true
        }
        holder.background = android.graphics.drawable.BitmapDrawable(activity.resources, bitmap)
        activity.window.setBackgroundDrawable(null)
        return true
    }

    private var light: Boolean? = null

    /**
     * A TV short of memory (Android's low-RAM flag, or about 1 GB or less): heavier effects start off
     * there (poster glow, sections prepared from Home); Settings can still turn the glow on.
     */
    fun isLightDevice(context: Context): Boolean = light ?: run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        (am.isLowRamDevice || info.totalMem < 1100L * 1024 * 1024).also { light = it }
    }

    /** In attachBaseContext: the chosen text size on top of the TV's own. */
    fun wrap(base: Context): Context {
        val index = Prefs(base).textSize.coerceIn(0, TEXT_SCALES.size - 1)
        val config = Configuration(base.resources.configuration)
        var changed = false
        if (index != 1) {
            config.fontScale = config.fontScale * TEXT_SCALES[index]
            changed = true
        }
        // Scale to the screen width, whatever density the TV reports (720p sets, 4K sets), and
        // to Settings > Look > Interface size.
        val dm = base.resources.displayMetrics
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        if (longSide > 0) {
            val widthDp = UI_WIDTHS_DP[Prefs(base).uiSize.coerceIn(0, UI_WIDTHS_DP.size - 1)]
            val target = longSide * 160 / widthDp
            if (Math.abs(target - config.densityDpi) > config.densityDpi / 50) {
                config.densityDpi = target
                changed = true
            }
        }
        return if (changed) base.createConfigurationContext(config) else base
    }

}

/** Loading posters: a soft light band moving across instead of a flat grey box. */
class ShimmerDrawable : Drawable() {
    private val base = Paint().apply { color = 0x14FFFFFF }
    private val band = Paint()
    private val rect = RectF()
    private val shift = android.graphics.Matrix()
    private var shaderWidth = -1f

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        canvas.drawRect(rect, base)
        val w = b.width().toFloat()
        // Built once per size and slid along: no new objects per frame while lists scroll.
        if (w != shaderWidth) {
            shaderWidth = w
            band.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x00FFFFFF, 0x1FFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        }
        val phase = (SystemClock.uptimeMillis() % 1400L) / 1400f
        shift.setTranslate(b.left - w + phase * 2 * w, 0f)
        band.shader.setLocalMatrix(shift)
        canvas.drawRect(rect, band)
        // About 30 frames a second is plenty for a soft light band.
        scheduleSelf({ invalidateSelf() }, SystemClock.uptimeMillis() + 33)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * V1: the screen's background becomes a faded copy of the highlighted poster, changing smoothly
 * as the highlight moves. Off in Settings > Look.
 */
class FocusBackdrop private constructor(private val image: ImageView, private val strength: Float = 0.42f) {

    private var shown: String? = null
    private val pending = Runnable { load() }
    private var next: String? = null

    fun show(url: String?) {
        if (url.isNullOrBlank() || url == shown) return
        next = url
        image.removeCallbacks(pending)
        image.postDelayed(pending, 250)
    }

    private fun load() {
        val url = next ?: return
        shown = url
        image.animate().cancel()
        image.animate().alpha(0f).setDuration(150).withEndAction {
            image.load(url) {
                crossfade(false)
                listener(onSuccess = { _, _ -> image.animate().alpha(strength).setDuration(350).start() })
            }
        }.start()
    }

    companion object {
        /** Puts the backdrop behind the screen's content (null when turned off). */
        fun attach(activity: Activity): FocusBackdrop? {
            // Cinema: the highlighted item's artwork always fills the screen.
            val cinema = Prefs(activity).themeIndex == Appearance.THEME_CINEMA
            if (!Prefs(activity).backdropFollows && !cinema) return null
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
            val root = content.getChildAt(0) ?: return null
            // The app background moves behind the picture; the screen itself becomes see-through.
            content.background = root.background
            root.background = null
            val image = ImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val shade = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundResource(R.drawable.shade_details)
            }
            content.addView(image, 0)
            content.addView(shade, 1)
            return FocusBackdrop(image, if (cinema) 0.62f else 0.42f)
        }
    }
}
