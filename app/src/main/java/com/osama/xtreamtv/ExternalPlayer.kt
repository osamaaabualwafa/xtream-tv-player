package com.osama.xtreamtv

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Movies and episodes in another player app (Settings > Player > Play movies and series in). */
object ExternalPlayer {

    /** Choice number -> name and the app packages that provide it (first installed one is used). */
    val PLAYERS = listOf(
        "This app" to emptyList(),
        "VLC" to listOf("org.videolan.vlc"),
        "MX Player" to listOf("com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad"),
        "Just Player" to listOf("com.brouken.player"),
    )

    fun installedPackage(context: Context, choice: Int): String? =
        PLAYERS.getOrNull(choice)?.second?.firstOrNull { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }

    /** Opens [url] in the chosen player, at [positionMs]. False when that player isn't installed. */
    fun open(context: Context, choice: Int, url: String, title: String, positionMs: Long): Boolean {
        val pkg = installedPackage(context, choice) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .setPackage(pkg)
            .putExtra("title", title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // VLC reads a Long, MX Player an Int (milliseconds).
        if (positionMs > 0) {
            if (pkg.startsWith("org.videolan")) intent.putExtra("position", positionMs) else intent.putExtra("position", positionMs.toInt())
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
