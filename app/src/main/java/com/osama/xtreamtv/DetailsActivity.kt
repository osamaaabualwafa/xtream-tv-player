package com.osama.xtreamtv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DetailsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Look.wrap(newBase))

    companion object {
        /** The coming episode's card (1.1E next episode): shown, never played. */
        private const val COMING_ID = "__coming__"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_IMAGE = "image"
        private const val EXTRA_EXT = "ext"
        private const val EXTRA_RATING = "rating"
        private const val EXTRA_CATEGORY = "category"

        fun intent(context: Context, item: StreamItem): Intent =
            Intent(context, DetailsActivity::class.java)
                .putExtra(EXTRA_TYPE, item.type.name)
                .putExtra(EXTRA_ID, item.id)
                .putExtra(EXTRA_NAME, item.name)
                .putExtra(EXTRA_IMAGE, item.image)
                .putExtra(EXTRA_EXT, item.ext)
                .putExtra(EXTRA_RATING, item.rating)
                .putExtra(EXTRA_CATEGORY, item.categoryId)
    }

    private lateinit var api: XtreamApi
    private lateinit var prefs: Prefs
    private lateinit var item: StreamItem
    private var ext: String? = null
    private var posterUrl: String? = null

    private lateinit var poster: ImageView
    private lateinit var title: TextView
    private lateinit var meta: android.widget.LinearLayout
    private lateinit var plot: TextView
    private lateinit var buttons: View
    private lateinit var playButton: Button
    private lateinit var resumeButton: Button
    private lateinit var favoriteButton: Button
    private lateinit var seasonsView: RecyclerView
    private lateinit var episodesView: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView

    private lateinit var seasonAdapter: TextAdapter<Int>
    private lateinit var episodeAdapter: TextAdapter<Episode>
    private var seasons: Map<Int, List<Episode>> = emptyMap()

    private val isMovie get() = item.type == ContentType.MOVIE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Session.api(this) ?: return goToLogin()
        prefs = Prefs(this)
        val type = if (intent.getStringExtra(EXTRA_TYPE) == ContentType.SERIES.name) ContentType.SERIES else ContentType.MOVIE
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        item = StreamItem(
            type = type,
            id = id,
            name = intent.getStringExtra(EXTRA_NAME) ?: "",
            image = intent.getStringExtra(EXTRA_IMAGE),
            categoryId = intent.getStringExtra(EXTRA_CATEGORY),
            ext = intent.getStringExtra(EXTRA_EXT),
            num = null,
            rating = intent.getStringExtra(EXTRA_RATING),
        )
        ext = item.ext
        posterUrl = item.image
        Appearance.apply(this)
        setContentView(if (item.type == ContentType.SERIES) R.layout.activity_details_series else R.layout.activity_details)

        poster = findViewById(R.id.poster)
        title = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        plot = findViewById(R.id.plot)
        keepButtonsVisible()
        buttons = findViewById(R.id.buttons)
        playButton = findViewById(R.id.play)
        resumeButton = findViewById(R.id.resume)
        favoriteButton = findViewById(R.id.favorite)
        seasonsView = findViewById(R.id.seasons)
        episodesView = findViewById(R.id.episodes)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)

        title.text = item.name
        setMeta(emptyList())
        loadPoster(item.image)

        buttons.visibility = View.VISIBLE
        favoriteButton.setOnClickListener {
            prefs.toggleFavorite(item)
            updateFavoriteButton()
        }
        updateFavoriteButton()

        if (!isMovie) {
            val modified = Session.streamCache["SERIES:__all__"]?.firstOrNull { it.id == item.id }?.added ?: 0L
            prefs.markSeriesSeen(item.id, maxOf(modified, System.currentTimeMillis() / 1000))
        }
        if (isMovie) setupMovie() else setupSeries()
    }

    override fun onPause() {
        super.onPause()
        MiniPlayerBar.detach(this)
    }

    override fun onResume() {
        super.onResume()
        MiniPlayerBar.attach(this)
        if (!::item.isInitialized || !::resumeButton.isInitialized) return
        if (isMovie) {
            updateResumeButton()
        } else if (::episodeAdapter.isInitialized) {
            // A payload rebinds rows in place, so the focused episode keeps focus.
            episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.itemCount, "progress")
            if (seasons.isNotEmpty()) updateSeriesResume(focus = false)
        }
    }

    /** Trailer button when the provider has a YouTube id (or link) for this title. */
    private fun showTrailer(trailer: String?) {
        val value = trailer?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val button = findViewById<Button>(R.id.trailer)
        button.visibility = View.VISIBLE
        button.setOnClickListener {
            val isLink = value.startsWith("http")
            val intents = if (isLink) {
                listOf(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
            } else {
                listOf(
                    Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$value")),
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$value")),
                )
            }
            val started = intents.any { intent ->
                try {
                    startActivity(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    false
                }
            }
            if (!started) showToast(this, "Install YouTube to watch trailers", Toast.LENGTH_LONG)
        }
    }

    /** No gap on the left of the first visible button. */
    private fun alignButtons() {
        val row = buttons as android.view.ViewGroup
        var first = true
        for (i in 0 until row.childCount) {
            val b = row.getChildAt(i)
            if (b.visibility != View.VISIBLE) continue
            (b.layoutParams as android.view.ViewGroup.MarginLayoutParams).leftMargin = if (first) 0 else (12 * resources.displayMetrics.density).toInt()
            b.requestLayout()
            first = false
        }
    }

    private fun updateFavoriteButton() {
        buttons.post { alignButtons() }
        val fav = item.id in prefs.favoriteIds(item.type)
        favoriteButton.text = if (fav) "★  Favorite" else "☆  Add to favorites"
    }

    /**
     * Series page at large sizes: when the top block doesn't fit above the seasons, the description
     * gives way a step at a time so the buttons stay fully visible.
     */
    private fun keepButtonsVisible() {
        val info = findViewById<View?>(R.id.info) ?: return
        val area = info.parent as? android.widget.ScrollView ?: return
        val plot = findViewById<TextView>(R.id.plot)
        val credits = findViewById<TextView>(R.id.credits)
        area.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (info.height <= area.height - area.paddingTop - area.paddingBottom) return@addOnLayoutChangeListener
            area.post {
                when {
                    credits.visibility != View.GONE -> credits.visibility = View.GONE
                    plot.visibility != View.GONE && plot.maxLines > 1 -> plot.maxLines = 1
                    plot.visibility != View.GONE -> plot.visibility = View.GONE
                }
            }
        }
    }

    private fun loadPoster(url: String?) {
        if (url.isNullOrBlank()) return
        posterUrl = url
        poster.load(url) {
            crossfade(true)
            placeholder(R.drawable.placeholder)
            error(R.drawable.placeholder)
        }
        val glass = Prefs(this).themeIndex == Appearance.THEME_GLASS
        findViewById<ImageView>(R.id.backdrop).let { backdrop ->
            if (glass) Look.blurBackdrop(backdrop)
            backdrop.load(url) {
                crossfade(true)
                // Older TVs have no blur: a tiny picture scaled up gives the same softness.
                if (glass && android.os.Build.VERSION.SDK_INT < 31) size(32, 48)
            }
        }
    }

    /** The chips shown last, so real scores arriving later can take the provider's ★ place. */
    private var metaValues: List<String> = emptyList()
    /** The provider's own chips (year, length, genre), before anything MDBList fills in. */
    private var baseMeta: List<String> = emptyList()
    /** 1.1E extras: age rating, awards, next episode, recommendations, gaps. */
    private var extras: RatingsExtras.Extras? = null
    private var recosAdapter: ItemAdapter? = null
    /** Series: More like this is ready under the episodes (shown with Down). */
    private var recosReady = false
    private var currentSeason: Int? = null
    private var lastEpisodeFocus: View? = null

    private fun validYear(text: String?) = text?.take(4)?.takeIf { Regex("(19|20)\\d{2}").matches(it) }
    private var scores: RatingBadges.Scores? = null

    /** Rating, year, length, genre as glass chips; real IMDb / Rotten Tomatoes scores first when known (1.1E). */
    private fun setMeta(values: List<String>) {
        metaValues = values
        meta.removeAllViews()
        val density = resources.displayMetrics.density
        scores?.let { s ->
            meta.addView(TextView(this).apply {
                text = RatingBadges.line(context, s, themeColor(R.attr.appText))
                textSize = 14f
                maxLines = 1
                setBackgroundResource(R.drawable.bg_meta_chip)
                setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = (8 * density).toInt() }
            })
        }
        for (v in values.filter { it.isNotBlank() }) {
            meta.addView(TextView(this).apply {
                text = v
                // Glass: the rating stands out in the live colour.
                setTextColor(themeColor(R.attr.appText))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (320 * density).toInt()
                setBackgroundResource(R.drawable.bg_meta_chip)
                setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = (8 * density).toInt() }
                // An award: the same chip in gold or silver glass, with its statuette.
                AwardArt.styleChip(this, v)
            })
        }
    }

    /**
     * 1.1E: the title's real scores (saved, or asked from MDBList now: it is on screen). "Wrong scores"
     * shows for titles matched by name, the only ones that can be wrong.
     */
    private fun showScores(tmdb: String?, imdb: String?, releaseDate: String?, cover: String?, name: String, originalName: String? = null) {
        if (!Ratings.hasKey) return
        baseMeta = metaValues
        Ratings.known(item.type, item.id)?.let { scores = it; setMeta(metaValues) }
        val title = Ratings.Title(item.type, item.id, name, releaseDate?.let { Regex("(19|20)\\d{2}").find(it)?.value }, tmdb, imdb, item.image ?: cover,
            originalName?.takeIf { it != name })
        // More like this needs the whole list and its name index: get them ready meanwhile.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val all = Session.streamCache["${item.type.name}:__all__"] ?: runCatching { api.streams(item.type, null) }.getOrNull()
                ?.also { Session.streamCache["${item.type.name}:__all__"] = it }
            all?.let { Ratings.nameIndex(it) }
        }
        lifecycleScope.launch {
            val found = Ratings.lookup(title, onScreen = true)
            scores = found
            setMeta(metaValues)
            // No trailer from the provider: MDBList's, when it has one (asked once more for titles
            // saved before trailers were kept).
            if (findViewById<Button>(R.id.trailer).visibility != View.VISIBLE) {
                if (Ratings.needsTrailer(item.type, item.id)) Ratings.lookup(title, onScreen = true, fresh = true)
                Ratings.trailer(item.type, item.id)?.let { showTrailer(it); buttons.post { alignButtons() } }
            }
            val wrong = findViewById<Button?>(R.id.wrong_scores) ?: return@launch
            wrong.visibility = if (found != null && Ratings.how(item.type, item.id) == Ratings.How.NAME) View.VISIBLE else View.GONE
            wrong.setOnClickListener {
                Ratings.markWrong(item.type, item.id)
                scores = null
                setMeta(metaValues)
                if (wrong.hasFocus()) favoriteButton.requestFocus()
                wrong.visibility = View.GONE
                buttons.post { alignButtons() }
                showToast(this@DetailsActivity, "Scores removed. This title won't be matched again.", Toast.LENGTH_SHORT)
            }
            buttons.post { alignButtons() }
            if (found != null || Ratings.how(item.type, item.id) == Ratings.How.ID) showExtras()
        }
    }

    /**
     * 1.1E extras on this page: what the provider left empty (description, year, length, genre), the
     * age rating and awards as chips, the next episode (series) and More like this.
     */
    private suspend fun showExtras() {
        val e = RatingsExtras.forTitle(item.type, item.id) ?: return
        extras = e
        if (plot.text.isNullOrBlank() || plot.text.toString() == "Details unavailable.") e.description?.let { plot.text = it }
        val year = Regex("^(19|20)\\d{2}$")
        val fills = ArrayList<String>()
        if (baseMeta.none { year.matches(it) } && e.year > 1900) fills += e.year.toString()
        if (isMovie && baseMeta.none { it.contains(" min") } && e.runtime > 0) fills += if (e.runtime >= 60) "${e.runtime / 60} h ${e.runtime % 60} min" else "${e.runtime} min"
        if (baseMeta.none { !year.matches(it) && !it.contains(" min") } && e.genres.isNotEmpty()) fills += e.genres.take(3).joinToString(" / ")
        val badges = listOfNotNull(e.certification?.takeIf { it.length <= 8 }, RatingsExtras.awardBadge(e.awards))
        setMeta(baseMeta + fills + badges)
        if (!isMovie) {
            val line = RatingsExtras.nextLine(e) { s, n -> seasons[s]?.any { it.number == n } == true }
            findViewById<TextView?>(R.id.next_episode)?.apply {
                text = line
                visibility = if (line == null) View.GONE else View.VISIBLE
            }
            currentSeason?.let { s -> episodeAdapter.submit(withComing(s)) }
        }
        showRecos(e)
    }

    /** "Mon 9 Nov" when [ep] is the next episode and hasn't aired yet (MDBList's date), else null. */
    private fun notAiredYet(ep: Episode): String? {
        val n = extras?.next ?: return null
        if (n.season != ep.season || n.number != ep.number || n.airAt <= System.currentTimeMillis()) return null
        return java.text.SimpleDateFormat(if (n.airAt - System.currentTimeMillis() <= 30L * 86_400_000) "EEE d MMM" else "MMMM yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(n.airAt))
    }

    /** The coming episode as a card at the end of its season (not playable), when the provider lacks it. */
    private fun comingEpisode(): Episode? {
        val n = extras?.next ?: return null
        if (n.airAt <= 0 || n.season <= 0 || n.number <= 0) return null
        if (seasons[n.season]?.any { it.number == n.number } == true) return null
        return Episode(COMING_ID, n.title ?: "Episode ${n.number}", n.season, n.number, "", null, null)
    }

    private fun withComing(season: Int): List<Episode> {
        val list = seasons[season] ?: emptyList()
        val coming = comingEpisode()?.takeIf { it.season == season } ?: return list
        return list + coming
    }

    /**
     * More like this: first MDBList's recommendations that this playlist has (3 at least). When
     * MDBList has fewer (new titles have none yet), "Similar in your playlist": titles of the same
     * provider category sharing the most genres, best rated first.
     */
    private fun showRecos(e: RatingsExtras.Extras) {
        val recs = e.recs
        lifecycleScope.launch {
            val all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Session.streamCache["${item.type.name}:__all__"] ?: runCatching { api.streams(item.type, null) }.getOrNull() ?: emptyList()
            }
            val fromMdbList = if (recs.isEmpty()) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val byId = all.associateBy { it.id }
                val viaList = all.filter { it.tmdb != null }.associateBy { it.tmdb!! }
                val viaLookups = Ratings.idsByTmdb(item.type)
                // Movie lists carry no ids: a recommended title is also found by its name (the
                // index is made once per list and kept).
                val byName by lazy { Ratings.nameIndex(all) }
                recs.mapNotNull { r ->
                    val id = r.substringBefore('\t')
                    val title = r.substringAfter('\t', "")
                    (if (id.isNotEmpty()) viaList[id] ?: viaLookups[id]?.let { byId[it] } else null)
                        ?: title.takeIf { it.isNotEmpty() }?.let { byName[Ratings.nameKey(it)] }
                }.filter { it.id != item.id }.distinctBy { it.id }.take(12)
            }
            val similar = fromMdbList.size < 3
            val found = if (!similar) fromMdbList else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { similarTitles(all, e) }
            if (BuildConfig.DEBUG) android.util.Log.d("Recos", "${item.name}: ${recs.size} recommended, ${fromMdbList.size} in the playlist" +
                (if (similar) " · similar instead: ${found.size}" else "") + " · " + found.take(6).map { it.name })
            if (found.size < 3) return@launch
            findViewById<TextView>(R.id.recos_title).text = if (similar) "Similar in your playlist" else "More like this"
            val view = findViewById<RecyclerView>(R.id.recos)
            val adapter = recosAdapter ?: ItemAdapter(
                grid = true,
                onClick = { _, it -> startActivity(intent(this@DetailsActivity, it)) },
                onLongClick = null,
                layout = R.layout.item_poster_mini,
            ).also { a ->
                recosAdapter = a
                view.layoutManager = RowLayoutManager(this@DetailsActivity)
                view.adapter = a
            }
            adapter.submit(found)
            if (isMovie) {
                findViewById<View>(R.id.recos_title).visibility = View.VISIBLE
                view.visibility = View.VISIBLE
            } else {
                recosReady = true
            }
        }
    }

    /** Titles like this one from the playlist: same category, most genres in common, best rated first. */
    private fun similarTitles(all: List<StreamItem>, e: RatingsExtras.Extras): List<StreamItem> {
        val me = all.firstOrNull { it.id == item.id } ?: item
        val cats = me.categoryIds.toSet()
        fun genresOf(s: StreamItem): Set<String> =
            (RatingsExtras.saved(s.type, s.id)?.genres ?: s.genre?.split('/', ',', '&', '|')?.map { it.trim() } ?: emptyList())
                .map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()
        val mine = e.genres.map { it.lowercase() }.toSet() + genresOf(me)
        // No genres known: nothing to be similar by (the category's best titles alone looked random).
        if (mine.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { it.id != item.id && it.categoryIds.any { c -> c in cats } }
            .map { s -> s to genresOf(s).intersect(mine).size }
            .filter { it.second > 0 }
            .map { (s, shared) -> s to (shared * 10 + Ratings.scoreOf(s)) }
            .sortedWith(compareByDescending<Pair<StreamItem, Double>> { it.second }.thenByDescending { it.first.added })
            .map { it.first }
            .distinctBy { it.id }
            .take(12)
            .toList()
    }

    /** Series: Down from the episodes slides the top part away and shows More like this; Up brings it back. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Movies: Down from the buttons tucks the description and credits away so More like this
        // fits in full; Up brings them back.
        val recosRow = findViewById<RecyclerView?>(R.id.recos)
        if (isMovie && recosRow?.visibility == View.VISIBLE && event.action == android.view.KeyEvent.ACTION_DOWN) {
            val credits = findViewById<TextView>(R.id.credits)
            // From anywhere above the row, not only the buttons: right after the page opens the
            // highlight can sit elsewhere (a button added a moment later), and Down then did nothing.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && !recosRow.hasFocus()) {
                if ((recosRow.adapter?.itemCount ?: 0) == 0) return super.dispatchKeyEvent(event)
                plot.visibility = View.GONE
                credits.visibility = View.GONE
                // Still no room (a big interface size): the chips and next-episode line go too.
                focusFirstCard(recosRow) {
                    findViewById<View>(R.id.meta).let { tuckedMeta = it.visibility == View.VISIBLE; it.visibility = View.GONE }
                    findViewById<View?>(R.id.next_episode)?.let { tuckedNext = it.visibility == View.VISIBLE; it.visibility = View.GONE }
                }
                return true
            }
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && recosRow.hasFocus()) {
                plot.visibility = View.VISIBLE
                credits.visibility = if (credits.text.isNullOrBlank()) View.GONE else View.VISIBLE
                if (tuckedMeta) findViewById<View>(R.id.meta).visibility = View.VISIBLE
                tuckedMeta = false
                if (tuckedNext) findViewById<View?>(R.id.next_episode)?.visibility = View.VISIBLE
                tuckedNext = false
                playButton.post { (if (playButton.isShown) playButton else favoriteButton).requestFocus() }
                return true
            }
        }
        if (!isMovie && recosReady && event.action == android.view.KeyEvent.ACTION_DOWN) {
            val box = findViewById<View>(R.id.recos_box)
            val top = findViewById<View>(R.id.info).parent as View
            val recos = findViewById<RecyclerView>(R.id.recos)
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && episodesView.hasFocus() && box.visibility != View.VISIBLE) {
                lastEpisodeFocus = episodesView.focusedChild
                top.visibility = View.GONE
                box.visibility = View.VISIBLE
                focusFirstCard(recos)
                return true
            }
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && recos.hasFocus()) {
                box.visibility = View.GONE
                top.visibility = View.VISIBLE
                episodesView.post { (lastEpisodeFocus?.takeIf { it.isAttachedToWindow } ?: episodesView.getChildAt(0))?.requestFocus() }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Highlights a row's first card once the row has really been laid out. On the TV (bigger
     * interface) More like this sat below the screen with no cards made yet, so the old "right after"
     * found nothing and Down did nothing (diagnostics report, 2026-11-07). Tries for about a second.
     */
    private fun focusFirstCard(row: RecyclerView, makeRoom: (() -> Unit)? = null) {
        var tries = 0
        val attempt = object : Runnable {
            override fun run() {
                val card = row.layoutManager?.findViewByPosition(0) ?: row.getChildAt(0)
                if (card != null && card.isShown && card.requestFocus()) return
                if (tries == 0) row.scrollToPosition(0)
                if (tries == 4) makeRoom?.invoke()
                if (++tries < 20) row.postDelayed(this, 50)
            }
        }
        row.post(attempt)
    }

    /** The next-episode line was tucked away with the chips (shown again on Up). */
    private var tuckedNext = false
    private var tuckedMeta = false

    private fun showCredits(text: String?) {
        findViewById<TextView>(R.id.credits).apply {
            this.text = text
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    // ---- Movies ----

    private fun movieKey() = "movie_${item.id}"

    private fun setupMovie() {
        playButton.setOnClickListener { playMovie(0L) }
        // Resume was an explicit choice here, so the player doesn't ask again.
        resumeButton.setOnClickListener { playMovie(prefs.position(movieKey())) }
        updateResumeButton()
        playButton.requestFocus()

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val info = api.vodInfo(item.id)
                info.name?.let { title.text = it }
                if (info.ext != null) ext = info.ext
                setMeta(listOfNotNull(
                    validYear(info.releaseDate),
                    info.duration?.takeIf { parseLength(it)?.let { ms -> ms > 0 } ?: true }?.let { durationText(it) },
                    info.genre?.takeIf { it.isNotBlank() },
                ))
                plot.text = info.plot
                showCredits(listOfNotNull(
                    info.director?.let { "Director: $it" },
                    info.cast?.let { "Cast: $it" },
                ).joinToString("\n").ifEmpty { null })
                showTrailer(info.trailer)
                loadPoster(info.cover)
                showScores(info.tmdb, info.imdb, info.releaseDate, info.cover, info.name ?: item.name, info.originalName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                plot.text = "Details unavailable."
            }
            progress.visibility = View.GONE
        }
    }

    private fun updateResumeButton() {
        val pos = prefs.position(movieKey())
        if (pos > 0) {
            resumeButton.text = "▶  Resume ${formatDuration(pos)}"
            resumeButton.visibility = View.VISIBLE
            buttons.post { alignButtons() }
        } else {
            resumeButton.visibility = View.GONE
            buttons.post { alignButtons() }
        }
    }

    private fun playMovie(startMs: Long) {
        val name = title.text.toString()
        val entry = WatchEntry(movieKey(), ContentType.MOVIE, item.id, ext, name, null, posterUrl)
        startActivity(PlayerActivity.vodIntent(this, api.movieUrl(item.id, ext), name, movieKey(), startMs, entry, askResume = false))
    }

    // ---- Series ----

    private fun setupSeries() {
        playButton.visibility = View.GONE
        findViewById<View>(R.id.episodes_card).visibility = View.VISIBLE

        seasonAdapter = TextAdapter(
            R.layout.item_category_chip,
            bind = { vh, season ->
                // Many seasons: short, smaller tabs so they all fit on one line.
                val many = seasons.size > 6
                vh.title.text = when {
                    season == 0 -> "Extras"
                    many -> "S$season"
                    else -> "Season $season"
                }
                vh.subtitle?.text = ""
                vh.title.textSize = if (many) 15f else 16f
                val d = resources.displayMetrics.density
                vh.itemView.setPadding(((if (many) 14 else 18) * d).toInt(), 0, ((if (many) 14 else 18) * d).toInt(), 0)
                (vh.itemView.layoutParams as android.view.ViewGroup.MarginLayoutParams).rightMargin = ((if (many) 6 else 10) * d).toInt()
            },
            onClick = { index, season -> showSeason(index, season) },
        )
        seasonsView.layoutManager = RowLayoutManager(this)
        seasonsView.adapter = seasonAdapter

        episodeAdapter = TextAdapter(
            R.layout.item_episode_card,
            bind = { vh, ep -> bindEpisode(vh, ep) },
            onClick = { _, ep ->
                val wait = notAiredYet(ep)
                when {
                    ep.id == COMING_ID -> showToast(this, "Not available yet", Toast.LENGTH_SHORT)
                    // Listed by the provider before it airs (an empty placeholder): say when, don't
                    // start the player (it only retried "reconnect 1/5", 2026-11-07).
                    wait != null -> showToast(this, "Airs $wait · not available yet", Toast.LENGTH_SHORT)
                    else -> playEpisode(ep)
                }
            },
            onLongClick = { index, ep ->
                if (ep.id == COMING_ID) return@TextAdapter
                // Long-press: mark as watched / not watched.
                val key = episodeKey(ep)
                val watched = !prefs.isWatched(key)
                prefs.setWatched(key, watched)
                if (watched) prefs.removeProgress(key)
                showToast(this, if (watched) "Marked as watched" else "Marked as not watched", Toast.LENGTH_SHORT)
                episodeAdapter.notifyItemChanged(index, "watched")
            },
        )
        episodesView.layoutManager = RowLayoutManager(this)
        episodesView.adapter = episodeAdapter

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val info = api.seriesInfo(item.id)
                setMeta(listOfNotNull(
                    validYear(info.releaseDate),
                    info.genre?.takeIf { it.isNotBlank() },
                ))
                plot.text = info.plot
                showCredits(info.cast?.let { "Cast: $it" })
                showTrailer(info.trailer)
                loadPoster(info.cover)
                showScores(info.tmdb, info.imdb, info.releaseDate, info.cover, item.name)
                seasons = info.seasons
                if (seasons.isEmpty()) {
                    showMessage("No episodes available.")
                } else {
                    seasonsView.visibility = View.VISIBLE
                    episodesView.visibility = View.VISIBLE
                    seasonAdapter.submit(seasons.keys.toList())
                    // Open on the season of the episode being watched, with Resume ready.
                    val resumeEp = updateSeriesResume(focus = true)
                    val startSeason = resumeEp?.season?.takeIf { it in seasons } ?: seasons.keys.first()
                    showSeason(seasons.keys.indexOf(startSeason), startSeason)
                    if (resumeEp != null) {
                        // Episode row starts at the one being watched.
                        (seasons[startSeason] ?: emptyList()).indexOfFirst { it.id == resumeEp.id }.takeIf { it > 0 }
                            ?.let { (episodesView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(it, 0) }
                    } else {
                        episodesView.post { episodesView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Couldn't load episodes.\n${e.message ?: ""}")
            }
            progress.visibility = View.GONE
        }
    }

    /** Resume button: the series' episode in Continue watching (it changes after watching more). */
    private fun updateSeriesResume(focus: Boolean): Episode? {
        val watching = prefs.continueWatching(ContentType.SERIES).firstOrNull { it.seriesId == item.id }
        val resumeEp = watching?.let { w -> seasons.values.flatten().firstOrNull { it.id == w.id } }
        if (resumeEp == null) {
            if (resumeButton.hasFocus()) favoriteButton.requestFocus()
            resumeButton.visibility = View.GONE
        } else {
            resumeButton.text = "▶  Resume ${resumeEp.label().take(28)}"
            resumeButton.visibility = View.VISIBLE
            // Resume was an explicit choice here, so the player doesn't ask again.
            resumeButton.setOnClickListener { playEpisode(resumeEp, askResume = false) }
            if (focus) resumeButton.requestFocus()
        }
        buttons.post { alignButtons() }
        return resumeEp
    }

    private fun showSeason(index: Int, season: Int) {
        seasonAdapter.activeIndex = index
        currentSeason = season
        episodeAdapter.submit(withComing(season))
        episodesView.scrollToPosition(0)
    }

    private fun episodeKey(ep: Episode) = "ep_${ep.id}"

    private fun bindEpisode(vh: TextAdapter.VH, ep: Episode) {
        vh.itemView.alpha = if (ep.id == COMING_ID) 0.55f else 1f
        if (ep.id == COMING_ID) {
            val air = extras?.next?.airAt ?: 0L
            vh.title.text = "S%02d.E%02d  ·  %s".format(ep.season, ep.number ?: 0, ep.title)
            vh.subtitle?.text = if (air > System.currentTimeMillis()) "Coming " + java.text.SimpleDateFormat(
                if (air - System.currentTimeMillis() <= 30L * 86_400_000) "EEE d MMM" else "MMMM yyyy", java.util.Locale.getDefault(),
            ).format(java.util.Date(air)) else "Not in your playlist yet"
            vh.itemView.findViewById<ImageView?>(R.id.image)?.loadEpisodeImage(ep, posterUrl)
            vh.itemView.findViewById<ProgressBar?>(R.id.watch_progress)?.visibility = View.GONE
            return
        }
        // Many panels already put "S01 E02" in the title; don't repeat the number.
        val prefix = if (ep.number != null && !ep.hasNumberInTitle()) "E${ep.number}  ·  " else ""
        vh.title.text = prefix + ep.title
        val key = episodeKey(ep)
        val pos = prefs.position(key)
        val watched = prefs.isWatched(key)
        val length = parseLength(ep.duration)
        val next = extras?.next
        if (next != null && next.season == ep.season && next.number == ep.number && next.airAt > System.currentTimeMillis()) {
            vh.itemView.alpha = 0.55f  // faded like the other coming episodes
            vh.subtitle?.text = "Coming " + java.text.SimpleDateFormat(
                if (next.airAt - System.currentTimeMillis() <= 30L * 86_400_000) "EEE d MMM" else "MMMM yyyy", java.util.Locale.getDefault(),
            ).format(java.util.Date(next.airAt))
            vh.itemView.findViewById<ImageView?>(R.id.image)?.loadEpisodeImage(ep, posterUrl)
            vh.itemView.findViewById<ProgressBar?>(R.id.watch_progress)?.visibility = View.GONE
            return
        }
        vh.subtitle?.text = listOfNotNull(
            when {
                pos > 0 && length != null && length > pos -> "${(length - pos + 59_999) / 60_000} min left"
                pos > 0 -> "▶ Resume ${formatDuration(pos)}"
                watched -> "✓ Watched"
                else -> null
            },
            ep.duration?.let { durationText(it) },
        ).joinToString("   ·   ")
        vh.itemView.findViewById<ImageView?>(R.id.image)?.loadEpisodeImage(ep, posterUrl)
        vh.itemView.findViewById<ProgressBar?>(R.id.watch_progress)?.apply {
            val fraction = when {
                watched -> 1f
                pos > 0 && length != null && length > 0 -> (pos.toFloat() / length).coerceIn(0f, 1f)
                else -> -1f
            }
            visibility = if (fraction >= 0f) View.VISIBLE else View.GONE
            progress = (fraction.coerceAtLeast(0f) * 1000).toInt()
        }
    }

    /** "01:48:53" -> "1 h 48 min", "00:35:23" -> "35 min" (other text unchanged). */
    private fun durationText(text: String): String {
        val ms = parseLength(text) ?: return text
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }

    private fun parseLength(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(':').map { it.trim().toLongOrNull() ?: return null }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            2 -> (parts[0] * 60 + parts[1]) * 1000
            else -> null
        }
    }

    private fun playEpisode(ep: Episode, askResume: Boolean = true) {
        val episodeLabel = ep.label()
        val seriesName = title.text.toString()
        val key = episodeKey(ep)
        // The player uses this for next/previous episode and its episode list.
        Session.seriesQueue = SeriesQueue(item.id, seriesName, posterUrl, seasons.values.flatten())
        val entry = WatchEntry(key, ContentType.SERIES, ep.id, ep.ext, seriesName, episodeLabel, posterUrl, seriesId = item.id)
        startActivity(PlayerActivity.vodIntent(this, api.episodeUrl(ep.id, ep.ext), "$seriesName  ·  $episodeLabel", key, prefs.position(key), entry, askResume))
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }
}
