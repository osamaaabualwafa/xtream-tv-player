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

    fun favorites(): Set<String> = HashSet(sp.getStringSet("fav_live", emptySet()) ?: emptySet())

    /** Returns true when the channel is now a favorite. */
    fun toggleFavorite(id: String): Boolean {
        val set = favorites().toMutableSet()
        val added = if (set.contains(id)) {
            set.remove(id)
            false
        } else {
            set.add(id)
            true
        }
        sp.edit().putStringSet("fav_live", set).apply()
        return added
    }

    fun position(key: String): Long = sp.getLong("pos_$key", 0L)

    fun savePosition(key: String, positionMs: Long) {
        val e = sp.edit()
        if (positionMs > 0) e.putLong("pos_$key", positionMs) else e.remove("pos_$key")
        e.apply()
    }
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
        holder.subtitle.text = if (grid) item.rating?.let { "★ $it" } ?: "" else item.num ?: ""
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
