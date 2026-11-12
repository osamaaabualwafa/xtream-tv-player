package com.osama.xtreamtv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.launch

/**
 * A person's page (package 2, item 3, approved 2026-11-12): their photo, name, role and how many of
 * their titles the playlist has; All · Movies · Series; the same filter as Movies and Series; Sort;
 * their titles as posters (copies of one title are one card). Opens on the first poster.
 * Remote: Up from the top row of posters -> the filter line -> the tabs -> Filter and Sort; Down from
 * Filter and Sort returns straight to the poster left.
 */
class PersonActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Look.wrap(newBase))

    companion object {
        private const val EXTRA_NAME = "person"
        fun intent(context: Context, name: String): Intent = Intent(context, PersonActivity::class.java).putExtra(EXTRA_NAME, name)
        private val SORTS = listOf("Newest", "IMDb score", "Year", "Name")
    }

    private lateinit var api: XtreamApi
    private lateinit var prefs: Prefs
    private lateinit var name: String
    private lateinit var grid: RecyclerView
    private lateinit var adapter: ItemAdapter
    private lateinit var subtitle: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var filterButton: TextView
    private lateinit var sortButton: TextView
    private lateinit var empty: TextView
    private var filterUi: FilterUi? = null

    private var all: List<StreamItem> = emptyList()
    private var tab: ContentType? = null
    private var filter = TitleFilter.State()
    private var sort = 0
    private val focusMap = FocusMap { currentFocus }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Session.api(this) ?: return finish()
        prefs = Prefs(this)
        name = intent.getStringExtra(EXTRA_NAME) ?: return finish()
        Appearance.apply(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The theme's own background (a picture or a colour), as on the other screens.
            val tv = android.util.TypedValue()
            if (theme.resolveAttribute(R.attr.appBackground, tv, true)) {
                if (tv.resourceId != 0) setBackgroundResource(tv.resourceId) else setBackgroundColor(tv.data)
            }
            setPadding(dp(36), dp(28), dp(36), dp(12))
        }
        // Header: photo, name, role and count; Filter and Sort on the right.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val photoSize = dp(84)
        val face = android.widget.FrameLayout(this)
        face.addView(TextView(this).apply {
            text = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(themeColor(R.attr.appText))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        }, android.widget.FrameLayout.LayoutParams(photoSize, photoSize))
        val photo = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        face.addView(photo, android.widget.FrameLayout.LayoutParams(photoSize, photoSize))
        header.addView(face, LinearLayout.LayoutParams(photoSize, photoSize))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(12), 0) }
        names.addView(TextView(this).apply {
            text = name
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(R.attr.appText))
        })
        subtitle = TextView(this).apply { textSize = 15f; setTextColor(themeColor(R.attr.appTextSecondary)) }
        names.addView(subtitle)
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filterButton = TextView(this, null, 0, R.style.GlassPill).apply { text = "Filter"; setOnClickListener { openFilter() } }
        sortButton = TextView(this, null, 0, R.style.GlassPill).apply { text = "Sort: ${SORTS[0]}"; setOnClickListener { sortList() } }
        header.addView(filterButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { leftMargin = dp(8) })
        header.addView(sortButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { leftMargin = dp(8) })
        root.addView(header)

        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, dp(4)) }
        root.addView(tabs)

        val ui = FilterUi(this, ContentType.MOVIE, { filter }, { st, done ->
            val list = tabItems()
            lifecycleScope.launch {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { run(list, st) }
                done(r)
            }
        }) { st -> filter = st; show() }
        filterUi = ui
        root.addView(ui.lineScroll)

        val frame = android.widget.FrameLayout(this)
        grid = RecyclerView(this).apply {
            clipToPadding = false
            setPadding(dp(2), dp(8), dp(2), dp(8))
            itemAnimator = null
        }
        adapter = ItemAdapter(grid = true, onClick = { _, item -> startActivity(DetailsActivity.intent(this, item)) }, onLongClick = null)
        grid.layoutManager = CenterGridLayoutManager(this, 6)
        grid.tuneForTv()
        grid.adapter = adapter
        frame.addView(grid, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(themeColor(R.attr.appTextSecondary))
            visibility = View.GONE
        }
        frame.addView(empty, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // The remote's paths.
        val top = focusMap.section { listOf<View>(filterButton, sortButton) }
        val tabSection = focusMap.section { listOf<View>(tabs) }
        val line = focusMap.section { listOf<View>(ui.lineScroll) }
        val posters = focusMap.section { listOf<View>(grid) }
        top.exits[View.FOCUS_DOWN] = { posters.entry() }
        tabSection.exits[View.FOCUS_UP] = { filterButton }
        tabSection.exits[View.FOCUS_DOWN] = { line.entry() }
        line.exits[View.FOCUS_UP] = { tabs.getChildAt(0)?.let { first -> (0 until tabs.childCount).map { tabs.getChildAt(it) }.firstOrNull { it.isActivated } ?: first } }
        line.exits[View.FOCUS_DOWN] = { posters.entry() }
        posters.exits[View.FOCUS_UP] = {
            val f = currentFocus
            val at = IntArray(2); f?.getLocationOnScreen(at)
            ui.chipNear(at[0] + (f?.width ?: 0) / 2)
        }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, new -> focusMap.note(new) }

        load(photo)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (focusMap.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun load(photo: ImageView) {
        lifecycleScope.launch {
            val pl = prefs.activeId ?: return@launch
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Ratings.db.personTitles(pl, name) }.getOrNull() } ?: return@launch
            Tmdb.photoUrl(found.photo)?.let { url -> photo.load(url) { transformations(CircleCropTransformation()); crossfade(false) } }
            var acts = found.acts
            var directs = found.directs
            val items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val wanted = found.titles.groupBy({ it.first }, { it.second }).mapValues { it.value.toHashSet() }
                val list = ArrayList<StreamItem>()
                for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
                    val ids = wanted[type.name].orEmpty()
                    val kind = if (type == ContentType.MOVIE) "movie" else "show"
                    // Also the provider's titles with a TMDB id in the person's film list (the notebook may not have reached them).
                    val films = found.films.filterKeys { it.startsWith("tmdb/$kind/") }
                    if (ids.isEmpty() && films.isEmpty()) continue
                    val source = runCatching { Session.all(api, type) }.getOrDefault(emptyList())
                    list.addAll(source.filter { s ->
                        if (s.id in ids) return@filter true
                        val role = s.tmdb?.trim()?.let { films["tmdb/$kind/$it"] } ?: return@filter false
                        if (role == RatingsDb.Detail.DIRECTOR) directs = true else acts = true
                        true
                    })
                }
                val facts = TitleFilter.facts(this@PersonActivity)
                // Copies of one film count once: by the notebook's match, else by the provider's own TMDB id.
                Versions.group(list, { s ->
                    facts["${s.type.name}:${s.id}"]?.ref
                        ?: s.tmdb?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it != "0" }?.let { "tmdb/${if (s.type == ContentType.MOVIE) "movie" else "show"}/$it" }
                }, Versions.learned(this@PersonActivity))
            }
            all = items
            val role = when {
                acts && directs -> "Actor and director"
                directs -> "Director"
                else -> "Actor"
            }
            val movies = items.count { it.type == ContentType.MOVIE }
            val series = items.size - movies
            subtitle.text = "$role · ${items.size} ${if (items.size == 1) "title" else "titles"} in your playlist"
            buildTabs(items.size, movies, series)
            show()
            grid.post { grid.layoutManager?.findViewByPosition(0)?.requestFocus() ?: tabs.getChildAt(0)?.requestFocus() }
        }
    }

    private fun buildTabs(total: Int, movies: Int, series: Int) {
        tabs.removeAllViews()
        for ((t, label) in listOf(null to "All · $total", ContentType.MOVIE to "Movies · $movies", ContentType.SERIES to "Series · $series")) {
            if (t != null && (if (t == ContentType.MOVIE) movies else series) == 0) continue
            tabs.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(ContextCompat.getColorStateList(this@PersonActivity, R.color.on_focus_text))
                setBackgroundResource(R.drawable.bg_glass_choice)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                isFocusable = true
                isClickable = true
                isActivated = t == tab
                setOnClickListener {
                    tab = t
                    for (i in 0 until tabs.childCount) tabs.getChildAt(i).isActivated = tabs.getChildAt(i) === this
                    show()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
        }
    }

    private fun tabItems(): List<StreamItem> = all.filter { tab == null || it.type == tab }

    private fun run(items: List<StreamItem>, st: TitleFilter.State): TitleFilter.Result {
        val facts = TitleFilter.facts(this)
        val watched = prefs.watchedKeys()
        val started = prefs.continueWatching(ContentType.SERIES).mapNotNullTo(HashSet()) { it.seriesId }
        return TitleFilter.run(ContentType.MOVIE, items, st, { facts["${it.type.name}:${it.id}"] }, { item ->
            if (item.type == ContentType.MOVIE) "movie_${item.id}" in watched || prefs.position("movie_${item.id}") > 0 else item.id in started
        })
    }

    private fun show() {
        filterUi?.refreshLine()
        filterButton.text = if (filter.count == 0) "Filter" else "Filter · ${filter.count}"
        filterButton.setBackgroundResource(if (filter.count == 0) R.drawable.bg_glass_pill else R.drawable.bg_glass_choice)
        filterButton.isActivated = filter.count > 0
        sortButton.text = "Sort: ${SORTS[sort]}"
        val items = tabItems()
        val st = filter
        lifecycleScope.launch {
            val shown = if (st.count == 0) items else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { run(items, st).shown }
            val facts = TitleFilter.facts(this@PersonActivity)
            val list = when (sort) {
                1 -> Ratings.sortByScore(shown)
                2 -> shown.sortedByDescending { facts["${it.type.name}:${it.id}"]?.year ?: it.year?.toIntOrNull() ?: 0 }
                3 -> shown.sortedBy { Versions.shownName(it).lowercase() }
                else -> shown.sortedByDescending { it.added }
            }
            adapter.favorites = prefs.favoriteIds(ContentType.MOVIE) + prefs.favoriteIds(ContentType.SERIES)
            adapter.submit(list)
            empty.text = if (list.isEmpty() && st.count > 0) "Nothing matches.\nChange or remove a filter above." else ""
            empty.visibility = if (list.isEmpty() && st.count > 0) View.VISIBLE else View.GONE
        }
    }

    private fun openFilter() {
        val back = currentFocus
        filterUi?.openPanel { (back?.takeIf { it.isAttachedToWindow } ?: filterButton).requestFocus() }
    }

    private fun sortList() {
        val names = SORTS.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sort")
            .setSingleChoiceItems(names, sort) { d, which ->
                d.dismiss()
                if (which != sort) { sort = which; show() }
            }
            .show()
    }
}
