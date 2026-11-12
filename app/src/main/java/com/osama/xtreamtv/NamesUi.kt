package com.osama.xtreamtv

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
import coil.transform.CircleCropTransformation

/**
 * Names you can press (package 2, item 3, approved 2026-11-12): the Director (or Created by) and Cast
 * rows on a title's page, above its buttons. A person with two or more titles in the playlist is a
 * button with a small photo (initials when there is none) that opens their page; anyone else is plain
 * text, which the remote passes over.
 */
object NamesUi {

    class Name(val text: String, val photo: String?, val count: Int)

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    /** The rows, ready to put above the buttons. [open] is called with the person's name. */
    fun rows(a: Activity, directorLabel: String, directors: List<Name>, cast: List<Name>, open: (String) -> Unit): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            tag = "names_rows"
            setPadding(0, dp(a, 6), 0, dp(a, 10))
        }
        if (directors.isNotEmpty()) box.addView(row(a, directorLabel, directors, open))
        if (cast.isNotEmpty()) box.addView(row(a, "Cast", cast, open))
        return box
    }

    private fun row(a: Activity, label: String, names: List<Name>, open: (String) -> Unit): View {
        val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(TextView(a).apply {
            text = label
            textSize = 14f
            setTextColor(a.themeColor(R.attr.appTextSecondary))
            minWidth = dp(a, 96)
        })
        // The names wrap to a second line when they don't fit: every name stays whole, buttons or text.
        val chips = FlowRow(a)
        for (n in names) {
            val v = if (n.count >= 2) button(a, n, open) else TextView(a).apply {
                text = n.text
                textSize = 14f
                setTextColor(a.themeColor(R.attr.appTextSecondary))
                setPadding(dp(a, 10), dp(a, 6), dp(a, 10), dp(a, 6))
            }
            chips.addView(v, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 8); topMargin = dp(a, 3); bottomMargin = dp(a, 3) })
        }
        line.addView(chips, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return line
    }

    private fun button(a: Activity, n: Name, open: (String) -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_glass_choice)
        setPadding(dp(a, 6), dp(a, 4), dp(a, 14), dp(a, 4))
        isFocusable = true
        isClickable = true
        contentDescription = n.text
        val size = dp(a, 30)
        val face = android.widget.FrameLayout(a)
        val initials = TextView(a).apply {
            text = n.text.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(a.themeColor(R.attr.appText))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
        }
        face.addView(initials, android.widget.FrameLayout.LayoutParams(size, size))
        val url = Tmdb.photoUrl(n.photo)
        if (url != null) {
            val img = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            face.addView(img, android.widget.FrameLayout.LayoutParams(size, size))
            img.load(url) {
                transformations(CircleCropTransformation())
                crossfade(false)
                listener(onError = { _, _ -> img.visibility = View.GONE })
            }
        }
        addView(face, LinearLayout.LayoutParams(size, size))
        addView(TextView(a).apply {
            text = n.text
            textSize = 14f
            isSingleLine = true
            setTextColor(ContextCompat.getColorStateList(a, R.color.on_focus_text))
            setPadding(dp(a, 8), 0, 0, 0)
            isDuplicateParentStateEnabled = true
        })
        setOnClickListener { open(n.text) }
    }
}
