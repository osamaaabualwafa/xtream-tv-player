package com.osama.xtreamtv

import android.graphics.Rect
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Package 5, item 3 (approved 2026-11-13): a typing field is never hidden behind the TV's keyboard.
 * When the keyboard opens, [content] slides up just enough to show the field above it, and slides back
 * when it closes. When the field can't move high enough, a "you're typing" line sits just above the
 * keyboard with the field's name and what was typed (a password as dots). TV keyboards don't all tell
 * the app their height: when they don't, the bottom half of the screen is taken as theirs.
 */
object KeyboardGuard {

    /** Keyboards that don't say how tall they are: taken as this share of the screen. */
    private const val KEYBOARD_SHARE = 0.5f

    fun attach(window: Window, content: View, fields: List<EditText>) {
        val decor = window.decorView as? ViewGroup ?: return
        val d = content.resources.displayMetrics.density
        var imeTop = 0
        var openNow = false
        var line: TextView? = null

        fun label(f: EditText): String = (f.hint?.toString() ?: "").ifBlank { "Typing" }

        fun lineText(f: EditText): String {
            val secret = (f.inputType and InputType.TYPE_MASK_VARIATION) in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            val text = f.text?.toString().orEmpty()
            return "${label(f)}:  " + if (secret) "●".repeat(text.length) else text.ifEmpty { "…" }
        }

        fun hideLine() { line?.let { decor.removeView(it) }; line = null }

        fun place() {
            val f = fields.firstOrNull { it.hasFocus() }
            if (!openNow || f == null) {
                content.animate().translationY(0f).setDuration(160).start()
                hideLine()
                return
            }
            val screenH = decor.height.takeIf { it > 0 } ?: return
            val top = if (imeTop in 1 until screenH) imeTop else (screenH * (1 - KEYBOARD_SHARE)).toInt()
            val r = Rect()
            f.getGlobalVisibleRect(r)
            val now = content.translationY
            val fieldTop = r.top - now
            val fieldBottom = r.bottom - now
            val margin = (16 * d).toInt()
            val need = fieldBottom - (top - margin)
            // Up by what's needed, but never so far that the field goes off the top.
            val shift = need.coerceAtMost(fieldTop - (24 * d)).coerceAtLeast(0f)
            content.animate().translationY(-shift).setDuration(160).start()
            if (fieldBottom - shift > top - margin / 2) {
                val tv = line ?: TextView(content.context).also { t ->
                    t.textSize = 16f
                    t.setTextColor(0xFFFFFFFF.toInt())
                    t.setBackgroundColor(0xE6202A33.toInt())
                    t.setPadding((24 * d).toInt(), (10 * d).toInt(), (24 * d).toInt(), (10 * d).toInt())
                    t.isSingleLine = true
                    t.ellipsize = android.text.TextUtils.TruncateAt.START
                    decor.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                    line = t
                }
                tv.text = lineText(f)
                tv.post { tv.translationY = (top - tv.height).toFloat().coerceAtLeast(0f) }
            } else hideLine()
        }

        fun setOpen(open: Boolean) {
            if (open == openNow && open) { content.post { place() }; return }
            openNow = open
            content.post { place() }
        }

        for (f in fields) {
            (f as? KeyboardEditText)?.let { k ->
                val before = k.onKeyboard
                k.onKeyboard = { open -> before?.invoke(open); setOpen(open) }
            }
            f.setOnFocusChangeListenerChained { has -> if (has && openNow) content.post { place() } else if (!has && fields.none { it.hasFocus() }) setOpen(false) }
            f.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { line?.let { tv -> fields.firstOrNull { it.hasFocus() }?.let { tv.text = lineText(it) } } }
            })
        }
        // Keyboards that report themselves: their real top edge.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(decor) { v, insets ->
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val visible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            imeTop = if (visible && ime.bottom > 0) v.height - ime.bottom else 0
            if (visible != openNow) setOpen(visible) else if (visible) content.post { place() }
            androidx.core.view.ViewCompat.onApplyWindowInsets(v, insets)
        }
    }

    /** Adds a focus reaction without replacing the field's own. */
    private fun EditText.setOnFocusChangeListenerChained(extra: (Boolean) -> Unit) {
        val before = onFocusChangeListener
        setOnFocusChangeListener { v, has -> before?.onFocusChange(v, has); extra(has) }
    }
}
