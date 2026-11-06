package com.osama.xtreamtv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * The diagnostics screens, built from the Glass parts (frosted card, round buttons, the theme's
 * highlight) so they look like the rest of the app in every theme: how to share, sharing, sent, the
 * "What went wrong?" question after Mark, and the one-time message after a crash.
 */
object DiagUi {
    const val YELLOW = 0xFFF3D28B.toInt()
    const val RED = 0xFFF2B3B3.toInt()
    const val BLUE = 0xFFBFD3F5.toInt()
    const val GREEN = 0xFFA9E6C9.toInt()

    /**
     * Buttons on the diagnostics screens: the selected one must stand out on the dark glass card in every
     * highlight style (with "Lift and glow" the usual fill is too faint there), so a stronger fill in the
     * background's highlight colour with a thin edge of it.
     */
    fun buttonBackground(activity: Activity, radiusDp: Int): android.graphics.drawable.Drawable {
        val d = activity.resources.displayMetrics.density
        val accent = activity.themeColor(R.attr.appAccent)
        fun shape(fill: Int, stroke: Int, width: Float) = GradientDrawable().apply {
            cornerRadius = radiusDp * d
            setColor(fill)
            setStroke((width * d).toInt().coerceAtLeast(1), stroke)
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape((accent and 0x00FFFFFF) or 0x40000000, (accent and 0x00FFFFFF) or 0xCC000000.toInt(), 2f))
            addState(intArrayOf(android.R.attr.state_pressed), shape((accent and 0x00FFFFFF) or 0x55000000, accent, 2f))
            addState(intArrayOf(), shape(0x12FFFFFF, 0x24FFFFFF, 1f))
        }
    }

    internal class Card(val activity: Activity, val dialog: Dialog, val body: LinearLayout) {
        private val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun color(attr: Int) = activity.themeColor(attr)

        fun clear() = body.removeAllViews()

        fun title(text: String, icon: Int? = null, tint: Int? = null) = row(dp(2)).also { r ->
            if (icon != null) r.addView(ImageView(activity).apply {
                setImageResource(icon)
                tint?.let { setColorFilter(it) }
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) }
            })
            r.addView(TextView(activity).apply {
                this.text = text
                textSize = 20f
                setTextColor(tint ?: color(R.attr.appText))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
        }

        fun line(text: String, size: Float = 14f, top: Int = 4, tint: Int? = null) = TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(tint ?: color(R.attr.appTextSecondary))
            setPadding(0, dp(top), 0, 0)
            body.addView(this)
        }

        fun row(top: Int = 0): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
            body.addView(this)
        }

        fun step(number: Int, text: String) = row(dp(10)).also { r ->
            r.addView(TextView(activity).apply {
                this.text = number.toString()
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(color(R.attr.appAccent))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor((color(R.attr.appAccent) and 0x00FFFFFF) or 0x2E000000)
                }
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(12) }
            })
            r.addView(TextView(activity).apply {
                this.text = text
                textSize = 15f
                setTextColor(color(R.attr.appText))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

        /** A tinted see-through box with a matching edge: privacy, warnings. */
        fun note(text: String, tint: Int, icon: Int? = null, top: Int = 12): View = row(dp(top)).also { r ->
            r.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor((tint and 0x00FFFFFF) or 0x22000000)
                setStroke(dp(1), (tint and 0x00FFFFFF) or 0x66000000)
            }
            r.setPadding(dp(12), dp(9), dp(12), dp(9))
            if (icon != null) r.addView(ImageView(activity).apply {
                setImageResource(icon)
                setColorFilter(tint)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) }
            })
            r.addView(TextView(activity).apply {
                this.text = text
                textSize = 13f
                setTextColor(tint)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

        /** A round Glass button. */
        fun pill(parent: ViewGroup, text: String, icon: Int? = null, weight: Float = 1f, onClick: () -> Unit): TextView =
            (LayoutInflater.from(activity).inflate(R.layout.item_glass_pill, parent, false) as TextView).also { b ->
                b.text = text
                b.minHeight = dp(48)
                b.background = buttonBackground(activity, 999)
                b.setOnClickListener { onClick() }
                if (icon != null) {
                    val d = ContextCompat.getDrawable(activity, icon)!!.mutate()
                    d.setBounds(0, 0, dp(20), dp(20))
                    b.setCompoundDrawablesRelative(d, null, null, null)
                    b.compoundDrawablePadding = dp(10)
                }
                b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                    leftMargin = dp(5)
                    rightMargin = dp(5)
                }
                parent.addView(b)
            }

        /** A two-line choice card: a bold name over one line of explanation. */
        fun choice(parent: ViewGroup, name: String, detail: String, onClick: () -> Unit): View = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = buttonBackground(activity, 16)
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(5)
                rightMargin = dp(5)
            }
            addView(TextView(activity).apply {
                text = name
                textSize = 16f
                isDuplicateParentStateEnabled = true
                setTextColor(ContextCompat.getColorStateList(activity, R.color.on_focus_text))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            addView(TextView(activity).apply {
                text = detail
                textSize = 13f
                isDuplicateParentStateEnabled = true
                setTextColor(ContextCompat.getColorStateList(activity, R.color.on_focus_secondary))
                setPadding(0, dp(3), 0, 0)
            })
            parent.addView(this)
        }

        /** A labelled value in a soft box (address, code). */
        fun box(parent: ViewGroup, label: String, value: String, weight: Float, big: Boolean = false): TextView {
            val valueView = TextView(activity).apply {
                text = value
                textSize = if (big) 28f else 20f
                letterSpacing = if (big) 0.25f else 0.02f
                setTextColor(if (big) color(R.attr.appAccent) else color(R.attr.appText))
            }
            parent.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_value_chip)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                // Both boxes as tall as the taller one (the code is bigger).
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                    leftMargin = dp(5)
                    rightMargin = dp(5)
                }
                addView(TextView(activity).apply {
                    text = label
                    textSize = 12f
                    setTextColor(color(R.attr.appTextSecondary))
                })
                addView(valueView)
            })
            return valueView
        }
    }

    internal fun card(activity: Activity, widthDp: Int = 620, build: (Card) -> Unit): Card {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val density = activity.resources.displayMetrics.density
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // Nearly solid, in the background's own dark panel colour: a see-through card let the screen
            // behind show through the text. The thin glass edge stays.
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(activity.themeColor(R.attr.appOverlay) or 0xF0000000.toInt())
                setStroke((1 * density).toInt().coerceAtLeast(1), activity.themeColor(R.attr.appGlassStroke))
            }
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        dialog.setContentView(body, ViewGroup.LayoutParams((widthDp * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // The screen behind goes darker, so the card reads on its own.
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.65f)
        FocusFx.auditPopup(dialog.window)
        val c = Card(activity, dialog, body)
        build(c)
        dialog.show()
        return c
    }

    private val version get() = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

    // ---- Sharing ----

    /** Step one: how it works, and the choice of keeping or clearing the report. */
    fun shareFlow(activity: Activity) {
        card(activity) { c ->
            c.title("Share the report", R.drawable.ic_glass_share, c.color(R.attr.appAccent))
            c.line("Send Claude how the app has been working · $version", 13f)
            c.step(1, "Make sure the TV and the computer are on the same Wi-Fi.")
            c.step(2, "Choose how to share below. The next screen shows an address and a code.")
            c.step(3, "Tell Claude the address and the code, and keep that screen open until it says Sent.")
            c.note("No addresses, logins or passwords are in the report. Sharing stops by itself after 15 minutes.", GREEN, R.drawable.ic_glass_lock)
            val choices = c.row(c.dp(16))
            val keep = c.choice(choices, "Share and keep", "The report stays on the TV. Recording goes on.") {
                c.dialog.dismiss()
                sharing(activity, clear = false)
            }
            c.choice(choices, "Share, then clear", "What was sent is deleted from the TV. Recording goes on.") {
                c.dialog.dismiss()
                sharing(activity, clear = true)
            }
            c.line("Press Back to cancel", 12f, top = 12)
            keep.post { keep.requestFocus() }
        }
    }

    private fun sharing(activity: Activity, clear: Boolean) {
        var sent = false
        lateinit var c: Card
        val share = Diag.startShare(clear) { name ->
            sent = true
            showSent(c, name)
        }
        if (share == null) {
            showToast(activity, "Couldn't start sharing. Is the TV connected to your home network?", Toast.LENGTH_LONG)
            return
        }
        c = card(activity) { card ->
            // The TV must not go to its screensaver while Claude is downloading.
            card.dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            card.title(share.name.substringBefore(" ·"), R.drawable.ic_glass_share, card.color(R.attr.appAccent))
            card.line("${share.name.substringAfter("· ")} · $version · ${if (clear) "clears what is sent" else "keeps the report"}", 13f)
            val boxes = card.row(card.dp(16))
            card.box(boxes, "Address", share.address, 1.6f)
            card.box(boxes, "Code", share.code, 1f, big = true)
            // The time left: a bar that empties in the theme's highlight colour, and the minutes in words.
            val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = 1000
                val radius = card.dp(3).toFloat()
                val track = GradientDrawable().apply { cornerRadius = radius; setColor(0x33FFFFFF) }
                val fill = GradientDrawable().apply { cornerRadius = radius; setColor(card.color(R.attr.appAccent)) }
                progressDrawable = android.graphics.drawable.LayerDrawable(
                    arrayOf(track, android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)),
                ).apply {
                    setId(0, android.R.id.background)
                    setId(1, android.R.id.progress)
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, card.dp(6)).apply { topMargin = card.dp(16) }
            }
            card.body.addView(bar)
            val left = card.line("", 14f, top = 8, tint = card.color(R.attr.appText))
            val buttons = card.row(card.dp(14))
            // "+15 min" only in the last minute, when it is needed.
            val more = card.pill(buttons, "+15 min") {}
            more.visibility = View.GONE
            val stop = card.pill(buttons, "Stop sharing") { card.dialog.dismiss() }
            card.dialog.setOnDismissListener { Diag.stopShare() }
            val tick = object : Runnable {
                override fun run() {
                    if (sent || !card.dialog.isShowing) return
                    val remaining = share.endsAt - SystemClock.elapsedRealtime()
                    if (remaining <= 0) {
                        left.text = "Stopped: the time ran out. Share again when you're ready."
                        bar.progress = 0
                        more.visibility = View.GONE
                        return
                    }
                    bar.progress = (remaining * 1000 / Diag.SHARE_MS).toInt().coerceIn(0, 1000)
                    left.text = "Stops by itself in ${remaining / 60_000}:${"%02d".format((remaining / 1000) % 60)}"
                    val lastMinute = remaining <= 60_000
                    if (lastMinute && more.visibility != View.VISIBLE) more.visibility = View.VISIBLE
                    if (!lastMinute && more.visibility == View.VISIBLE) {
                        if (more.hasFocus()) stop.requestFocus()
                        more.visibility = View.GONE
                    }
                    left.postDelayed(this, 1000)
                }
            }
            more.setOnClickListener {
                Diag.extendShare(share)
                stop.requestFocus()
                tick.run()
            }
            stop.post { stop.requestFocus() }
            // After the screen is open (before that it isn't "showing" yet).
            left.post(tick)
        }
    }

    private fun showSent(c: Card, name: String) {
        if (!c.dialog.isShowing) return
        val stats = Diag.stats()
        c.clear()
        c.title("Sent", R.drawable.ic_glass_check, GREEN)
        c.line("${name.substringBefore(" ·")} · ${stats.entries} entries · ${stats.kb} KB", 13f)
        c.line("Claude has the report. Recording goes on as before.", 15f, top = 12, tint = c.color(R.attr.appText))
        val row = c.row(c.dp(18))
        val done = c.pill(row, "Done") { c.dialog.dismiss() }
        done.post { done.requestFocus() }
    }

    // ---- Mark ----

    /** Saves the moment (and a picture of the screen when allowed), then asks what kind of problem it was. */
    fun mark(from: String, activity: Activity, extra: String = "") {
        if (!Diag.enabled) {
            showToast(activity, "Turn Diagnostics on first (Settings › About)", Toast.LENGTH_SHORT)
            return
        }
        val at = Diag.mark(from, activity, extra)
        card(activity) { c ->
            c.title("What went wrong?", R.drawable.ic_glass_flag, c.color(R.attr.appAccent))
            c.line("Marked at $at on ${Diag.screen}. Pick one, or press Back to skip.", 13f)
            var first: View? = null
            for (pair in Diag.MARK_TYPES.chunked(2)) {
                val row = c.row(c.dp(12))
                for (type in pair) {
                    val b = c.pill(row, type) {
                        Diag.log("MARK", "type: $type (for the mark at $at)")
                        c.dialog.dismiss()
                    }
                    if (first == null) first = b
                }
            }
            first?.let { f -> f.post { f.requestFocus() } }
        }
    }

    // ---- After a crash ----

    /** Once per crash, when the app opens again. The dot on Settings stays until the report is shared. */
    fun crashPrompt(activity: Activity) {
        if (!Diag.takeCrashPrompt()) return
        card(activity) { c ->
            c.title("The app closed unexpectedly", R.drawable.ic_glass_alert, RED)
            c.line("Sharing the report helps fix it. It holds no logins or passwords.", 14f)
            val row = c.row(c.dp(18))
            val share = c.pill(row, "Share now") {
                c.dialog.dismiss()
                shareFlow(activity)
            }
            c.pill(row, "Later") { c.dialog.dismiss() }
            share.post { share.requestFocus() }
        }
    }

    /** The small coloured dot on a Settings button: red for a crash, yellow for full or old, blue for marks. */
    /** [sideRow]: the side menu's Settings row, where the dot sits on its right side, centred on the line. */
    fun applyDot(view: View?, color: Int?, sideRow: Boolean = false) {
        view ?: return
        if (android.os.Build.VERSION.SDK_INT < 23) return
        if (color == null) {
            view.foreground = null
            return
        }
        val d = view.resources.displayMetrics.density
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val layers = android.graphics.drawable.LayerDrawable(arrayOf(dot)).apply {
            setLayerSize(0, (11 * d).toInt(), (11 * d).toInt())
            setLayerGravity(0, Gravity.TOP or Gravity.END)
        }
        view.foreground = layers
        // The side menu's Settings row: the dot on the row's right side, centred on the same line as
        // the word, not in the top corner (2026-11-07). Tiles and top-bar buttons keep the corner.
        if (sideRow) view.post {
            // A fresh drawable: changing the place of one already shown doesn't move it.
            view.foreground = android.graphics.drawable.LayerDrawable(arrayOf(dot)).apply {
                setLayerSize(0, (11 * d).toInt(), (11 * d).toInt())
                setLayerGravity(0, Gravity.END or Gravity.CENTER_VERTICAL)
                setLayerInsetEnd(0, (18 * d).toInt())
            }
        }
    }
}
