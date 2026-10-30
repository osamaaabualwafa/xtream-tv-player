package com.osama.xtreamtv

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Settings > Player: decoder choice, buffer size, and matching the TV's refresh rate to the
 * video (smooth motion, no judder on 24/25 fps content).
 */
@UnstableApi
object PlaybackTuning {

    val DECODER_NAMES = listOf("Automatic (hardware)", "Prefer software")
    val BUFFER_NAMES = listOf("Small (starts fastest)", "Normal", "Large (for unstable connections)")

    /** Hardware decoders first (with fallback to the next one), or software first when chosen. */
    fun renderers(context: Context, preferSoftware: Boolean): RenderersFactory {
        val selector = MediaCodecSelector { mimeType, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            if (preferSoftware) all.sortedBy { !it.softwareOnly } else all
        }
        return DefaultRenderersFactory(context)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(true)
    }

    /** How much video is kept ready: small starts quickly, large rides out a shaky connection. */
    fun loadControl(size: Int): LoadControl = when (size) {
        0 -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 25_000, 1_000, 2_000)
            .build()
        2 -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 150_000, 3_000, 8_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        else -> DefaultLoadControl.Builder().build()
    }

    // ---- Match frame rate ----

    /**
     * Switch the TV to the refresh rate that fits [fps] (24 → 24 Hz, 25 → 50 Hz, 30 → 60 Hz…),
     * keeping its resolution. Returns true when a different mode was requested.
     */
    fun matchFrameRate(activity: Activity, fps: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || fps <= 1f) return false
        val display = activity.window.decorView.display ?: return false
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val best = pick(candidates, fps) ?: return false
        if (best.modeId == current.modeId) return false
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = best.modeId
        activity.window.attributes = attrs
        return true
    }

    /** Lowest refresh rate that is a whole multiple of the frame rate (within 1 %), at least 23 Hz. */
    private fun pick(modes: List<Display.Mode>, fps: Float): Display.Mode? = modes
        .filter { it.refreshRate >= 23f }
        .mapNotNull { mode ->
            val ratio = mode.refreshRate / fps
            val whole = Math.round(ratio)
            if (whole >= 1 && Math.abs(ratio - whole) / whole < 0.01f) mode to whole else null
        }
        .minByOrNull { it.second }
        ?.first

    /** Back to the TV's own refresh rate when the player closes. */
    fun restoreFrameRate(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val attrs = activity.window.attributes
        if (attrs.preferredDisplayModeId == 0) return
        attrs.preferredDisplayModeId = 0
        activity.window.attributes = attrs
    }
}
