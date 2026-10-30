package com.osama.xtreamtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Backup = every saved setting as JSON: playlists (with passwords), favorites,
 * Continue watching, watched marks and player settings, for each playlist.
 */
object Backup {

    const val FILE_NAME = "xtream-tv-backup.json"
    private const val GLOBAL = "xtream"
    private val PLAYLIST_FILE = Regex("^playlist_[A-Za-z0-9_-]+$")

    /** Used when the TV has no file picker: the app's own folder (no permission needed). */
    fun fallbackFile(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    fun export(context: Context): String {
        val prefs = Prefs(context)
        val names = listOf(GLOBAL) + prefs.playlists().map { "playlist_${it.id}" }
        val files = JSONObject()
        for (name in names) {
            val entries = JSONObject()
            for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val typed = when (value) {
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                    else -> null
                }
                if (typed != null) entries.put(key, typed)
            }
            files.put(name, entries)
        }
        return JSONObject()
            .put("app", "xtream-tv-player")
            .put("version", 1)
            .put("created", System.currentTimeMillis())
            .put("files", files)
            .toString(2)
    }

    /** Replaces the current playlists and settings with the backup. Returns the number of playlists. */
    fun import(context: Context, text: String): Int {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("This isn't an Xtream TV backup file")
        }
        if (root.optString("app") != "xtream-tv-player") throw IOException("This isn't an Xtream TV backup file")
        val files = root.optJSONObject("files") ?: throw IOException("The backup is empty")
        val names = files.keys().asSequence().filter { it == GLOBAL || PLAYLIST_FILE.matches(it) }.toList()
        if (GLOBAL !in names) throw IOException("The backup has no playlists")

        // Remove the current per-playlist data first so nothing stale is left behind.
        for (p in Prefs(context).playlists()) {
            context.getSharedPreferences("playlist_${p.id}", Context.MODE_PRIVATE).edit().clear().commit()
        }
        for (name in names) {
            val entries = files.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (key in entries.keys()) {
                val typed = entries.optJSONObject(key) ?: continue
                when (typed.optString("t")) {
                    "s" -> editor.putString(key, typed.optString("v"))
                    "b" -> editor.putBoolean(key, typed.optBoolean("v"))
                    "i" -> editor.putInt(key, typed.optInt("v"))
                    "l" -> editor.putLong(key, typed.optLong("v"))
                    "f" -> editor.putFloat(key, typed.optDouble("v").toFloat())
                    "set" -> {
                        val arr = typed.optJSONArray("v") ?: JSONArray()
                        editor.putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet())
                    }
                }
            }
            editor.commit()
        }
        return Prefs(context).playlists().size
    }
}
