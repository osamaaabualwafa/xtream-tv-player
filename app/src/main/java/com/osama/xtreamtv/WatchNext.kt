package com.osama.xtreamtv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

/**
 * Continue watching on the Android TV home screen ("Play next" row). Each entry opens the app
 * straight into the player at the saved position. Android 8+ only; failures are ignored (some
 * launchers don't offer the row).
 */
object WatchNext {

    const val EXTRA_RESUME_KEY = "resume_key"
    const val EXTRA_RESUME_PLAYLIST = "resume_playlist"

    private fun supported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private fun idKey(playlistId: String, key: String) = "watch_next_${playlistId}_$key"

    /** Add or update the row entry for [entry] of playlist [playlistId]. */
    fun update(context: Context, playlistId: String, entry: WatchEntry) {
        if (!supported()) return
        try {
            val prefs = context.getSharedPreferences("xtream", Context.MODE_PRIVATE)
            val open = Intent(context, PlaylistsActivity::class.java)
                .putExtra(EXTRA_RESUME_PLAYLIST, playlistId)
                .putExtra(EXTRA_RESUME_KEY, entry.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val builder = WatchNextProgram.Builder()
                .setType(if (entry.type == ContentType.SERIES) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(entry.updated)
                .setTitle(entry.title)
                .setLastPlaybackPositionMillis(entry.position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setIntentUri(Uri.parse(open.toUri(Intent.URI_INTENT_SCHEME)))
                .setInternalProviderId("$playlistId/${entry.key}")
            entry.subtitle?.let { builder.setEpisodeTitle(it) }
            if (entry.duration > 0) builder.setDurationMillis(entry.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            entry.image?.let {
                builder.setPosterArtUri(Uri.parse(it))
                builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
            }
            val values = builder.build().toContentValues()
            val resolver = context.contentResolver
            val saved = prefs.getLong(idKey(playlistId, entry.key), -1L)
            if (saved >= 0 && resolver.update(TvContractCompat.buildWatchNextProgramUri(saved), values, null, null) > 0) return
            val uri = resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) ?: return
            prefs.edit().putLong(idKey(playlistId, entry.key), ContentUris.parseId(uri)).apply()
        } catch (e: Exception) {
            // No TV provider / not allowed on this device: the row just isn't offered.
        }
    }

    /** Take [key] of playlist [playlistId] off the row (finished, removed, or replaced by the next episode). */
    fun remove(context: Context, playlistId: String, key: String) {
        if (!supported()) return
        try {
            val prefs = context.getSharedPreferences("xtream", Context.MODE_PRIVATE)
            val saved = prefs.getLong(idKey(playlistId, key), -1L)
            if (saved < 0) return
            context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(saved), null, null)
            prefs.edit().remove(idKey(playlistId, key)).apply()
        } catch (e: Exception) {
            // Ignore: see update().
        }
    }
}
