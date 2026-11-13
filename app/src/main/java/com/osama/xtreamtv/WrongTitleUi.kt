package com.osama.xtreamtv

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import coil.load
import kotlinx.coroutines.launch

/**
 * "Which title is this?" (package 2, item 5, approved 2026-11-12): up to five titles TMDB finds for
 * this name, each with its poster, year, kind, genres and TMDB score; the one used now and the first
 * match (to go back to) are marked. "Search by name" searches other words; "None of these" shows no
 * scores. The choice is the owner's: it is saved and never changed by the fill or a refresh.
 */
object WrongTitleUi {

    fun show(
        a: Activity, scope: LifecycleCoroutineScope, type: ContentType, id: String, name: String,
        onChosen: (String) -> Unit, onNone: () -> Unit,
    ) {
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(a.themeColor(R.attr.appOverlay) or 0xF0000000.toInt())
                setStroke(dp(1).coerceAtLeast(1), 0x40FFFFFF)
            }
        }
        val popup = PopupWindow(box, (a.resources.displayMetrics.widthPixels * 0.5f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        box.addView(TextView(a).apply {
            text = "Which title is this?"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(a.themeColor(R.attr.appText))
        })
        box.addView(TextView(a).apply {
            text = "${Versions.clean(name)} · the scores shown belong to the title you choose"
            textSize = 14f
            setTextColor(a.themeColor(R.attr.appTextSecondary))
            setPadding(0, dp(2), 0, dp(10))
        })
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        val status = TextView(a).apply { text = "Looking…"; textSize = 15f; setTextColor(a.themeColor(R.attr.appTextSecondary)); setPadding(0, dp(8), 0, dp(8)) }
        list.addView(status)
        val buttons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        fun button(text: String, click: () -> Unit) = TextView(a).apply {
            this.text = text
            textSize = 15f
            setTextColor(ContextCompat.getColorStateList(a, R.color.on_focus_text))
            setBackgroundResource(R.drawable.bg_glass_choice)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isFocusable = true
            isClickable = true
            setOnClickListener { click() }
            buttons.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
        }
        lateinit var load: (String) -> Unit
        button("Search by name") {
            val input = android.widget.EditText(a).apply { setText(Versions.clean(name)); setSingleLine(); setSelection(text.length) }
            androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("Search TMDB")
                .setView(input)
                .setPositiveButton("Search") { _, _ -> input.text.toString().trim().takeIf { it.length >= 2 }?.let { load(it) } }
                .setNegativeButton("Cancel", null)
                .show()
                // Moves up with the TV's keyboard (Package 5, item 3).
                .window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        button("None of these: show no scores") { popup.dismiss(); onNone() }
        val cancel = button("Cancel") { popup.dismiss() }
        box.addView(buttons)

        val now = Ratings.matchOf(type, id)
        load = { query ->
            status.text = "Looking…"
            list.removeAllViews()
            list.addView(status)
            scope.launch {
                val (found, original) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val c = Tmdb.candidates(type, query)
                    val orig = Ratings.originalMatch(type, id)
                    // The current and the first match are offered too, when the search doesn't list them.
                    val extra = listOfNotNull(now, orig).distinct().filter { r -> c?.none { it.ref == r } != false && r.startsWith("tmdb/") }.mapNotNull { r ->
                        Tmdb.details(type, r).found?.let { f -> Tmdb.Candidate(r, f.name ?: return@mapNotNull null, f.year, f.poster, f.genres, 0f) }
                    }
                    (extra + c.orEmpty()).distinctBy { it.ref }.take(6) to orig
                }
                list.removeAllViews()
                if (found.isEmpty()) {
                    status.text = if (Tmdb.usable) "TMDB found nothing for these words. Try Search by name." else "TMDB can't be reached right now (a TMDB key is needed)."
                    list.addView(status)
                    cancel.requestFocus()
                    return@launch
                }
                var focus: View? = null
                for (cand in found) {
                    val isNow = cand.ref == now
                    val row = LinearLayout(a).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setBackgroundResource(R.drawable.bg_glass_choice)
                        setPadding(dp(10), dp(8), dp(16), dp(8))
                        isFocusable = true
                        isClickable = true
                        isActivated = isNow
                        setOnClickListener { popup.dismiss(); if (!isNow) onChosen(cand.ref) }
                    }
                    val img = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    row.addView(img, LinearLayout.LayoutParams(dp(40), dp(60)))
                    Tmdb.posterUrl(cand.poster)?.let { img.load(it) { crossfade(false) } }
                    val words = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(10), 0) }
                    words.addView(TextView(a).apply {
                        text = cand.name
                        textSize = 17f
                        setTextColor(ContextCompat.getColorStateList(a, R.color.on_focus_text))
                        isDuplicateParentStateEnabled = true
                    })
                    val kind = if (type == ContentType.MOVIE) "Movie" else "Series"
                    val marks = listOfNotNull(if (isNow) "used now" else null, if (cand.ref == original) "first match" else null)
                    words.addView(TextView(a).apply {
                        text = (listOfNotNull(cand.year.takeIf { it > 0 }?.toString(), kind, cand.genres) + marks).joinToString(" · ")
                        textSize = 13f
                        setTextColor(ContextCompat.getColorStateList(a, R.color.on_focus_text))
                        isDuplicateParentStateEnabled = true
                    })
                    row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (cand.score > 0f) row.addView(TextView(a).apply {
                        text = "TMDB %.1f".format(java.util.Locale.US, cand.score)
                        textSize = 14f
                        setTextColor(0xFFF5C518.toInt())
                    })
                    if (isNow || focus == null && !isNow) focus = if (isNow) focus ?: row else focus ?: row
                    list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                }
                // The first title that is not the current one, so one OK changes the match.
                val first = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { !it.isActivated } ?: list.getChildAt(0)
                first?.requestFocus()
            }
        }
        popup.setBackgroundDrawable(ColorDrawable(0))
        popup.elevation = dp(16).toFloat()
        popup.isOutsideTouchable = true
        popup.showAtLocation(a.window.decorView, Gravity.CENTER, 0, 0)
        cancel.requestFocus()
        load(Versions.clean(name).replace(Regex("\\s*\\(?(19|20)\\d{2}\\)?\\s*$"), ""))
    }
}
