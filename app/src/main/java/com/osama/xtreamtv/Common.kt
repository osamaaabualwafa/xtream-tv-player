package com.osama.xtreamtv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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

    fun continueWatching(type: ContentType): List<WatchEntry> =
        readList("continue").mapNotNull { watchEntryFromJson(it) }.filter { it.type == type }.sortedByDescending { it.updated }

    fun saveProgress(entry: WatchEntry) {
        // One entry per series: the episode watched last replaces the others.
        val all = readList("continue").mapNotNull { watchEntryFromJson(it) }
            .filter { it.key != entry.key && (entry.seriesId == null || it.seriesId != entry.seriesId) }
        writeList("continue", (listOf(entry) + all).take(40).map { it.toJson() })
    }

    fun removeProgress(key: String) {
        val all = readList("continue").mapNotNull { watchEntryFromJson(it) }.filter { it.key != key }
        writeList("continue", all.map { it.toJson() })
        savePosition(key, 0)
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
    val scale = if (hasFocus) 1.06f else 1f
    v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
    v.elevation = if (hasFocus) 8f else 0f
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
            if (old in items.indices) notifyItemChanged(old)
            if (value in items.indices) notifyItemChanged(value)
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
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val fav: TextView? = view.findViewById(R.id.fav)
        /** Poster grid: rating in a small badge on the picture. */
        val ratingBadge: TextView? = view.findViewById(R.id.rating)
        val watchProgress: android.widget.ProgressBar? = view.findViewById(R.id.watch_progress)
    }

    var items: List<StreamItem> = emptyList()
        private set
    var favorites: Set<String> = emptySet()

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
            crossfade(false)
            placeholder(R.drawable.placeholder)
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
