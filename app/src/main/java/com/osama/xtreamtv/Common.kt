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

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("xtream", Context.MODE_PRIVATE)

    var account: Account?
        get() {
            val base = sp.getString("base", null) ?: return null
            val user = sp.getString("user", null) ?: return null
            val pass = sp.getString("pass", null) ?: return null
            return Account(base, user, pass)
        }
        set(value) {
            val e = sp.edit()
            if (value == null) {
                e.remove("base").remove("user").remove("pass")
            } else {
                e.putString("base", value.base).putString("user", value.username).putString("pass", value.password)
            }
            e.apply()
        }

    /** Comma-separated allowed_output_formats from the last login. */
    var formats: String
        get() = sp.getString("formats", "") ?: ""
        set(value) = sp.edit().putString("formats", value).apply()

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
        val all = readList("continue").mapNotNull { watchEntryFromJson(it) }.filter { it.key != entry.key }
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("type", type.name).put("id", id).put("ext", ext)
        .put("title", title).put("subtitle", subtitle).put("image", image)
        .put("position", position).put("duration", duration).put("updated", updated)

    fun progressText(): String =
        if (duration > 0) "▶ ${formatDuration(position)} / ${formatDuration(duration)}" else "▶ ${formatDuration(position)}"
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
    )
}

fun StreamItem.toJson(): JSONObject = JSONObject()
    .put("type", type.name).put("id", id).put("name", name).put("image", image)
    .put("cat", categoryId).put("ext", ext).put("num", num).put("rating", rating)

fun streamItemFromJson(o: JSONObject): StreamItem? {
    val type = try { ContentType.valueOf(o.str("type") ?: return null) } catch (e: IllegalArgumentException) { return null }
    val id = o.str("id") ?: return null
    return StreamItem(type, id, o.str("name") ?: id, o.str("image"), o.str("cat"), o.str("ext"), o.str("num"), o.str("rating"))
}

/** In-memory state shared between screens; rebuilt from Prefs after process death. */
object Session {
    private var api: XtreamApi? = null
    var userInfo: UserInfo? = null
    var zapList: List<StreamItem> = emptyList()
    val streamCache = HashMap<String, List<StreamItem>>()
    val categoryCache = HashMap<ContentType, List<Category>>()

    fun api(context: Context): XtreamApi? {
        api?.let { return it }
        val account = Prefs(context).account ?: return null
        return XtreamApi(account).also { api = it }
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
    startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    finish()
}

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
    }
}

/** Channels as rows (grid=false) or movies/series as posters (grid=true). */
class ItemAdapter(
    private val grid: Boolean,
    private val onClick: (Int, StreamItem) -> Unit,
    private val onLongClick: ((Int, StreamItem) -> Unit)?,
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val fav: TextView? = view.findViewById(R.id.fav)
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
            .inflate(if (grid) R.layout.item_poster else R.layout.item_channel, parent, false)
        if (grid) view.onFocusChangeListener = scaleOnFocus
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        holder.subtitle.text = item.note ?: if (grid) item.rating?.let { "★ $it" } ?: "" else item.num ?: ""
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
