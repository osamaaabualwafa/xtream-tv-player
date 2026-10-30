package com.osama.xtreamtv

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Avatar picker (A2): a preview at the top, emoji grouped (TV and movies, Sports, ...) with
 * "Letter" for the default first letter, and the circle colour. Saved per playlist.
 */
object AvatarPicker {

    private const val LETTER_GROUP = -1

    fun show(activity: Activity, playlist: Playlist, onSaved: () -> Unit) {
        val prefs = Prefs(activity)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_avatar, null)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var emoji: String? = prefs.avatar(playlist.id)
        var colorIndex = prefs.avatarColor(playlist.id)

        val preview = root.findViewById<TextView>(R.id.avatar_preview)
        root.findViewById<TextView>(R.id.avatar_name).text = playlist.name
        fun updatePreview() {
            if (emoji != null) {
                preview.text = emoji
                preview.textSize = 36f
            } else {
                preview.text = playlist.name.trim().firstOrNull()?.uppercase() ?: "?"
                preview.textSize = 32f
            }
            val color = if (colorIndex in Appearance.ACCENT_COLORS.indices) Appearance.ACCENT_COLORS[colorIndex] else avatarColor(playlist.id)
            preview.backgroundTintList = ColorStateList.valueOf(color)
        }

        // Emoji row for the chosen group (or the single "letter" choice).
        val emojiRow = root.findViewById<LinearLayout>(R.id.avatar_emojis)
        fun showGroup(group: Int) {
            emojiRow.removeAllViews()
            val choices: List<String?> = if (group == LETTER_GROUP) listOf(null) else Appearance.AVATAR_GROUPS[group].second
            for (choice in choices) {
                val cell = TextView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(10) }
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_avatar_cell)
                    isFocusable = true
                    isClickable = true
                    setTextColor(Color.WHITE)
                    if (choice == null) {
                        text = playlist.name.trim().firstOrNull()?.uppercase() ?: "?"
                        textSize = 24f
                    } else {
                        text = choice
                        textSize = 26f
                    }
                    isActivated = choice == emoji
                    setOnClickListener {
                        emoji = choice
                        for (i in 0 until emojiRow.childCount) emojiRow.getChildAt(i).isActivated = false
                        isActivated = true
                        updatePreview()
                    }
                }
                emojiRow.addView(cell)
            }
        }

        // Group chips: each group, then "Letter".
        val groups = root.findViewById<LinearLayout>(R.id.avatar_groups)
        val groupIds = Appearance.AVATAR_GROUPS.indices.toList() + LETTER_GROUP
        for (g in groupIds) {
            val chip = LayoutInflater.from(activity).inflate(R.layout.item_glass_pill, groups, false) as TextView
            chip.text = if (g == LETTER_GROUP) "Letter" else Appearance.AVATAR_GROUPS[g].first
            chip.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showGroup(g) }
            chip.setOnClickListener { showGroup(g); emojiRow.getChildAt(0)?.requestFocus() }
            groups.addView(chip)
        }

        // Circle colours: automatic, then the theme colours.
        val colors = root.findViewById<LinearLayout>(R.id.avatar_colors)
        for (index in listOf(-1) + Appearance.ACCENT_COLORS.indices) {
            val ring = colorDot(activity, if (index < 0) avatarColor(playlist.id) else Appearance.ACCENT_COLORS[index], if (index < 0) "A" else null)
            ring.isActivated = index == colorIndex
            ring.setOnClickListener {
                colorIndex = index
                for (i in 0 until colors.childCount) colors.getChildAt(i).isActivated = false
                ring.isActivated = true
                updatePreview()
            }
            colors.addView(ring)
        }

        root.findViewById<View>(R.id.avatar_cancel).setOnClickListener { dialog.dismiss() }
        root.findViewById<View>(R.id.avatar_save).setOnClickListener {
            prefs.setAvatar(playlist.id, emoji, colorIndex)
            dialog.dismiss()
            onSaved()
        }

        // Start on the group that holds the current avatar.
        val startGroup = emoji?.let { e -> Appearance.AVATAR_GROUPS.indexOfFirst { e in it.second } }?.takeIf { it >= 0 } ?: LETTER_GROUP
        showGroup(startGroup)
        updatePreview()
        dialog.show()
        groups.getChildAt(groupIds.indexOf(startGroup))?.requestFocus()
    }

    /** A colour choice: a filled circle inside a focus ring; [label] is drawn on it (e.g. "A" for automatic). */
    fun colorDot(activity: Activity, color: Int, label: String? = null): FrameLayout {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(8) }
            setBackgroundResource(R.drawable.bg_dot_ring)
            isFocusable = true
            isClickable = true
            val dot = TextView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
                setBackgroundResource(R.drawable.bg_avatar)
                backgroundTintList = ColorStateList.valueOf(color)
                gravity = Gravity.CENTER
                text = label ?: ""
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            addView(dot)
            // The ring only: keep the dot itself out of focus.
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }
}
