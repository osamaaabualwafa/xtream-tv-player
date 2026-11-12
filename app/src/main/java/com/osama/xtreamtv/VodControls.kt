package com.osama.xtreamtv

import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Movie / episode controls (view_vod_controls.xml): titles top-left, clock top-right,
 * light shade, timeline and a centred button row. Fades in and out, hides itself
 * 5 s after the last key press, and can show just the title when a video starts.
 */
class VodControls(private val root: View, private val host: Host) {

    interface Host {
        val player: Player?
        /** Playback position, or where a seek still in progress is going. */
        fun positionMs(): Long = player?.currentPosition ?: 0
        fun seekBy(deltaMs: Long)
        fun togglePlay()
        fun previousEpisode()
        fun nextEpisode()
        fun openEpisodes()
        fun openTracks()
        fun openSettings()
        /** The title's other copies (quality and language). */
        fun openVersions() {}
        /** Controls went away: give focus back to the video. */
        fun onControlsHidden()
        /** A toolbar button was pressed (close an open panel first). */
        fun beforeAction() {}
    }

    companion object {
        private const val FADE_MS = 200L
        private const val TITLE_ONLY_MS = 3_000L
        private const val TIMELINE_STEP_MS = 10_000L
        private val CLOCK = SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    private val shadeBottom: View = root.findViewById(R.id.vc_shade_bottom)
    private val bottom: View = root.findViewById(R.id.vc_bottom)
    private val title: TextView = root.findViewById(R.id.vc_title)
    private val episode: TextView = root.findViewById(R.id.vc_episode)
    private val quality: TextView = root.findViewById(R.id.vc_quality)
    private val clock: TextView = root.findViewById(R.id.vc_clock)
    private val meta: TextView = root.findViewById(R.id.vc_meta)
    private val upNext: View = root.findViewById(R.id.vc_upnext)
    private val upNextTitle: TextView = root.findViewById(R.id.vc_upnext_title)
    private val position: TextView = root.findViewById(R.id.vc_position)
    private val remaining: TextView = root.findViewById(R.id.vc_remaining)
    private val timeline: View = root.findViewById(R.id.vc_timeline)
    private val buffered: View = root.findViewById(R.id.vc_buffered)
    private val played: View = root.findViewById(R.id.vc_played)
    private val thumb: View = root.findViewById(R.id.vc_thumb)
    private val ahead: View = root.findViewById(R.id.vc_ahead)
    private val ghost: View = root.findViewById(R.id.vc_ghost)
    private val bubble: TextView = root.findViewById(R.id.vc_bubble)
    private val row: View = root.findViewById(R.id.vc_row)
    private val play: ImageView = root.findViewById(R.id.vc_play)
    private val prev: View = root.findViewById(R.id.vc_prev)
    private val next: View = root.findViewById(R.id.vc_next)
    private val episodesButton: View = root.findViewById(R.id.vc_episodes)
    private val back: View = root.findViewById(R.id.vc_back)
    private val forward: View = root.findViewById(R.id.vc_forward)

    /** Settings > Player: jump sizes and how long the controls stay up. */
    private val prefs = Prefs(root.context)
    private val forwardMs get() = prefs.seekForwardSeconds * 1000L
    private val backMs get() = prefs.seekBackSeconds * 1000L
    private val autoHideMs get() = prefs.controlsHideSeconds * 1000L

    private enum class Mode { HIDDEN, TITLE, FULL }
    private var mode = Mode.HIDDEN

    /** Full controls (not just the start-up title) are on screen. */
    val isShown: Boolean get() = mode == Mode.FULL

    /** One of the controls has focus (false right after opening them with the mouse). */
    val hasFocus: Boolean get() = root.hasFocus()

    private val hideRunnable = Runnable { hide() }
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (mode != Mode.HIDDEN) root.postDelayed(this, 500)
        }
    }
    private val hideBubble = Runnable {
        if (!timeline.isFocused) {
            bubble.visibility = View.INVISIBLE
            meta.alpha = 1f
        }
    }

    init {
        prev.setOnClickListener { host.beforeAction(); host.previousEpisode(); poke() }
        (back as? TextView)?.text = "${prefs.seekBackSeconds}s"
        (forward as? TextView)?.text = "${prefs.seekForwardSeconds}s"
        back.contentDescription = "Back ${prefs.seekBackSeconds} seconds"
        forward.contentDescription = "Forward ${prefs.seekForwardSeconds} seconds"
        back.setOnClickListener { host.beforeAction(); host.seekBy(-backMs); poke(); refresh() }
        play.setOnClickListener { host.beforeAction(); host.togglePlay(); poke(); refresh() }
        forward.setOnClickListener { host.beforeAction(); host.seekBy(forwardMs); poke(); refresh() }
        next.setOnClickListener { host.beforeAction(); host.nextEpisode(); poke() }
        episodesButton.setOnClickListener { host.openEpisodes() }
        root.findViewById<View>(R.id.vc_tracks).setOnClickListener { host.openTracks() }
        root.findViewById<View>(R.id.vc_settings).setOnClickListener { host.openSettings() }
        root.findViewById<View>(R.id.vc_versions).setOnClickListener { host.openVersions() }

        val rowGroup = row as android.view.ViewGroup

        // Timeline: focus highlight, Left/Right seek with a time bubble, Up/Down leave it.
        timeline.setOnFocusChangeListener { _, hasFocus ->
            thumb.setBackgroundResource(if (hasFocus) R.drawable.vc_dot_focused else R.drawable.vc_dot)
            thumb.scaleX = if (hasFocus) 1.4f else 1f
            thumb.scaleY = if (hasFocus) 1.4f else 1f
            if (hasFocus) {
                showBubble(host.positionMs())
            } else {
                endScrub()
                bubble.visibility = View.INVISIBLE
                meta.alpha = 1f
            }
        }
        // 16.3: on the timeline a press moves 1 minute and a hold grows the step (1, 2, then 5
        // minutes, never more than about a twentieth of the video). The marker and the time label
        // move at once; the video jumps once, on release, to the time shown (a weak TV would
        // reload the stream for every step otherwise). The normal skip elsewhere keeps Settings' jump.
        timeline.setOnKeyListener { _, keyCode, event ->
            val sign = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                else -> 0
            }
            if (sign == 0) return@setOnKeyListener false
            val p = host.player
            val duration = p?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
            if (p == null || duration == null) {
                // No known length: the old fixed step, at once.
                if (event.action == KeyEvent.ACTION_DOWN) {
                    host.seekBy(sign * TIMELINE_STEP_MS)
                    poke()
                    refresh()
                    showBubble(host.positionMs())
                }
                return@setOnKeyListener true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                val now = android.os.SystemClock.uptimeMillis()
                // Held: four steps a second, however fast the remote repeats.
                if (event.repeatCount > 0 && now - scrubStepAt < 250) return@setOnKeyListener true
                scrubStepAt = now
                if (scrubTarget < 0) {
                    scrubFrom = p.currentPosition.coerceIn(0, duration)
                    scrubTarget = host.positionMs()
                }
                scrubTarget = (scrubTarget + sign * scrubStep(event.eventTime - event.downTime, duration)).coerceIn(0, duration - 1_000)
                poke()
                showScrub(duration)
            } else if (event.action == KeyEvent.ACTION_UP && scrubTarget >= 0) {
                val target = scrubTarget
                endScrub()
                host.seekBy(target - host.positionMs())
                poke()
                refresh()
                showBubble(target)
            }
            true
        }
        played.pivotX = 0f
        buffered.pivotX = 0f
        played.scaleX = 0f
        buffered.scaleX = 0f
        timeline.nextFocusDownId = R.id.vc_play
        rowGroup.let { for (i in 0 until it.childCount) it.getChildAt(i).nextFocusUpId = R.id.vc_timeline }
    }

    /** Titles: series name + episode, or movie name. Episode buttons only for series. */
    fun bind(titleText: String, episodeText: String?, hasEpisodes: Boolean, hasPrevious: Boolean, hasNext: Boolean, upNextText: String?) {
        title.text = titleText
        episode.text = episodeText ?: ""
        episode.visibility = if (episodeText.isNullOrBlank()) View.GONE else View.VISIBLE
        prev.visibility = if (hasEpisodes) View.VISIBLE else View.GONE
        next.visibility = if (hasEpisodes) View.VISIBLE else View.GONE
        episodesButton.visibility = if (hasEpisodes) View.VISIBLE else View.GONE
        prev.isEnabled = hasPrevious
        prev.alpha = if (hasPrevious) 1f else 0.35f
        next.isEnabled = hasNext
        next.alpha = if (hasNext) 1f else 0.35f
        upNextTitle.text = upNextText ?: ""
        upNext.visibility = if (upNextText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** Full controls with focus on Pause (or keep the current focus if already open). */
    val playFocused: Boolean get() = play.isFocused

    /** The same as pressing Play/Pause on screen. */
    fun pressPlay() {
        play.performClick()
    }

    fun show() {
        val wasShown = mode == Mode.FULL
        setMode(Mode.FULL)
        if (!wasShown || !row.hasFocus() && !timeline.hasFocus()) play.requestFocus()
        poke()
    }

    /** Up from the buttons goes to the timeline, Down from the timeline back to Pause. */
    fun moveFocus(keyCode: Int): Boolean {
        if (mode != Mode.FULL) return false
        return when {
            keyCode == KeyEvent.KEYCODE_DPAD_UP && row.hasFocus() -> timeline.requestFocus()
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && timeline.hasFocus() -> play.requestFocus()
            keyCode == KeyEvent.KEYCODE_DPAD_UP && timeline.hasFocus() -> true
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && row.hasFocus() -> true
            else -> false
        }
    }

    /** Only the title and clock, for a few seconds when a video starts. */
    fun showTitleOnly() {
        if (mode == Mode.FULL) return
        setMode(Mode.TITLE)
        root.removeCallbacks(hideRunnable)
        root.postDelayed(hideRunnable, TITLE_ONLY_MS)
    }

    /** At once, no fade (going into the mini player: nothing may be left over the picture). */
    fun hideNow() {
        hide()
        root.animate().cancel()
        root.alpha = 0f
        root.visibility = View.GONE
    }

    fun hide() {
        if (mode == Mode.HIDDEN) return
        val hadFocus = root.hasFocus()
        mode = Mode.HIDDEN
        root.removeCallbacks(hideRunnable)
        root.removeCallbacks(ticker)
        bubble.visibility = View.INVISIBLE
        endScrub()
        meta.alpha = 1f
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(FADE_MS).withLayer().withEndAction {
            if (mode == Mode.HIDDEN) root.visibility = View.GONE
        }.start()
        if (hadFocus) host.onControlsHidden()
    }

    /** While a pop-up from the controls is open (settings menu): don't hide. */
    private var held = false

    fun hold(on: Boolean) {
        held = on
        root.removeCallbacks(hideRunnable)
        if (!on) poke()
    }

    /** Any key while the controls are open: start the hide countdown again. */
    fun poke() {
        if (mode != Mode.FULL || held) return
        root.removeCallbacks(hideRunnable)
        root.postDelayed(hideRunnable, autoHideMs)
    }

    private fun setMode(newMode: Mode) {
        mode = newMode
        val full = newMode == Mode.FULL
        bottom.visibility = if (full) View.VISIBLE else View.GONE
        shadeBottom.visibility = if (full) View.VISIBLE else View.GONE
        root.animate().cancel()
        if (root.visibility != View.VISIBLE) {
            root.alpha = 0f
            root.visibility = View.VISIBLE
        }
        root.animate().alpha(1f).setDuration(FADE_MS).withLayer().start()
        root.removeCallbacks(ticker)
        root.post(ticker)
    }

    // ---- Timeline scrubbing (16.3) ----

    /** Where the video will jump on release; -1 when no press or hold is in progress. */
    private var scrubTarget = -1L
    /** Where the video really was when the press or hold began. */
    private var scrubFrom = 0L
    private var scrubStepAt = 0L

    /** The step for a key held [heldMs]: 1 min, 2 min from 1.5 s, 5 min from 3 s; smaller for short videos. */
    private fun scrubStep(heldMs: Long, duration: Long): Long {
        val tier = when {
            heldMs < 1_500 -> 60_000L
            heldMs < 3_000 -> 120_000L
            else -> 300_000L
        }
        val cap = if (duration < 10 * 60_000L) (duration / 10).coerceAtLeast(5_000L) else maxOf(60_000L, duration / 20 / 30_000L * 30_000L)
        return minOf(tier, cap)
    }

    /** The marker, the lighter stretch and the label ("1:05:10  ·  +24 min") at the target. */
    private fun showScrub(duration: Long) {
        val width = timeline.width
        if (width <= 0) return
        val fromF = (scrubFrom.toFloat() / duration).coerceIn(0f, 1f)
        val toF = (scrubTarget.toFloat() / duration).coerceIn(0f, 1f)
        thumb.translationX = width * toF - thumb.width / 2f
        ahead.pivotX = 0f
        ahead.translationX = width * minOf(fromF, toF)
        ahead.scaleX = kotlin.math.abs(toF - fromF)
        ahead.visibility = View.VISIBLE
        ghost.translationX = width * fromF - ghost.width / 2f
        ghost.visibility = View.VISIBLE
        val diff = scrubTarget - scrubFrom
        val amount = kotlin.math.abs(diff).let { if (it < 60_000) "${it / 1000} s" else "${(it + 30_000) / 60_000} min" }
        bubble.text = "${formatDuration(scrubTarget)}  ·  ${if (diff >= 0) "+" else "−"}$amount"
        bubble.visibility = View.VISIBLE
        meta.alpha = 0f
        root.removeCallbacks(hideBubble)
        bubble.post {
            val x = width * toF
            bubble.translationX = (x - bubble.width / 2f).coerceIn(-bubble.width / 2f, width - bubble.width / 2f)
        }
    }

    private fun endScrub() {
        scrubTarget = -1
        ahead.visibility = View.INVISIBLE
        ghost.visibility = View.INVISIBLE
    }

    private fun showBubble(positionMs: Long) {
        val p = host.player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        bubble.text = formatDuration(positionMs.coerceIn(0, duration))
        bubble.visibility = View.VISIBLE
        meta.alpha = 0f // the bubble sits where the "Ends at" line is
        bubble.post {
            val x = timeline.width * (positionMs.toFloat() / duration).coerceIn(0f, 1f)
            bubble.translationX = (x - bubble.width / 2f).coerceIn(-bubble.width / 2f, timeline.width - bubble.width / 2f)
        }
        root.removeCallbacks(hideBubble)
        root.postDelayed(hideBubble, 1_500)
    }

    private var shownPlaying: Boolean? = null

    /** The copy's language, shown before the quality under the title; null when there is only one copy. */
    var language: String? = null

    /** The Versions button shows only for titles with more than one copy. */
    fun showVersions(show: Boolean) {
        root.findViewById<View>(R.id.vc_versions).visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Clock, quality, times, timeline and the play/pause icon. Runs twice a second over the
     * video, so it only touches views whose content changed and never triggers a layout pass
     * for the bars (they scale instead of resizing), which would make the video stutter.
     */
    fun refresh() {
        setIfChanged(clock, CLOCK.format(Date()))
        val p = host.player ?: return
        // "English · 1080p" when the title has copies in other languages; the quality alone otherwise.
        val q = listOfNotNull(language, qualityLabel(p.videoSize.height).ifEmpty { null }).joinToString(" · ")
        setIfChanged(quality, q)
        val qVisibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        if (quality.visibility != qVisibility) quality.visibility = qVisibility
        if (mode != Mode.FULL) return

        val pos = host.positionMs().coerceAtLeast(0)
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        setIfChanged(position, formatDuration(pos))
        setIfChanged(remaining, if (duration != null) "−" + formatDuration((duration - pos).coerceAtLeast(0)) else "")
        setIfChanged(meta, if (duration != null) {
            val speed = p.playbackParameters.speed.takeIf { it > 0f } ?: 1f
            val endsAt = System.currentTimeMillis() + ((duration - pos) / speed).toLong()
            "${durationWords(duration)}   ·   Ends at ${CLOCK.format(Date(endsAt))}"
        } else {
            ""
        })
        val width = timeline.width
        if (width > 0 && duration != null) {
            val playedF = (pos.toFloat() / duration).coerceIn(0f, 1f)
            played.scaleX = playedF
            buffered.scaleX = (p.bufferedPosition.toFloat() / duration).coerceIn(0f, 1f)
            // While Left/Right is pressed or held, the marker shows the target (showScrub).
            if (scrubTarget < 0) thumb.translationX = width * playedF - thumb.width / 2f
        }
        val playing = p.playWhenReady
        if (playing != shownPlaying) {
            shownPlaying = playing
            play.setImageResource(if (playing) R.drawable.ic_vc_pause else R.drawable.ic_vc_play)
            play.contentDescription = if (playing) "Pause" else "Play"
        }
    }

    private fun setIfChanged(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun qualityLabel(height: Int): String = when {
        height <= 0 -> ""
        height >= 2000 -> "4K"
        height >= 1400 -> "1440p"
        height >= 1000 -> "1080p"
        height >= 700 -> "720p"
        height >= 560 -> "576p"
        else -> "${height}p"
    }

    private fun durationWords(ms: Long): String {
        val minutes = (ms / 60_000).toInt()
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
    }

    fun release() {
        root.removeCallbacks(hideRunnable)
        root.removeCallbacks(ticker)
        root.removeCallbacks(hideBubble)
    }
}
