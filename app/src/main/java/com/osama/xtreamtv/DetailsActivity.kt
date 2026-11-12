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
    private lateinit var meta: android.view.ViewGroup
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

    /** When the screen opened (diagnostics: how long its content took to be ready). */
    private val openedAt = android.os.SystemClock.uptimeMillis()

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
        // The chips row wraps to a second line when it doesn't fit (a long genre list pushed the award
        // chip off the edge, 2026-11-10): the layout's plain row is swapped for a wrapping one.
        meta = findViewById<android.view.ViewGroup>(R.id.meta).let { old ->
            val parent = old.parent as android.view.ViewGroup
            val at = parent.indexOfChild(old)
            val row = FlowRow(this).also { it.id = R.id.meta }
            val lp = old.layoutParams.also { it.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT }
            parent.removeViewAt(at)
            parent.addView(row, at, lp)
            row
        }
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

        title.text = Versions.shownName(item)
        setMeta(emptyList())
        loadPoster(item.image)
        loadVersions()

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
                    // The names keep to one line each before the description gives way.
                    trimNames() -> Unit
                    plot.visibility != View.GONE && plot.maxLines > 1 -> plot.maxLines = 1
                    plot.visibility != View.GONE -> plot.visibility = View.GONE
                }
            }
        }
    }

    /** The provider has no picture for this title, or its picture didn't load: MDBList's is used once known. */
    private var posterMissing = false

    private fun useMdbPoster() {
        val link = extras?.poster ?: return
        if (!posterMissing) return
        posterMissing = false
        loadPoster(link, fromMdbList = true)
        // Episode cards without a still show the poster.
        if (!isMovie && ::episodeAdapter.isInitialized) episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.itemCount)
    }

    private fun loadPoster(url: String?, fromMdbList: Boolean = false) {
        if (url.isNullOrBlank()) {
            if (posterUrl == null) {
                posterMissing = true
                useMdbPoster()
            }
            return
        }
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
                // The provider's picture is gone (a dead link): MDBList's poster instead.
                if (!fromMdbList) listener(onError = { _, _ ->
                    if (posterUrl == url) {
                        posterUrl = null
                        posterMissing = true
                        useMdbPoster()
                    }
                })
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

    // ---- Versions (package 2, item 2): information only; the choice is made in the player ----

    /** The title's copies in other qualities and languages (itself only when there are none). */
    private var copies: Versions.CopySet? = null
    /** Chips after year, length and genre: the best quality this TV can play, then each language. */
    private var versionChips: List<String> = emptyList()

    /** A copy's real height, from the provider's details: saved, and the chips follow. */
    private fun noteQuality(id: String, height: Int) {
        Versions.noteHeight(this, item.type, id, height)
        val set = copies ?: return
        val learned = HashMap(set.learned).apply { put("${item.type.name}:$id", height) }
        copies = Versions.CopySet(set.copies, set.original, learned)
        showVersionChips()
    }

    private fun showVersionChips() {
        val set = copies ?: return
        val best = set.copies.map { Versions.height(it, set.learned) }.filter { Versions.playable(it) }.maxOrNull() ?: 0
        val langs = Versions.order(set.copies, set.learned).map { it.lang }.distinct()
        versionChips = listOfNotNull(Versions.qualityLabel(best)) + langs.map { Versions.languageLabel(it, set.original) }
        setMeta(metaValues)
    }

    private fun loadVersions() {
        lifecycleScope.launch {
            val set = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Versions.of(this@DetailsActivity, api, item) }.getOrNull() }
                ?: return@launch
            // A height the page already learned from the provider while this loaded is kept.
            copies = copies?.let { old -> Versions.CopySet(set.copies, set.original, set.learned + old.learned) } ?: set
            showVersionChips()
        }
    }

    /**
     * The copy Play starts: this one when it is being watched; otherwise the best copy (its original
     * language first, then the highest quality this TV can play).
     */
    private fun playCopy(): StreamItem {
        if (prefs.position(movieKey()) > 0) return item
        val set = copies ?: return item
        return Versions.best(set.copies, set.learned)?.item ?: item
    }

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
                    .apply { rightMargin = (8 * density).toInt(); bottomMargin = (6 * density).toInt() }
            })
        }
        for (v in (values + versionChips).filter { it.isNotBlank() }) {
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
                    .apply { rightMargin = (8 * density).toInt(); bottomMargin = (6 * density).toInt() }
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
        if (!Ratings.isOn) return showSimilarOnly()
        baseMeta = metaValues
        Ratings.known(item.type, item.id)?.let { scores = it; setMeta(metaValues) }
        val title = Ratings.Title(item.type, item.id, name, releaseDate?.let { Regex("(19|20)\\d{2}").find(it)?.value }, tmdb, imdb, item.image ?: cover,
            originalName?.takeIf { it != name })
        // More like this needs the whole list and its name index: get them ready meanwhile.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val all = runCatching { Session.all(api, item.type) }.getOrNull()
            all?.let { Ratings.nameIndex(it) }
        }
        lifecycleScope.launch {
            val found = Ratings.lookup(title, onScreen = true)
            scores = found
            if (found != null) Diag.ready("Details", "scores", openedAt)
            setMeta(metaValues)
            // No trailer from the provider: MDBList's, when it has one (asked once more for titles
            // saved before trailers were kept).
            if (findViewById<Button>(R.id.trailer).visibility != View.VISIBLE) {
                if (Ratings.needsTrailer(item.type, item.id)) Ratings.lookup(title, onScreen = true, fresh = true)
                Ratings.trailer(item.type, item.id)?.let { showTrailer(it); buttons.post { alignButtons() } }
            }
            // "Wrong title?" only where the match is not sure (package 2, item 5): by name, or by TMDB
            // without its poster. Everywhere else the red button's Mark a problem offers the same window.
            val wrong = findViewById<Button?>(R.id.wrong_scores) ?: return@launch
            val unsure = found != null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Ratings.notSure(item.type, item.id) }
            wrong.text = "Wrong title?"
            wrong.visibility = if (unsure) View.VISIBLE else View.GONE
            wrong.setOnClickListener { openWrongTitle() }
            buttons.post { alignButtons() }
            if (found != null || Ratings.how(item.type, item.id) == Ratings.How.ID) showExtras() else showSimilarOnly()
        }
    }

    /** This title has scores: its match can be corrected (the red button offers it too). */
    fun canCorrectTitle(): Boolean = Ratings.isOn && scores != null

    /** "Which title is this?": the owner's choice replaces the match; None removes the scores. */
    fun openWrongTitle() {
        WrongTitleUi.show(this, lifecycleScope, item.type, item.id, title.text.toString(), onChosen = { ref ->
            lifecycleScope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Ratings.chooseMatch(item.type, item.id, ref)
                    // Its cast, story, genres and season years come with it.
                    Tmdb.details(item.type, ref).found?.let { f -> runCatching { Ratings.db.putWorkDetail(ref, f.toDetail()) } }
                }
                showToast(this@DetailsActivity, "Title changed. Its scores and details follow.", Toast.LENGTH_SHORT)
                // The page again, with the chosen title's scores and details.
                startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
                finish()
                overridePendingTransition(0, 0)
            }
        }, onNone = {
            Ratings.markWrong(item.type, item.id)
            scores = null
            setMeta(metaValues)
            findViewById<Button?>(R.id.wrong_scores)?.let { w -> if (w.hasFocus()) favoriteButton.requestFocus(); w.visibility = View.GONE }
            buttons.post { alignButtons() }
            showToast(this@DetailsActivity, "Scores removed. This title won't be matched again.", Toast.LENGTH_SHORT)
        })
    }

    /**
     * 1.1E extras on this page: what the provider left empty (description, year, length, genre), the
     * age rating and awards as chips, the next episode (series) and More like this.
     */
    private suspend fun showExtras() {
        val e = RatingsExtras.forTitle(item.type, item.id) ?: return showSimilarOnly()
        extras = e
        useMdbPoster()
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
                runCatching { Session.all(api, item.type) }.getOrNull() ?: emptyList()
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
            if (found.size < 3) {
                // Diagnostics: why the row isn't there.
                Diag.log("ui", "More like this not shown on Details${Diag.title(item.name)}: MDBList recommended ${recs.size}, ${fromMdbList.size} in the playlist, ${found.size} similar in the same category")
                return@launch
            }
            findViewById<TextView>(R.id.recos_title).text = if (similar) "Similar in your playlist" else "More like this"
            val view = findViewById<RecyclerView>(R.id.recos)
            val adapter = recosAdapter ?: ItemAdapter(
                grid = true,
                onClick = { _, it -> startActivity(intent(this@DetailsActivity, it)) },
                // Long-press: favourite or not, straight away (as in Movies and Series), with the star.
                onLongClick = { pos, it ->
                    val added = prefs.toggleFavorite(it)
                    recosAdapter?.let { a -> a.favorites = prefs.favoriteIds(it.type); a.notifyItemChanged(pos) }
                    showToast(this@DetailsActivity, if (added) "Added ${it.name} to favorites" else "Removed ${it.name} from favorites", Toast.LENGTH_SHORT)
                },
                layout = R.layout.item_poster_mini,
            ).also { a ->
                recosAdapter = a
                view.layoutManager = RowLayoutManager(this@DetailsActivity)
                view.adapter = a
            }
            // The favourite star on cards that are favourites.
            adapter.favorites = prefs.favoriteIds(item.type)
            adapter.submit(found)
            Diag.ready("Details", if (similar) "Similar in your playlist" else "More like this", openedAt)
            if (isMovie) {
                findViewById<View>(R.id.recos_title).visibility = View.VISIBLE
                view.visibility = View.VISIBLE
            } else {
                recosReady = true
            }
        }
    }

    /** The provider's genre for this title ("Action, Drama"), for Similar in your playlist. */
    private var pageGenre: String? = null

    /**
     * Similar in your playlist without MDBList (no key, or the title isn't matched): the same rule as
     * when MDBList has fewer than 3 recommendations in the playlist (owner, 2026-11-07).
     */
    private fun showSimilarOnly() {
        val genres = pageGenre?.split('/', ',', '&', '|')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        showRecos(RatingsExtras.Extras(0L, null, 0, 0, genres, null, null, null, null, null, emptyList()))
    }

    /** Titles like this one from the playlist: same category, most genres in common, best rated first. */
    private fun similarTitles(all: List<StreamItem>, e: RatingsExtras.Extras): List<StreamItem> {
        val me = all.firstOrNull { it.id == item.id } ?: item
        val cats = me.categoryIds.toSet()
        fun genresOf(s: StreamItem): Set<String> =
            (RatingsExtras.saved(s.type, s.id)?.genres ?: s.genre?.split('/', ',', '&', '|')?.map { it.trim() } ?: emptyList())
                .map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()
        val mine = (e.genres + (pageGenre?.split('/', ',', '&', '|') ?: emptyList())).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet() + genresOf(me)
        // Most genres in common first; then, to fill the row, the same category's best rated (many
        // provider lists carry no genre for each title, 2026-11-07).
        return all.asSequence()
            .filter { it.id != item.id && it.categoryIds.any { c -> c in cats } }
            .map { s -> s to (genresOf(s).intersect(mine).size * 10 + Ratings.scoreOf(s)) }
            .sortedWith(compareByDescending<Pair<StreamItem, Double>> { it.second }.thenByDescending { it.first.added })
            .map { it.first }
            .distinctBy { it.id }
            .take(12)
            .toList()
    }

    /** Series: Down from the episodes slides the top part away and shows More like this; Up brings it back. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && namesKey(event.keyCode)) return true
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

    /**
     * The director and cast (package 2, item 3): rows of names above the buttons; people with two or
     * more titles in the playlist are buttons with a photo that open their page. [text] is
     * "Director: …\nCast: …" as the page has it; the saved names (with photos) are used when known.
     */
    private fun showCredits(text: String?, asked: Boolean = false) {
        lastCredits = text
        if (!asked) peopleJob?.cancel()
        findViewById<TextView>(R.id.credits).visibility = View.GONE
        val lines = text?.lines().orEmpty()
        fun list(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            ?.split(',')?.map { it.trim() }?.filter { it.length >= 2 }.orEmpty()
        val givenDirectors = list("Director: ")
        val givenCast = list("Cast: ")
        namesJob?.cancel()
        namesJob = lifecycleScope.launch {
            val pl = prefs.activeId ?: return@launch
            val listRefs = providerRefs()
            val (directors, cast) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val saved = runCatching { Ratings.db.peopleOf(Ratings.keyOf(item.type, item.id)) }.getOrDefault(emptyList())
                val d = saved.filter { it.role == RatingsDb.Detail.DIRECTOR }.map { it.name to it.photo }.ifEmpty { givenDirectors.map { it to null } }
                val c = saved.filter { it.role == RatingsDb.Detail.ACTOR }.map { it.name to it.photo }.ifEmpty { givenCast.map { it to null } }
                val counts = runCatching { Ratings.db.titleCounts(pl, (d + c).map { it.first }, listRefs) }.getOrDefault(emptyMap())
                // A photo TMDB gave with the person's film list, for names the title's own list had none for.
                val photos = runCatching { Ratings.db.personPhotos((d + c).filter { it.second == null }.map { it.first }) }.getOrDefault(emptyMap())
                fun name(p: Pair<String, String?>) = NamesUi.Name(p.first, p.second ?: photos[p.first], counts[p.first] ?: 0)
                d.map(::name) to c.take(10).map(::name)
            }
            val parent = buttons.parent as? android.view.ViewGroup ?: return@launch
            // The name highlighted in the old rows gets the highlight back in the new ones.
            val focusedName = parent.findViewWithTag<View>("names_rows")?.let { old -> currentFocus?.takeIf { isInside(it, old) }?.contentDescription?.toString() }
            parent.findViewWithTag<View>("names_rows")?.let { parent.removeView(it) }
            if (!asked) askPeople(pl, (directors + cast).filter { it.count < 2 }.map { it.text })
            if (directors.isEmpty() && cast.isEmpty()) return@launch
            val rows = NamesUi.rows(this@DetailsActivity, if (isMovie) "Director" else "Created by", directors, cast) { name ->
                startActivity(PersonActivity.intent(this@DetailsActivity, name))
            }
            // Added while the page settles: the highlight stays where the page put it (Play, or the episode).
            val had = currentFocus
            parent.addView(rows, parent.indexOfChild(buttons))
            if (focusedName != null) {
                rows.post {
                    val again = ArrayList<View>().also { rows.findViewsWithText(it, focusedName, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION) }.firstOrNull()
                    (again ?: if (playButton.isShown) playButton else null)?.requestFocus() ?: focusDefault()
                }
                return@launch
            }
            rows.post {
                val now = currentFocus
                // The page's own highlight (not the scrolling area, nor a name it just landed on) stays.
                val real = had != null && had.isAttachedToWindow && had.isShown && had !is android.widget.ScrollView && !isInside(had, rows)
                if (now == null || now is android.widget.ScrollView || isInside(now, rows)) { if (real) had!!.requestFocus() else focusDefault() }
            }
        }
    }

    private var namesJob: kotlinx.coroutines.Job? = null
    private var peopleJob: kotlinx.coroutines.Job? = null
    private var lastCredits: String? = null
    private var listRefsCache: Set<String>? = null

    /** The provider's own TMDB ids for this kind of title ("tmdb/movie/603"), for titles the notebook hasn't reached yet. */
    private suspend fun providerRefs(): Set<String> = listRefsCache ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val kind = if (item.type == ContentType.MOVIE) "movie" else "show"
        val all = runCatching { Session.all(api, item.type) }.getOrDefault(emptyList())
        all.mapNotNullTo(HashSet()) { s -> s.tmdb?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it != "0" }?.let { "tmdb/$kind/$it" } }
    }.also { if (it.isNotEmpty()) listRefsCache = it }

    /**
     * Names that can't be pressed yet because the notebook knows fewer than two of their titles:
     * TMDB is asked for each one's film list (once a month at most), one person at a time, and the
     * rows are drawn again when someone turns out to have two or more (owner, 2026-11-12).
     */
    private fun askPeople(pl: String, names: List<String>) {
        if (names.isEmpty() || !Tmdb.hasKey) return
        peopleJob?.cancel()
        peopleJob = lifecycleScope.launch {
            val listRefs = providerRefs()
            val todo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val asked = runCatching { Ratings.db.personAsked(names) }.getOrDefault(emptySet())
                names.distinct().filter { it !in asked }
            }
            if (todo.isEmpty()) return@launch
            var gained = 0
            for (name in todo) {
                val films = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Tmdb.personFilms(name) }.getOrNull() } ?: break
                val now = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { Ratings.db.savePersonWorks(name, films.photo, films.works); Ratings.db.titleCounts(pl, listOf(name), listRefs)[name] ?: 0 }.getOrDefault(0)
                }
                Diag.log("names", "TMDB film list · ${films.works.size} works · $now in the playlist${Diag.title(name)}")
                if (now >= 2) gained++
            }
            if (gained > 0) showCredits(lastCredits, asked = true)
        }
    }

    /**
     * Up and Down between the buttons and the names rows go row by row, to the first name you can
     * press in the next row (Android's nearest-guess skipped a row whose only button sat far right).
     * Rows with nothing to press are passed over.
     */
    private fun namesKey(keyCode: Int): Boolean {
        val side = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        if (!side && keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP && keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return false
        val rows = (buttons.parent as? android.view.ViewGroup)?.findViewWithTag<android.view.ViewGroup>("names_rows") ?: return false
        val f = currentFocus ?: return false
        val lines = (0 until rows.childCount).map { rows.getChildAt(it) }
        // Each row (Director, Cast) is one group: Left and Right go name by name in reading order,
        // also across the row's second line, and stop at its ends; they never leave the row
        // (owner, 2026-11-12: Left from a name jumped to More like this and the last name was unreachable).
        if (side) {
            val line = lines.firstOrNull { isInside(f, it) } ?: return false
            val flow = (line as? android.view.ViewGroup)?.getChildAt(1) as? android.view.ViewGroup ?: return true
            val names = (0 until flow.childCount).map { flow.getChildAt(it) }.filter { it.isFocusable && it.isShown }
            val i = names.indexOfFirst { it === f || isInside(f, it) }
            if (i < 0) return true
            names.getOrNull(if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) i + 1 else i - 1)?.requestFocus()
            return true
        }
        fun firstButton(line: View): View? = ((line as? android.view.ViewGroup)?.getChildAt(1) as? android.view.ViewGroup)?.let { flow ->
            (0 until flow.childCount).map { flow.getChildAt(it) }.firstOrNull { it.isFocusable && it.isShown }
        }
        val pressable = lines.filter { firstButton(it) != null }
        if (pressable.isEmpty()) return false
        val up = keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
        val inButtons = isInside(f, buttons)
        val at = pressable.indexOfFirst { isInside(f, it) }
        val target: View? = when {
            inButtons && up -> firstButton(pressable.last())
            at >= 0 && up -> pressable.getOrNull(at - 1)?.let(::firstButton) ?: return true
            at >= 0 && !up -> pressable.getOrNull(at + 1)?.let(::firstButton)
                ?: (buttons as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it.isFocusable && it.isShown } }
            else -> null
        }
        target ?: return false
        target.requestFocus()
        return true
    }

    /** A names row on more than one line loses its last name (plain names first). True when one went. */
    private fun trimNames(): Boolean {
        val rows = (buttons.parent as? android.view.ViewGroup)?.findViewWithTag<android.view.ViewGroup>("names_rows") ?: return false
        for (r in (rows.childCount - 1) downTo 0) {
            val flow = (rows.getChildAt(r) as? android.view.ViewGroup)?.getChildAt(1) as? FlowRow ?: continue
            if (flow.childCount < 2) continue
            val first = flow.getChildAt(0)
            if (flow.height <= first.height * 3 / 2 + first.paddingTop) continue
            val plain = (flow.childCount - 1 downTo 0).firstOrNull { !flow.getChildAt(it).isFocusable } ?: (flow.childCount - 1)
            if (flow.getChildAt(plain).hasFocus()) continue
            flow.removeViewAt(plain)
            return true
        }
        return false
    }

    /** Where the page puts the highlight: Play (or Resume) on a movie, the episode being watched on a series. */
    private fun focusDefault() {
        if (isMovie) { (if (resumeButton.isShown) resumeButton else playButton).requestFocus(); return }
        val lm = episodesView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
        val at = lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
        (lm?.findViewByPosition(at) ?: episodesView).requestFocus()
    }

    /** Season number -> the year it came out (TMDB, saved by the fill); only seasons TMDB lists. */
    private var seasonYears: Map<Int, Int> = emptyMap()

    private fun loadSeasonYears() {
        lifecycleScope.launch {
            val map = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { Ratings.db.seasonYears(Ratings.keyOf(item.type, item.id)) }.getOrDefault(emptyMap())
            }
            if (map.isEmpty()) return@launch
            seasonYears = map
            if (::seasonAdapter.isInitialized) seasonAdapter.notifyItemRangeChanged(0, seasonAdapter.itemCount)
        }
    }

    private fun isInside(v: View, box: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === box) return true; p = p.parent as? View }
        return false
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
            val saved = showSaved()
            try {
                val info = api.vodInfo(item.id)
                info.name?.let { title.text = Versions.clean(it, info.releaseDate ?: item.year) }
                // The provider says the picture size: the quality chip needs no playing first.
                if (info.height > 0) noteQuality(item.id, info.height)
                if (info.ext != null) ext = info.ext
                // What the provider leaves empty keeps what the TV has saved.
                val genre = info.genre?.takeIf { it.isNotBlank() } ?: saved?.genres
                setMeta(listOfNotNull(
                    validYear(info.releaseDate) ?: saved?.year?.takeIf { it > 0 }?.toString(),
                    info.duration?.takeIf { parseLength(it)?.let { ms -> ms > 0 } ?: true }?.let { durationText(it) } ?: saved?.minutes?.let(::minutesText),
                    genre,
                ))
                pageGenre = genre
                Diag.ready("Details", "page", openedAt)
                plot.text = info.plot?.takeIf { it.isNotBlank() } ?: saved?.plot
                showCredits(listOfNotNull(
                    (info.director?.takeIf { it.isNotBlank() } ?: saved?.director)?.let { "Director: $it" },
                    (info.cast?.takeIf { it.isNotBlank() } ?: saved?.cast)?.let { "Cast: $it" },
                ).joinToString("\n").ifEmpty { null })
                showTrailer(info.trailer)
                loadPoster(info.cover)
                showScores(info.tmdb, info.imdb, info.releaseDate, info.cover, info.name ?: item.name, info.originalName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The provider did not answer: the page keeps what the TV has saved, and its scores.
                plot.text = saved?.plot ?: if (saved == null) "Details unavailable." else "The story is not available right now."
                if (saved != null) showScores(null, null, saved.year.takeIf { it > 0 }?.toString(), item.image, item.name)
            }
            progress.visibility = View.GONE
        }
    }

    /**
     * Year, length, genre, director and cast the TV already has for this title (saved by the ratings
     * fill or an earlier visit), shown at once, before the provider answers. The provider's answer
     * then adds the story and the trailer. Returns what was shown, or null when nothing is saved.
     */
    private suspend fun showSaved(): RatingsDb.Saved? {
        val key = Ratings.keyOf(item.type, item.id)
        val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Ratings.db.saved(key) }.getOrNull() } ?: return null
        setMeta(listOfNotNull(saved.year.takeIf { it > 0 }?.toString(), minutesText(saved.minutes), saved.genres))
        pageGenre = saved.genres
        saved.plot?.let { plot.text = it }
        showCredits(listOfNotNull(
            saved.director?.takeIf { item.type == ContentType.MOVIE }?.let { "Director: $it" },
            saved.cast?.let { "Cast: $it" },
        ).joinToString("\n").ifEmpty { null })
        Diag.ready("Details", "saved details", openedAt)
        return saved
    }

    private fun minutesText(minutes: Int): String? =
        if (minutes <= 0) null else if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

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
        // Play from the start: the best copy (Resume keeps the copy being watched).
        val copy = if (startMs == 0L) playCopy() else item
        val copyExt = if (copy.id == item.id) ext else copy.ext
        val key = "movie_${copy.id}"
        val entry = WatchEntry(key, ContentType.MOVIE, copy.id, copyExt, name, null, posterUrl)
        startActivity(PlayerActivity.vodIntent(this, api.movieUrl(copy.id, copyExt), name, key, startMs, entry, askResume = false))
    }

    // ---- Series ----

    private fun setupSeries() {
        playButton.visibility = View.GONE
        findViewById<View>(R.id.episodes_card).visibility = View.VISIBLE
        loadSeasonYears()

        seasonAdapter = TextAdapter(
            R.layout.item_category_chip,
            bind = { vh, season ->
                // Always the full name, with the year it came out when known (package 2, item 4, approved
                // 2026-11-12: "Season 1 · 2025"); many seasons scroll sideways rather than shrinking to "S1".
                val year = seasonYears[season]
                vh.title.text = when {
                    season == 0 -> "Extras"
                    year != null -> "Season $season · $year"
                    else -> "Season $season"
                }
                vh.subtitle?.text = ""
                vh.title.textSize = 16f
                val d = resources.displayMetrics.density
                vh.itemView.setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                (vh.itemView.layoutParams as android.view.ViewGroup.MarginLayoutParams).rightMargin = (10 * d).toInt()
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
            val saved = showSaved()
            try {
                val info = api.seriesInfo(item.id)
                val genre = info.genre?.takeIf { it.isNotBlank() } ?: saved?.genres
                setMeta(listOfNotNull(
                    validYear(info.releaseDate) ?: saved?.year?.takeIf { it > 0 }?.toString(),
                    genre,
                ))
                pageGenre = genre
                Diag.ready("Details", "page", openedAt)
                plot.text = info.plot?.takeIf { it.isNotBlank() } ?: saved?.plot
                showCredits((info.cast?.takeIf { it.isNotBlank() } ?: saved?.cast)?.let { "Cast: $it" })
                showTrailer(info.trailer)
                loadPoster(info.cover)
                showScores(info.tmdb, info.imdb, info.releaseDate, info.cover, item.name)
                seasons = info.seasons
                info.seasons.values.flatten().firstOrNull { it.height > 0 }?.let { noteQuality(item.id, it.height) }
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
