package com.osama.xtreamtv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved Xtream login. Each playlist keeps its own favorites, progress and settings. */
data class Playlist(val id: String, val name: String, val base: String, val username: String, val password: String) {
    fun toAccount() = Account(base, username, password)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("base", base).put("user", username).put("pass", password)
}

fun playlistFromJson(o: JSONObject): Playlist? {
    val id = o.str("id") ?: return null
    val base = o.str("base") ?: return null
    val user = o.str("user") ?: return null
    return Playlist(id, o.str("name") ?: base.substringAfter("://"), base, user, o.optString("pass", ""))
}

class Prefs(context: Context) {
    private val app = context.applicationContext
    private val global = app.getSharedPreferences("xtream", Context.MODE_PRIVATE)

    companion object {
        // The single login from before playlists existed keeps its data in the original file.
        private const val LEGACY_ID = "legacy"
        private val DATA_PREFIXES = listOf("favorites_", "continue", "pos_", "vod_hls", "formats")
    }

    /** Per-playlist storage for the active playlist. */
    private val sp get() = dataFile(activeId)

    private fun dataFile(id: String?) =
        if (id == null || id == LEGACY_ID) global else app.getSharedPreferences("playlist_$id", Context.MODE_PRIVATE)

    fun playlists(): List<Playlist> {
        migrateLegacyLogin()
        val text = global.getString("playlists", null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::playlistFromJson) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePlaylist(p: Playlist) {
        val list = playlists().toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        writePlaylists(list)
    }

    fun deletePlaylist(id: String) {
        writePlaylists(playlists().filter { it.id != id })
        if (activeId == id) activeId = null
        if (id == LEGACY_ID) {
            val e = global.edit()
            global.all.keys.filter { k -> DATA_PREFIXES.any { k.startsWith(it) } }.forEach { e.remove(it) }
            e.apply()
        } else {
            dataFile(id).edit().clear().apply()
        }
    }

    var activeId: String?
        get() = global.getString("active", null)
        set(value) = global.edit().putString("active", value).apply()

    val activePlaylist: Playlist? get() = activeId?.let { id -> playlists().firstOrNull { it.id == id } }

    val account: Account? get() = activePlaylist?.toAccount()

    private fun writePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        global.edit().putString("playlists", arr.toString()).apply()
    }

    private fun migrateLegacyLogin() {
        if (global.contains("playlists")) return
        val base = global.getString("base", null)
        val user = global.getString("user", null)
        val pass = global.getString("pass", null)
        if (base == null || user == null || pass == null) return
        writePlaylists(listOf(Playlist(LEGACY_ID, base.substringAfter("://"), base, user, pass)))
        global.edit().putString("active", LEGACY_ID).remove("base").remove("user").remove("pass").apply()
    }

    /** Comma-separated allowed_output_formats from the last login. */
    var formats: String
        get() = sp.getString("formats", "") ?: ""
        set(value) = sp.edit().putString("formats", value).apply()

    /** Audio language picked last time (ISO code), applied to the next videos. */
    // Settings > appearance (whole app, all playlists).
    var homeStyle: Int
        get() = global.getInt("home_style", Appearance.HOME_TILES)
        set(value) = global.edit().putInt("home_style", value).apply()

    var browseStyle: Int
        get() = global.getInt("browse_style", Appearance.BROWSE_SIDEBAR)
        set(value) = global.edit().putInt("browse_style", value).apply()

    var accentIndex: Int
        get() = global.getInt("accent", 0)
        set(value) = global.edit().putInt("accent", value).apply()

    /** Playlist avatar: an emoji, or null for the first letter of the name. */
    fun avatar(playlistId: String): String? = global.getString("avatar_$playlistId", null)

    /** Index into [Appearance.ACCENT_COLORS] for the avatar circle, or -1 for the automatic colour. */
    fun avatarColor(playlistId: String): Int = global.getInt("avatar_color_$playlistId", -1)

    fun setAvatar(playlistId: String, emoji: String?, colorIndex: Int) {
        val e = global.edit()
        if (emoji == null) e.remove("avatar_$playlistId") else e.putString("avatar_$playlistId", emoji)
        if (colorIndex < 0) e.remove("avatar_color_$playlistId") else e.putInt("avatar_color_$playlistId", colorIndex)
        e.apply()
    }

    var audioLanguage: String?
        get() = global.getString("audio_lang", null)
        set(value) = global.edit().putString("audio_lang", value).apply()

    /** Subtitle language picked last time, or SUBTITLES_OFF. */
    var subtitleLanguage: String?
        get() = global.getString("sub_lang", null)
        set(value) = global.edit().putString("sub_lang", value).apply()

    /** Subtitle size index: 0 small, 1 medium, 2 large, 3 extra large. */
    var subtitleSize: Int
        get() = global.getInt("sub_size", 1)
        set(value) = global.edit().putInt("sub_size", value).apply()

    /** Dark box behind subtitles for readability. */
    var subtitleBackground: Boolean
        get() = global.getBoolean("sub_bg", false)
        set(value) = global.edit().putBoolean("sub_bg", value).apply()

    // Settings > Player.
    /** Seconds for Right / ⏩ (forward) and Left / ⏪ (back). */
    var seekForwardSeconds: Int
        get() = global.getInt("seek_fwd", 10)
        set(value) = global.edit().putInt("seek_fwd", value).apply()

    var seekBackSeconds: Int
        get() = global.getInt("seek_back", 5)
        set(value) = global.edit().putInt("seek_back", value).apply()

    /** With a saved position: 0 ask, 1 resume, 2 start over. */
    var resumeMode: Int
        get() = global.getInt("resume_mode", 0)
        set(value) = global.edit().putInt("resume_mode", value).apply()

    /** Series: carry on with the next episode (prompt [nextCountdownSeconds] before the end). */
    var autoNext: Boolean
        get() = global.getBoolean("auto_next", true)
        set(value) = global.edit().putBoolean("auto_next", value).apply()

    var nextCountdownSeconds: Int
        get() = global.getInt("next_countdown", 30)
        set(value) = global.edit().putInt("next_countdown", value).apply()

    /** Player controls hide after this many seconds without a key. */
    var controlsHideSeconds: Int
        get() = global.getInt("controls_hide", 5)
        set(value) = global.edit().putInt("controls_hide", value).apply()

    /** Live TV list: muted preview of the highlighted channel. */
    var channelPreview: Boolean
        get() = global.getBoolean("channel_preview", true)
        set(value) = global.edit().putBoolean("channel_preview", value).apply()

    /** Movies and episodes play in: 0 this app, otherwise an installed player from [ExternalPlayer.PLAYERS]. */
    var vodPlayer: Int
        get() = global.getInt("vod_player", 0)
        set(value) = global.edit().putInt("vod_player", value).apply()

    /** Episodes list in the player: 0 glass side panel, 1 bottom shelf. */
    var episodesStyle: Int
        get() = global.getInt("episodes_style", 0)
        set(value) = global.edit().putInt("episodes_style", value).apply()

    /** Settings / options menu in the player: 0 small pop-up, 1 side panel. */
    var playerMenuStyle: Int
        get() = global.getInt("player_menu_style", 0)
        set(value) = global.edit().putInt("player_menu_style", value).apply()

    /** Back from the player keeps it playing in a corner (picture-in-picture) instead of closing. */
    var miniPlayer: Boolean
        get() = global.getBoolean("mini_player", true)
        set(value) = global.edit().putBoolean("mini_player", value).apply()

    /** Poster, title and clock after a minute paused. */
    var pauseScreensaver: Boolean
        get() = global.getBoolean("pause_screensaver", true)
        set(value) = global.edit().putBoolean("pause_screensaver", value).apply()

    /** Switch the TV's refresh rate to the video's frame rate (smoother motion). */
    var matchFrameRate: Boolean
        get() = global.getBoolean("match_frame_rate", false)
        set(value) = global.edit().putBoolean("match_frame_rate", value).apply()

    /** 0 automatic (hardware decoder first), 1 prefer software decoders. */
    var decoderMode: Int
        get() = global.getInt("decoder_mode", 0)
        set(value) = global.edit().putInt("decoder_mode", value).apply()

    /** 0 small, 1 normal, 2 large. */
    var bufferSize: Int
        get() = global.getInt("buffer_size", 1)
        set(value) = global.edit().putInt("buffer_size", value).apply()

    // Settings > Look.
    var backgroundStyle: Int
        get() = global.getInt("background_style", 0)
        set(value) = global.edit().putInt("background_style", value).apply()

    /** 0 small, 1 normal, 2 large, 3 extra large. */
    var textSize: Int
        get() = global.getInt("text_size", 1)
        set(value) = global.edit().putInt("text_size", value).apply()

    /** Background takes the colours of the highlighted poster. */
    var backdropFollows: Boolean
        get() = global.getBoolean("backdrop_follows", true)
        set(value) = global.edit().putBoolean("backdrop_follows", value).apply()

    /** Highlighted poster rises with a glow, the others dim. */
    var posterGlow: Boolean
        get() = global.getBoolean("poster_glow", true)
        set(value) = global.edit().putBoolean("poster_glow", value).apply()

    /** Rotating banner at the top of Home. */
    var featuredBanner: Boolean
        get() = global.getBoolean("featured_banner", true)
        set(value) = global.edit().putBoolean("featured_banner", value).apply()

    /** Live stream type: 0 automatic (what the server offers), 1 HLS (m3u8), 2 MPEG-TS. */
    var streamType: Int
        get() = global.getInt("stream_type", 0)
        set(value) = global.edit().putInt("stream_type", value).apply()

    /** Live channels as HLS (m3u8) rather than TS, from Settings or the server's formats. */
    fun useHls(): Boolean = when (streamType) {
        1 -> true
        2 -> false
        else -> formats.split(',').map { it.trim() }.filter { it.isNotEmpty() }.let { it.isEmpty() || it.contains("m3u8") }
    }

    /** Picture size index: 0 fit, 1 zoom, 2 stretch. */
    var pictureSize: Int
        get() = global.getInt("picture_size", 0)
        set(value) = global.edit().putInt("picture_size", value).apply()

    fun isWatched(key: String): Boolean = sp.getStringSet("watched", emptySet())?.contains(key) == true

    fun setWatched(key: String, watched: Boolean) {
        val set = HashSet(sp.getStringSet("watched", emptySet()) ?: emptySet())
        if (watched) set.add(key) else set.remove(key)
        sp.edit().putStringSet("watched", set).apply()
    }

    /** Learned per provider: movies/episodes are HLS playlists despite their .mkv/.mp4 names. */
    var vodIsHls: Boolean
        get() = sp.getBoolean("vod_hls", false)
        set(value) = sp.edit().putBoolean("vod_hls", value).apply()

    // Favorites keep the whole item so the list shows without downloading the full catalogue.
    fun favorites(type: ContentType): List<StreamItem> = readList("favorites_${type.name}").mapNotNull { streamItemFromJson(it) }

    /** When the user last looked at a followed series (provider "last_modified" seconds). */
    fun seriesSeen(seriesId: String): Long? = sp.getLong("series_seen_$seriesId", -1L).takeIf { it >= 0 }

    fun markSeriesSeen(seriesId: String, seconds: Long) = sp.edit().putLong("series_seen_$seriesId", seconds).apply()

    /** Series in favorites or Continue watching. */
    fun followedSeriesIds(): Set<String> =
        favoriteIds(ContentType.SERIES) + continueWatching(ContentType.SERIES).mapNotNull { it.seriesId }

    /**
     * Followed series the provider changed since the user last opened them ("NEW" badge).
     * A series seen for the first time is just remembered, so nothing old shows as new.
     */
    fun newEpisodeSeries(all: List<StreamItem>): Set<String> {
        val followed = followedSeriesIds()
        val result = HashSet<String>()
        for (item in all) {
            if (item.id !in followed || item.added <= 0) continue
            val seen = seriesSeen(item.id)
            if (seen == null) markSeriesSeen(item.id, item.added) else if (item.added > seen) result.add(item.id)
        }
        return result
    }

    fun favoriteIds(type: ContentType): Set<String> = favorites(type).mapTo(HashSet()) { it.id }

    /** Returns true when the item is now a favorite. */
    fun toggleFavorite(item: StreamItem): Boolean {
        val list = favorites(item.type).toMutableList()
        val existing = list.indexOfFirst { it.id == item.id }
        val added = existing < 0
        if (added) list.add(0, item.copy(note = null)) else list.removeAt(existing)
        writeList("favorites_${item.type.name}", list.map { it.toJson() })
        return added
    }

    /** Categories moved by the user (ids, first = top); the others follow in the server's order. */
    fun categoryOrder(type: ContentType): List<String> =
        sp.getString("cat_order_${type.name}", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    fun setCategoryOrder(type: ContentType, ids: List<String>) =
        sp.edit().putString("cat_order_${type.name}", ids.joinToString("\n")).apply()

    fun hiddenCategories(type: ContentType): Set<String> =
        sp.getStringSet("cat_hidden_${type.name}", null)?.toSet() ?: emptySet()

    fun setHiddenCategories(type: ContentType, ids: Set<String>) =
        sp.edit().putStringSet("cat_hidden_${type.name}", ids).apply()

    fun continueWatching(type: ContentType): List<WatchEntry> =
        readList("continue").mapNotNull { watchEntryFromJson(it) }.filter { it.type == type }.sortedByDescending { it.updated }

    fun saveProgress(entry: WatchEntry) {
        // One entry per series: the episode watched last replaces the others.
        val before = readList("continue").mapNotNull { watchEntryFromJson(it) }
        val all = before.filter { it.key != entry.key && (entry.seriesId == null || it.seriesId != entry.seriesId) }
        val kept = (listOf(entry) + all).take(40)
        writeList("continue", kept.map { it.toJson() })
        // Same list on the TV home screen ("Play next").
        val playlist = activeId ?: LEGACY_ID
        before.filter { old -> kept.none { it.key == old.key } }.forEach { WatchNext.remove(app, playlist, it.key) }
        WatchNext.update(app, playlist, entry)
    }

    fun removeProgress(key: String) {
        val all = readList("continue").mapNotNull { watchEntryFromJson(it) }.filter { it.key != key }
        writeList("continue", all.map { it.toJson() })
        savePosition(key, 0)
        WatchNext.remove(app, activeId ?: LEGACY_ID, key)
    }

    private fun readList(key: String): List<JSONObject> {
        val text = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(key: String, items: List<JSONObject>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    fun position(key: String): Long = sp.getLong("pos_$key", 0L)

    fun savePosition(key: String, positionMs: Long) {
        val e = sp.edit()
        if (positionMs > 0) e.putLong("pos_$key", positionMs) else e.remove("pos_$key")
        e.apply()
    }
}

/** A partly watched movie (type MOVIE) or episode (type SERIES) for "Continue watching". */
data class WatchEntry(
    val key: String,
    val type: ContentType,
    val id: String,
    val ext: String?,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val position: Long = 0,
    val duration: Long = 0,
    val updated: Long = 0,
    /** For episodes: lets the player offer next/previous and the episode list. */
    val seriesId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("type", type.name).put("id", id).put("ext", ext)
        .put("title", title).put("subtitle", subtitle).put("image", image)
        .put("position", position).put("duration", duration).put("updated", updated)
        .put("series", seriesId)

    /** Short enough for a poster: "21 min left" (the bar on the poster shows how far in). */
    fun progressText(): String {
        if (duration <= 0) return "▶ ${formatDuration(position)}"
        val minutesLeft = ((duration - position).coerceAtLeast(0) + 59_999) / 60_000
        return when {
            minutesLeft < 1 -> "Almost done"
            minutesLeft < 60 -> "$minutesLeft min left"
            else -> "${minutesLeft / 60} h ${minutesLeft % 60} min left"
        }
    }

    /** 0..1 watched, or -1 when the length is unknown. */
    fun progressFraction(): Float = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else -1f
}

fun watchEntryFromJson(o: JSONObject): WatchEntry? {
    val type = try { ContentType.valueOf(o.str("type") ?: return null) } catch (e: IllegalArgumentException) { return null }
    return WatchEntry(
        key = o.str("key") ?: return null,
        type = type,
        id = o.str("id") ?: return null,
        ext = o.str("ext"),
        title = o.str("title") ?: "",
        subtitle = o.str("subtitle"),
        image = o.str("image"),
        position = o.long("position") ?: 0,
        duration = o.long("duration") ?: 0,
        updated = o.long("updated") ?: 0,
        seriesId = o.str("series"),
    )
}

fun StreamItem.toJson(): JSONObject = JSONObject()
    .put("type", type.name).put("id", id).put("name", name).put("image", image)
    .put("cat", categoryId).put("ext", ext).put("num", num).put("rating", rating).put("arch", archiveDays)

fun streamItemFromJson(o: JSONObject): StreamItem? {
    val type = try { ContentType.valueOf(o.str("type") ?: return null) } catch (e: IllegalArgumentException) { return null }
    val id = o.str("id") ?: return null
    return StreamItem(
        type, id, o.str("name") ?: id, o.str("image"), o.str("cat"), o.str("ext"), o.str("num"), o.str("rating"),
        archiveDays = o.int("arch") ?: 0,
    )
}

/** In-memory state shared between screens; rebuilt from Prefs after process death. */
/** All episodes of a series in play order, handed from the details screen to the player. */
data class SeriesQueue(val seriesId: String, val name: String, val cover: String?, val episodes: List<Episode>)

private val EPISODE_TAG = Regex("""(?i)\bS\d+\s*E\d+|\bE\d+\b""")

/** Many panels already put "S01 E02" in the title. */
fun Episode.hasNumberInTitle(): Boolean = EPISODE_TAG.containsMatchIn(title)

fun Episode.label(): String = if (hasNumberInTitle()) title else "S$season E${number ?: "?"}  $title"

fun SeriesInfo.playOrder(): List<Episode> = seasons.values.flatten()

object Session {
    private var api: XtreamApi? = null
    var userInfo: UserInfo? = null
    var zapList: List<StreamItem> = emptyList()
    var zapTitle: String? = null
    var seriesQueue: SeriesQueue? = null
    /** Set by the TV guide when opened from the live player: channel index to switch to. */
    var pendingChannel: Int? = null
    val streamCache = HashMap<String, List<StreamItem>>()
    val categoryCache = HashMap<ContentType, List<Category>>()

    fun api(context: Context): XtreamApi? {
        api?.let { return it }
        val account = Prefs(context).account ?: return null
        return XtreamApi(account, context.applicationContext.cacheDir).also { api = it }
    }

    fun start(newApi: XtreamApi, info: UserInfo) {
        clear()
        api = newApi
        userInfo = info
    }

    fun clear() {
        api = null
        userInfo = null
        zapList = emptyList()
        seriesQueue = null
        streamCache.clear()
        categoryCache.clear()
    }
}

fun accountProblem(info: UserInfo): String? {
    val exp = info.expDate
    return when {
        !info.auth -> "Wrong username or password"
        info.status != null && !info.status.equals("Active", ignoreCase = true) -> "Account status: ${info.status}"
        exp != null && exp * 1000 < System.currentTimeMillis() -> "Subscription expired on ${formatDate(exp)}"
        else -> null
    }
}

/** "5,537 channels", "1 movie", "2,105 series". */
fun countLabel(type: ContentType, n: Int): String {
    val number = java.text.NumberFormat.getIntegerInstance(Locale.US).format(n)
    val noun = when (type) {
        ContentType.LIVE -> if (n == 1) "channel" else "channels"
        ContentType.MOVIE -> if (n == 1) "movie" else "movies"
        ContentType.SERIES -> "series"
    }
    return "$number $noun"
}

/** Long-press on a Continue watching card: ask first, so a long OK press can't wipe progress by accident. */
fun confirmRemoveProgress(activity: Activity, prefs: Prefs, item: StreamItem, onRemoved: () -> Unit) {
    androidx.appcompat.app.AlertDialog.Builder(activity)
        .setTitle("Remove from Continue watching?")
        .setMessage("${item.name}\nThe saved position is deleted too.")
        .setPositiveButton("Remove") { _, _ ->
            prefs.removeProgress(item.id)
            android.widget.Toast.makeText(activity, "Removed from Continue watching", android.widget.Toast.LENGTH_SHORT).show()
            onRemoved()
        }
        .setNegativeButton("Keep", null)
        .show()
}

/** "7.491" -> "7.5" (favorites saved before ratings were rounded keep the raw value). */
fun displayRating(raw: String?): String? {
    val value = raw?.toDoubleOrNull() ?: return raw
    if (value <= 0.0) return null
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

fun formatDate(unixSeconds: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(unixSeconds * 1000))

fun formatClock(unixSeconds: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unixSeconds * 1000))

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun Activity.goToLogin() {
    startActivity(Intent(this, PlaylistsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    finish()
}

private val AVATAR_COLORS = intArrayOf(
    0xFF3D8BFD.toInt(), 0xFF8A5CF6.toInt(), 0xFF14B8A6.toInt(),
    0xFFF97316.toInt(), 0xFFEC4899.toInt(), 0xFF22C55E.toInt(),
)

/** Colour of a playlist's initial circle (stable per playlist). */
fun avatarColor(playlistId: String): Int = AVATAR_COLORS[Math.floorMod(playlistId.hashCode(), AVATAR_COLORS.size)]

val scaleOnFocus = View.OnFocusChangeListener { v, hasFocus ->
    val glow = Prefs(v.context).posterGlow
    val scale = if (hasFocus) (if (glow) 1.1f else 1.06f) else 1f
    val d = v.resources.displayMetrics.density
    v.animate().scaleX(scale).scaleY(scale).translationY(if (hasFocus && glow) -4 * d else 0f).setDuration(140).start()
    v.elevation = if (hasFocus) (if (glow) 18 * d else 8f) else 0f
    if (glow && android.os.Build.VERSION.SDK_INT >= 28) {
        // A soft glow in the theme colour around the highlighted poster.
        val a = v.context.obtainStyledAttributes(intArrayOf(R.attr.appAccent))
        val accent = a.getColor(0, 0xFF3D8BFD.toInt())
        a.recycle()
        v.outlineSpotShadowColor = accent
        v.outlineAmbientShadowColor = accent
    }
    // In a row or grid: the others dim a little so the highlighted one stands out.
    val parent = v.parent as? androidx.recyclerview.widget.RecyclerView ?: return@OnFocusChangeListener
    if (!glow) return@OnFocusChangeListener
    if (hasFocus) {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            c.animate().alpha(if (c === v) 1f else 0.72f).setDuration(140).start()
        }
    } else {
        parent.post {
            if (!parent.hasFocus()) for (i in 0 until parent.childCount) parent.getChildAt(i).animate().alpha(1f).setDuration(140).start()
        }
    }
}

/** Simple list of focusable rows with a title and optional subtitle. */
class TextAdapter<T>(
    private val layout: Int,
    private val bind: (VH, T) -> Unit,
    private val onClick: (Int, T) -> Unit,
    private val onLongClick: ((Int, T) -> Unit)? = null,
) : RecyclerView.Adapter<TextAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView? = view.findViewById(R.id.subtitle)
    }

    var items: List<T> = emptyList()
        private set
    var activeIndex = -1
        set(value) {
            val old = field
            field = value
            // A payload rebinds in place: no fade that swaps the row and drops its focus.
            if (old in items.indices) notifyItemChanged(old, "active")
            if (value in items.indices) notifyItemChanged(value, "active")
        }

    fun submit(list: List<T>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        bind(holder, items[position])
        holder.itemView.isActivated = position == activeIndex
        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p, items[p])
        }
        val longClick = onLongClick
        if (longClick != null) {
            holder.itemView.setOnLongClickListener {
                val p = holder.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) longClick(p, items[p])
                true
            }
        }
    }
}

/** Channels as rows (grid=false) or movies/series as posters (grid=true). */
class ItemAdapter(
    private val grid: Boolean,
    private val onClick: (Int, StreamItem) -> Unit,
    private val onLongClick: ((Int, StreamItem) -> Unit)?,
    /** Optional row layout (Home rows); defaults to poster grid / channel list. */
    private val layout: Int? = null,
    /** Called when an item gets focus (the browse screen's info strip). */
    private val onFocusItem: ((StreamItem) -> Unit)? = null,
    /** Extra filling for a row (Live TV: what is on now). */
    private val onBindExtra: ((VH, StreamItem) -> Unit)? = null,
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val fav: TextView? = view.findViewById(R.id.fav)
        /** Poster grid: rating in a small badge on the picture. */
        val ratingBadge: TextView? = view.findViewById(R.id.rating)
        val watchProgress: android.widget.ProgressBar? = view.findViewById(R.id.watch_progress)
        val epgNow: TextView? = view.findViewById(R.id.epg_now)
        val epgBar: android.widget.ProgressBar? = view.findViewById(R.id.epg_bar)
    }

    var items: List<StreamItem> = emptyList()
        private set
    var favorites: Set<String> = emptySet()
    /** Series with new episodes ("NEW" badge on the poster). */
    var newIds: Set<String> = emptySet()

    fun submit(list: List<StreamItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(layout ?: if (grid) R.layout.item_poster else R.layout.item_channel, parent, false)
        if (grid) view.onFocusChangeListener = scaleOnFocus
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        val badge = holder.ratingBadge
        if (badge != null) {
            val rating = displayRating(item.rating)
            badge.text = rating?.let { "★ $it" }
            badge.visibility = if (rating == null) View.GONE else View.VISIBLE
            holder.subtitle.text = item.note ?: ""
            holder.subtitle.visibility = if (item.note == null) View.GONE else View.VISIBLE
        } else {
            holder.subtitle.text = item.note ?: if (grid) displayRating(item.rating)?.let { "★ $it" } ?: "" else item.num ?: ""
        }
        holder.watchProgress?.apply {
            visibility = if (item.progress >= 0f) View.VISIBLE else View.GONE
            progress = (item.progress.coerceAtLeast(0f) * 1000).toInt()
        }
        holder.itemView.findViewById<View?>(R.id.live_badge)?.visibility = if (item.type == ContentType.LIVE) View.VISIBLE else View.GONE
        // Recycled rows may still be dimmed from the focus effect.
        if (!holder.itemView.hasFocus()) holder.itemView.alpha = 1f
        holder.itemView.findViewById<View?>(R.id.new_badge)?.visibility = if (item.id in newIds) View.VISIBLE else View.GONE
        onBindExtra?.invoke(holder, item)
        val focusCallback = onFocusItem
        if (focusCallback != null) {
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                if (grid) scaleOnFocus.onFocusChange(v, hasFocus)
                val p = holder.bindingAdapterPosition
                if (hasFocus && p != RecyclerView.NO_POSITION) focusCallback(items[p])
            }
        }
        holder.fav?.visibility = if (favorites.contains(item.id)) View.VISIBLE else View.GONE
        holder.image.load(item.image) {
            crossfade(true)
            placeholder(ShimmerDrawable())
            error(R.drawable.placeholder)
            fallback(R.drawable.placeholder)
        }
        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p, items[p])
        }
        val longClick = onLongClick
        if (longClick != null) {
            holder.itemView.setOnLongClickListener {
                val p = holder.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) longClick(p, items[p])
                true
            }
        } else {
            holder.itemView.setOnLongClickListener(null)
        }
    }
}

/**
 * Row (or list) for the remote: holding Left/Right (Up/Down for a list) keeps the highlight in
 * the row and scrolls it, instead of jumping out to whatever is nearest when the next item isn't
 * on screen yet. At the ends the highlight stays put.
 */
class RowLayoutManager(context: android.content.Context, orientation: Int = HORIZONTAL) :
    LinearLayoutManager(context, orientation, false) {

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val forward = if (orientation == HORIZONTAL) View.FOCUS_RIGHT else View.FOCUS_DOWN
        val backward = if (orientation == HORIZONTAL) View.FOCUS_LEFT else View.FOCUS_UP
        if (direction != forward && direction != backward) return null
        val item = findContainingItemView(focused) ?: return null
        val next = getPosition(item) + if (direction == forward) 1 else -1
        // List ends: let Up/Down (Left/Right for a list) leave normally; along the row, stay.
        if (next < 0 || next >= itemCount) return if (orientation == HORIZONTAL) focused else null
        findViewByPosition(next)?.let { return it }
        scrollToPosition(next)
        return focused
    }
}
