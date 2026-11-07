package com.osama.xtreamtv

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The app's short messages ("Added … to favorites", "Marked as watched", …) as a pill at the bottom
 * centre in the theme's own colours, instead of Android's grey pop-up that ignored the theme (approved
 * 2026-11-07, design A). Favorites get a gold ★ (added) or ☆ (removed). One at a time per screen.
 */
object MessagePill {
    private const val TAG = "message_pill"

    /** A message for the next screen to show (one said while its own screen was closing). */
    @Volatile private var pending: Pair<CharSequence, Int>? = null

    fun showOnNextScreen(text: CharSequence, length: Int) {
        pending = text to length
    }

    /** Called when any screen comes to the front (XtreamApp). */
    fun showPending(activity: Activity) {
        val p = pending ?: return
        pending = null
        activity.window?.decorView?.post { show(activity, p.first, p.second) }
    }

    /** When the last message was shown (diagnostics: a long-press that showed nothing). */
    @Volatile var lastShownAt = 0L
        private set

    fun show(activity: Activity, text: CharSequence, length: Int) {
        lastShownAt = android.os.SystemClock.uptimeMillis()
        val root = activity.window?.decorView as? ViewGroup ?: return
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val accent = activity.themeColor(R.attr.appAccent)
        val plain = text.toString()
        val label: CharSequence = when {
            plain.startsWith("Added ") && plain.endsWith(" to favorites") -> star("★  ", plain)
            plain.startsWith("Removed ") && plain.endsWith(" from favorites") -> star("☆  ", plain)
            else -> text
        }
        val pill = TextView(activity).apply {
            tag = TAG
            this.text = label
            textSize = 15f
            maxLines = 2
            gravity = Gravity.CENTER
            setTextColor(activity.themeColor(R.attr.appText))
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                // The theme's dark (or, in Daylight, light) panel, nearly solid, with an accent edge.
                setColor(activity.themeColor(R.attr.appOverlay) or 0xF0000000.toInt())
                setStroke(dp(1).coerceAtLeast(1), (accent and 0x00FFFFFF) or 0xB3000000.toInt())
            }
            elevation = dp(12).toFloat()
            alpha = 0f
            maxWidth = (activity.resources.displayMetrics.widthPixels * 0.7f).toInt()
        }
        root.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(44)
        })
        val stay = if (length == Toast.LENGTH_LONG) 3500L else 2000L
        pill.animate().alpha(1f).setDuration(150).start()
        pill.postDelayed({
            pill.animate().alpha(0f).setDuration(200).withEndAction { (pill.parent as? ViewGroup)?.removeView(pill) }.start()
        }, stay)
    }

    private fun star(mark: String, text: String): CharSequence = SpannableStringBuilder(mark).also { b ->
        b.setSpan(ForegroundColorSpan(0xFFF5C518.toInt()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.append(text)
    }
}
