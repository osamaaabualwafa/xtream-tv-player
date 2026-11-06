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

    /**
     * The size rule, for every theme and screen. Interface size and text size multiply each other,
     * and layouts are built to fit up to this much bigger than Normal interface with Normal text. Beyond it,
     * the app's text setting gives way; the TV's own text setting still counts.
     */
    private const val MAX_COMBINED_SIZE = 1.25f
    private const val BASE_WIDTH_DP = 960f

    /** In onCreate before setContentView (with the theme colour). */
    fun applyBackground(activity: Activity) {
        val prefs = Prefs(activity)
        FocusFx.install(activity)
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
     * Screen titles (tagged "screen_title") in the theme's title style: Glass slightly
     * tight medium. True once done.
     */
    private fun styleTitles(activity: Activity): Boolean {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        if (content.childCount == 0) return false
        val theme = Prefs(activity).themeIndex
        if (theme == 0) return true
        fun walk(v: View) {
            if (v.tag == "screen_title" && v is android.widget.TextView) {
                when (theme) {
                    Appearance.THEME_GLASS -> { v.letterSpacing = -0.02f; v.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
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
        if (GlassDrift.wanted(activity, prefs)) {
            GlassDrift.attach(activity, content, holder)
            return true
        }
        val key = "${prefs.themeIndex}/${prefs.paletteIndex}/${prefs.backgroundStyle}/${prefs.accentIndex}/${prefs.glassScene}/${prefs.glassStyle}/${prefs.movingBackground}/${w}x$h"
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

    /** Glass: a poster behind a screen is always heavily blurred and a little less saturated. */
    fun blurBackdrop(image: ImageView) {
        image.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0.7f) })
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            image.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(40f, 40f, android.graphics.Shader.TileMode.CLAMP))
        }
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
        val interfaceScale = BASE_WIDTH_DP / UI_WIDTHS_DP[Prefs(base).uiSize.coerceIn(0, UI_WIDTHS_DP.size - 1)]
        val textScale = minOf(TEXT_SCALES[index], MAX_COMBINED_SIZE / interfaceScale)
        if (textScale != 1f) {
            config.fontScale = config.fontScale * textScale
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
class FocusBackdrop private constructor(private val image: ImageView, private val strength: Float = 0.42f, private val blurred: Boolean = false) {

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
                // Older TVs have no blur: a tiny picture scaled up gives the same softness.
                if (blurred && android.os.Build.VERSION.SDK_INT < 31) size(32, 48)
                listener(onSuccess = { _, _ -> image.animate().alpha(strength).setDuration(350).start() })
            }
        }.start()
    }

    companion object {
        /** Puts the backdrop behind the screen's content (null when turned off). */
        fun attach(activity: Activity): FocusBackdrop? {
            if (!Prefs(activity).backdropFollows) return null
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
            if (Prefs(activity).themeIndex == Appearance.THEME_GLASS) {
                // Subtle, top-right, faded into the scene: never a coloured blob.
                Look.blurBackdrop(image)
                image.layoutParams = FrameLayout.LayoutParams((activity.resources.displayMetrics.widthPixels * 0.62f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.END)
                return FocusBackdrop(image, 0.38f, blurred = true)
            }
            return FocusBackdrop(image, 0.42f)
        }
    }
}

/**
 * Glass theme, Settings > Look > Effects > Moving background: the wallpaper's glows as three small
 * views (scaled up: soft shapes need no detail) that drift on the GPU, so nothing is redrawn.
 */
object GlassDrift {
    private const val SCALE = 4f

    fun wanted(activity: Activity, prefs: Prefs): Boolean {
        if (prefs.themeIndex != Appearance.THEME_GLASS || !prefs.movingBackground) return false
        if (Look.isLightDevice(activity) || activity is PlayerActivity) return false
        // Never next to video: the channel preview in Live TV.
        val livePreview = activity is BrowseActivity && prefs.channelPreview && activity.intent.getStringExtra(BrowseActivity.EXTRA_TYPE) == ContentType.LIVE.name
        return !livePreview && PlayerActivity.miniTitle() == null
    }

    fun attach(activity: Activity, content: ViewGroup, holder: View) {
        holder.background = null
        content.setBackgroundColor(activity.themeColor(R.attr.appBg))
        activity.window.setBackgroundDrawable(null)
        val w = activity.resources.displayMetrics.widthPixels.toFloat()
        val h = activity.resources.displayMetrics.heightPixels.toFloat()
        val d = activity.resources.displayMetrics.density
        val glows = listOf(
            glow(activity, R.attr.appGlowC, w, h, 0.70f, 1.00f, 0.32f, -0.72f, 0f),
            glow(activity, R.attr.appGlowB, w, h, 1.25f, 1.50f, 0.20f, 0.40f, 0f),
            glow(activity, R.attr.appGlowA, w, h, 1.10f, 1.35f, -0.45f, -0.80f, -25f),
        )
        glows.forEach { content.addView(it, 0) }
        val anims = listOf(
            drift(glows[2], 90 * d, 50 * d, -25f, -18f),
            drift(glows[1], -110 * d, -60 * d, 0f, 0f),
        )
        (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> anims.forEach { if (PlayerActivity.miniTitle() == null) it.resume() }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> anims.forEach { it.pause() }
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> anims.forEach { it.cancel() }
                else -> {}
            }
        })
    }

    private fun glow(activity: Activity, colorAttr: Int, w: Float, h: Float, sizeX: Float, sizeY: Float, left: Float, top: Float, rotation: Float): View {
        val vw = (w * sizeX / SCALE).toInt()
        val vh = (h * sizeY / SCALE).toInt()
        val color = activity.themeColor(colorAttr)
        return View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(vw, vh)
            background = android.graphics.drawable.GradientDrawable().apply {
                gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
                gradientRadius = maxOf(vw, vh) * 0.42f
                colors = intArrayOf(color, color and 0x00FFFFFF)
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            pivotX = vw / 2f
            pivotY = vh / 2f
            scaleX = SCALE
            scaleY = SCALE
            // Laid out in the top-left corner: move its centre to where the recipe puts it.
            translationX = w * (left + sizeX / 2) - vw / 2f
            translationY = h * (top + sizeY / 2) - vh / 2f
            this.rotation = rotation
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    /** About 6 seconds there and back. */
    private fun drift(v: View, dx: Float, dy: Float, fromRotation: Float, toRotation: Float): android.animation.Animator =
        android.animation.ObjectAnimator.ofPropertyValuesHolder(
            v,
            android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, v.translationX, v.translationX + dx),
            android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, v.translationY, v.translationY + dy),
            android.animation.PropertyValuesHolder.ofFloat(View.ROTATION, fromRotation, toRotation),
        ).apply {
            duration = 3000
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
}

/** Glass theme: WinDock's on/off switch for settings rows. */
/** An on/off switch. [solid]: the Default theme, filled with the theme colour when on. */
class GlassSwitch(context: android.content.Context, private val daylight: Boolean, private val solid: Boolean = false) : View(context) {
    var on = false
        set(value) {
            field = value
            invalidate()
        }
    private val density = resources.displayMetrics.density
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val box = android.graphics.RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((40 * density).toInt(), (24 * density).toInt())

    override fun onDraw(canvas: Canvas) {
        val fill: Int
        val stroke: Int
        val knob: Int
        if (solid) {
            fill = if (on) context.themeColor(R.attr.appAccent) else 0x26FFFFFF
            stroke = if (on) context.themeColor(R.attr.appAccent) else 0x40FFFFFF
            knob = if (on) 0xFFFFFFFF.toInt() else 0xFFB4BAC0.toInt()
        } else if (daylight) {
            fill = if (on) context.themeColor(R.attr.appAccent) else 0x141E2822
            stroke = if (on) context.themeColor(R.attr.appAccent) else 0x331E2822
            knob = if (on) 0xFFFFFFFF.toInt() else 0xFF8A948E.toInt()
        } else {
            fill = if (on) 0x4FD6F5E5 else 0x6115242B
            stroke = if (on) 0x7AD6F5E5 else 0x28FFFFFF
            knob = if (on) 0xFFE5FFF2.toInt() else 0xFFB7C9C7.toInt()
        }
        val r = height / 2f
        box.set(density / 2, density / 2, width - density / 2, height - density / 2)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(box, r, r, paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = stroke
        canvas.drawRoundRect(box, r, r, paint)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = knob
        canvas.drawCircle(if (on) width - r else r, r, 8 * density, paint)
    }
}
