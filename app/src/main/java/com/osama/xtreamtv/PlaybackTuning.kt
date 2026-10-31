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
import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Settings > Player: decoder choice, buffer size, and matching the TV's refresh rate to the
 * video (smooth motion, no judder on 24/25 fps content).
 */
@UnstableApi
object PlaybackTuning {

    /**
     * The server sends each stream on to one of several stream servers, and some of them may not
     * answer. When a connection cannot be opened, ask again at once (the server usually picks
     * another one) instead of waiting seconds between tries, up to [openRetries] times, then give up; other
     * errors keep [minRetries].
     */
    class QuickRetry(private val minRetries: Int, private val openRetries: Int = 2) : DefaultLoadErrorHandlingPolicy(minRetries) {
        private fun cannotConnect(info: LoadErrorHandlingPolicy.LoadErrorInfo): Boolean {
            val e = info.exception
            return e is HttpDataSource.HttpDataSourceException && e.type == HttpDataSource.HttpDataSourceException.TYPE_OPEN &&
                e !is HttpDataSource.InvalidResponseCodeException
        }

        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (cannotConnect(info)) return if (info.errorCount <= openRetries) 100 else C.TIME_UNSET
            val delay = super.getRetryDelayMsFor(info)
            return if (delay == C.TIME_UNSET) delay else minOf(delay, 1_000)
        }

        override fun getMinimumLoadableRetryCount(dataType: Int) = maxOf(minRetries, openRetries)
    }

    /**
     * Video connections: IPv4 first. Some providers answer IPv6 with "blocked" while the same
     * server streams fine over IPv4, and TVs on an IPv6 network would otherwise always pick IPv6.
     * OkHttp falls back to the next address (IPv6 too) when one can't be reached.
     */
    private val ipv4First = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            okhttp3.Dns.SYSTEM.lookup(hostname).sortedBy { it is java.net.Inet6Address }
    }

    private val baseClient by lazy {
        okhttp3.OkHttpClient.Builder().dns(ipv4First).followRedirects(true).followSslRedirects(true).build()
    }

    /** For looking at what a stream URL really returns. */
    val probeClient: okhttp3.OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun http(connectTimeoutMs: Long, readTimeoutMs: Long): androidx.media3.datasource.DataSource.Factory =
        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            baseClient.newBuilder()
                .connectTimeout(connectTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build(),
        ).setUserAgent(USER_AGENT)

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
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
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
