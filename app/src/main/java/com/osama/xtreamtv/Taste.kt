package com.osama.xtreamtv

import android.content.Context

/**
 * Package 3 (approved 2026-11-13): how alike two titles are, from what the notebook saved about them
 * (film series, keywords, director, main cast, genres, language, year). Used by More like this and
 * Picked for You. Everything stays on the TV; nothing is asked from anyone here.
 */
object Taste {

    // ---- The notebook's facts, kept in memory for a few minutes ----

    @Volatile private var data: RatingsDb.TasteData? = null
    @Volatile private var dataFor: String? = null
    @Volatile private var dataAt = 0L

    /** The facts for [playlist]; read again after 10 minutes (the fill keeps adding). Off the main thread. */
    internal fun data(playlist: String): RatingsDb.TasteData? {
        val now = System.currentTimeMillis()
        data?.takeIf { dataFor == playlist && now - dataAt < 10 * 60_000L }?.let { return it }
        val fresh = runCatching { Ratings.db.tasteData(playlist) }.onFailure { android.util.Log.w("Taste", "notebook read failed", it) }.getOrNull() ?: return data?.takeIf { dataFor == playlist }
        data = fresh
        dataFor = playlist
        dataAt = now
        return fresh
    }

    /** The provider's own TMDB id as a work ("tmdb/movie/603"). */
    fun providerRef(s: StreamItem): String? = s.tmdb?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it != "0" }
        ?.let { "tmdb/${if (s.type == ContentType.MOVIE) "movie" else "show"}/$it" }

    internal fun refOf(d: RatingsDb.TasteData?, s: StreamItem): String? = d?.refOf?.get("${s.type.name}:${s.id}")
        // Matched moments ago (in memory, not yet written to the notebook), else the provider's own id.
        ?: runCatching { Ratings.matchOf(s.type, s.ratingId ?: s.id) }.getOrNull()?.takeIf { it.startsWith("tmdb/") || it.startsWith("imdb/") }
        ?: providerRef(s)

    /** A title being compared against: its facts as sets, made once. */
    private class Source(val f: RatingsDb.WorkFacts) {
        val keywords = f.keywords.toHashSet()
        val genres = f.genres.toHashSet()
        val directors = f.directors.toHashSet()
        val cast = f.cast.toHashSet()
    }

    /** A rare keyword ("time loop") says more than a common one ("based on novel"). */
    private fun weight(d: RatingsDb.TasteData, keyword: Int): Double {
        val n = d.works.size.coerceAtLeast(1).toDouble()
        val has = (d.keywordCounts[keyword] ?: 0) + 1.0
        return kotlin.math.ln(n / has).coerceIn(0.5, 4.0)
    }

    /** How alike: the same film series counts most, then shared keywords, director, cast, genres, language, year. */
    private fun alike(d: RatingsDb.TasteData, a: Source, b: RatingsDb.WorkFacts): Double {
        var s = 0.0
        if (a.f.coll != 0 && a.f.coll == b.coll) s += 100.0
        var kw = 0.0
        for (k in b.keywords) if (k in a.keywords) kw += weight(d, k) * 2
        s += kw.coerceAtMost(30.0)
        if (b.directors.any { it in a.directors }) s += 12.0
        s += (b.cast.count { it in a.cast } * 5.0).coerceAtMost(15.0)
        s += (b.genres.count { it in a.genres } * 3.0).coerceAtMost(9.0)
        if (a.f.lang != null && a.f.lang == b.lang) s += 4.0
        if (a.f.year > 0 && b.year > 0) {
            val gap = kotlin.math.abs(a.f.year - b.year)
            if (gap <= 3) s += 3.0 else if (gap <= 10) s += 1.0
        }
        return s
    }

    private fun words(genre: String?): Set<String> =
        genre?.split('/', ',', '&', '|')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** Copies (other qualities, dubs) count as one: the same work, else the same clean name. */
    internal fun sameKey(d: RatingsDb.TasteData?, s: StreamItem): String =
        refOf(d, s) ?: "name:${s.type.name}:${Versions.clean(s.name, s.year).lowercase()}"

    // ---- More like this (item 2) ----

    /**
     * Up to [limit] titles of [all] (the same kind as [item]) most like it: the same film series first,
     * unwatched before watched, never a copy of [item]. [recommended]: ids MDBList recommends (extra points).
     * Off the main thread.
     */
    fun moreLikeThis(context: Context, item: StreamItem, all: List<StreamItem>, recommended: Set<String>, watched: (StreamItem) -> Boolean, limit: Int = 20): List<StreamItem> {
        val pl = Prefs(context).activeId ?: return emptyList()
        var d = data(pl)
        val me = all.firstOrNull { it.id == item.id } ?: item
        val myRef = refOf(d, me)
        val myName = Versions.clean(me.name, me.year).lowercase()
        val myHead = myName.substringBefore(' ')
        // A copy: the same work, or (no work known) the same clean name. The name is cleaned only when
        // its first word is there: 25,000 names would take too long.
        fun copy(s: StreamItem): Boolean {
            val r = refOf(d, s)
            if (r != null && myRef != null) return r == myRef
            return s.name.contains(myHead, ignoreCase = true) && Versions.clean(s.name, s.year).lowercase() == myName
        }
        var myFacts = refOf(d, me)?.let { d?.works?.get(it) }
        // The page's own title not completed by the fill yet: TMDB is asked for it now (one request),
        // saved like the fill would, and the notebook read again.
        if (myFacts == null && myRef != null && myRef.startsWith("tmdb/") && Tmdb.hasKey) {
            val found = runCatching { Tmdb.details(item.type, myRef) }.getOrNull()?.found
            if (found != null) {
                runCatching { Ratings.db.putWorkDetail(myRef, found.toDetail()) }
                dataAt = 0L
                d = data(pl)
                myFacts = d?.works?.get(myRef)
            }
        }
        val source = myFacts?.let(::Source)
        if (BuildConfig.DEBUG) android.util.Log.d("Taste", "alike: works ${d?.works?.size} titles ${d?.refOf?.size} ref ${myRef != null} facts ${myFacts != null}")
        val myWords = words(me.genre)
        val cats = me.categoryIds.toSet()
        val scored = ArrayList<Pair<StreamItem, Double>>()
        for (s in all) {
            if (s.id == item.id || s.type != item.type) continue
            if (copy(s)) continue
            var p = 0.0
            val f = refOf(d, s)?.let { d?.works?.get(it) }
            if (source != null && f != null) p += alike(d!!, source, f)
            else p += (words(s.genre).count { it in myWords } * 3.0).coerceAtMost(9.0)
            if (s.id in recommended) p += 25.0
            if (s.categoryIds.any { it in cats }) p += 2.0
            if (p < 3.0) continue
            p += Ratings.scoreOf(s) * 0.3
            scored.add(s to p)
        }
        val ordered = scored.sortedByDescending { it.second }.take(limit * 10).map { it.first }.distinctBy { sameKey(d, it) }.take(limit * 3)
        val (unseen, seen) = ordered.partition { !watched(it) }
        return cards(context, (unseen + seen).take(limit), all, d)
    }

    // ---- Picked for You (item 1) ----

    /** A title in Picked for You, and why it is there ("Because you watched Interstellar"), when known. */
    class Pick(val item: StreamItem, val because: String?)

    /** [personal] false: fewer than 3 titles watched yet, so the row shows the best rated ("Top picks for you"). */
    class Picks(val personal: Boolean, val list: List<Pick>)

    @Volatile private var memo: Pair<String, Picks>? = null

    /**
     * Picked for You from [movies] and [series] ([kind] null: both, mixed). Watched means more than 30%
     * or finished; favourites count double; "Not interested" pushes alike titles down. The same answer
     * all day, until something new is watched or marked. Off the main thread.
     */
    fun picked(context: Context, movies: List<StreamItem>, series: List<StreamItem>, kind: ContentType?, limit: Int = 20): Picks {
        val prefs = Prefs(context)
        val pl = prefs.activeId ?: return Picks(false, emptyList())
        val watchedMovies = prefs.watchedKeys().filter { it.startsWith("movie_") }.map { it.removePrefix("movie_") }.toSet()
        val going = prefs.continueWatching(ContentType.MOVIE) + prefs.continueWatching(ContentType.SERIES)
        val startedMovies = going.filter { it.type == ContentType.MOVIE }.map { it.id }.toSet()
        val farMovies = going.filter { it.type == ContentType.MOVIE && it.duration > 0 && it.position.toDouble() / it.duration > 0.3 }.map { it.id }
        val seriesWatched = going.mapNotNull { it.seriesId }.toSet()
        val favMovies = prefs.favoriteIds(ContentType.MOVIE)
        val favSeries = prefs.favoriteIds(ContentType.SERIES)
        val no = prefs.notInterested()
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val stamp = listOf(pl, day, kind?.name, limit, movies.size, series.size, watchedMovies.sorted().hashCode(), farMovies.sorted().hashCode(),
            seriesWatched.sorted().hashCode(), favMovies.sorted().hashCode(), favSeries.sorted().hashCode(), no.sorted().hashCode()).joinToString("|")
        memo?.takeIf { it.first == stamp }?.let { return it.second }

        val byIdMovie = movies.associateBy { it.id }
        val byIdSeries = series.associateBy { it.id }
        // What the owner likes: (title, weight, "watched" or "favorite").
        val likes = ArrayList<Triple<StreamItem, Double, Boolean>>()
        for (id in (watchedMovies + farMovies).distinct()) byIdMovie[id]?.let { likes.add(Triple(it, 1.0, true)) }
        for (id in seriesWatched) byIdSeries[id]?.let { likes.add(Triple(it, 1.0, true)) }
        for (id in favMovies) byIdMovie[id]?.let { likes.add(Triple(it, 2.0, false)) }
        for (id in favSeries) byIdSeries[id]?.let { likes.add(Triple(it, 2.0, false)) }
        val watchedCount = (watchedMovies + farMovies).distinct().size + seriesWatched.size

        val d = data(pl)
        val pool = when (kind) { ContentType.MOVIE -> movies; ContentType.SERIES -> series; else -> movies + series }
        val skipIds = HashSet<String>().apply {
            addAll(watchedMovies.map { "MOVIE:$it" }); addAll(startedMovies.map { "MOVIE:$it" }); addAll(seriesWatched.map { "SERIES:$it" })
            addAll(favMovies.map { "MOVIE:$it" }); addAll(favSeries.map { "SERIES:$it" }); addAll(no)
        }
        val likedRefs = likes.mapNotNull { refOf(d, it.first) }.toHashSet()
        // Copies of liked titles are left out by their work (titles with no work have no facts to pick by).
        fun allowed(s: StreamItem) = "${s.type.name}:${s.id}" !in skipIds && (refOf(d, s)?.let { it !in likedRefs } ?: true)

        val result = if (watchedCount < 3 || d == null) {
            // Top picks in your playlist: the best rated not yet watched.
            val top = Ratings.topRated(pool, limit * 6).filter(::allowed).distinctBy { sameKey(d, it) }.take(limit)
            Picks(false, top.map { Pick(it, null) })
        } else {
            val sources = likes.mapNotNull { (s, w, watched) ->
                val f = refOf(d, s)?.let { d.works[it] } ?: return@mapNotNull null
                Triple(Source(f), w, (if (watched) "Because you watched " else "Because you like ") + Versions.clean(s.name, s.year))
            }
            val against = no.mapNotNull { key ->
                val type = runCatching { ContentType.valueOf(key.substringBefore(':')) }.getOrNull() ?: return@mapNotNull null
                val s = (if (type == ContentType.MOVIE) byIdMovie else byIdSeries)[key.substringAfter(':')] ?: return@mapNotNull null
                refOf(d, s)?.let { d.works[it] }?.let(::Source)
            }
            val scored = ArrayList<Triple<StreamItem, Double, String?>>()
            for (c in pool) {
                if (!allowed(c)) continue
                val f = refOf(d, c)?.let { d.works[it] } ?: continue
                var total = 0.0
                var best = 0.0
                var why: String? = null
                for ((src, w, text) in sources) {
                    val a = alike(d, src, f) * w
                    total += a
                    if (a > best) { best = a; why = text }
                }
                for (src in against) total -= alike(d, src, f) * 0.6
                if (best < 6.0) continue
                total += (f.imdb.takeIf { it > 0f } ?: Ratings.scoreOf(c).toFloat()) * 0.5
                scored.add(Triple(c, total, why))
            }
            val picks = scored.sortedByDescending { it.second }.distinctBy { sameKey(d, it.first) }.take(limit).map { Pick(it.first, it.third) }
            // Not enough alike yet (the notebook is still filling): the best rated fill the row.
            val more = if (picks.size >= limit) emptyList() else Ratings.topRated(pool, limit * 6).filter(::allowed)
                .filter { t -> picks.none { sameKey(d, it.item) == sameKey(d, t) } }.distinctBy { sameKey(d, it) }.take(limit - picks.size).map { Pick(it, null) }
            Picks(true, picks + more)
        }
        val shown = cards(context, result.list.map { it.item }, movies + series, d)
        val final = Picks(result.personal, result.list.zip(shown) { p, card -> Pick(card, p.because) })
        memo = stamp to final
        return final
    }

    /**
     * The card for each title of [list]: among its copies in [all], the best one; copies in different
     * languages show the official poster ([Versions.represent]).
     */
    internal fun cards(context: Context, list: List<StreamItem>, all: List<StreamItem>, d: RatingsDb.TasteData?): List<StreamItem> {
        val wanted = list.mapNotNull { refOf(d, it) }.toHashSet()
        if (wanted.isEmpty()) return list
        val byRef = HashMap<String, ArrayList<StreamItem>>()
        for (s in all) { val r = refOf(d, s) ?: continue; if (r in wanted) byRef.getOrPut(r) { ArrayList() }.add(s) }
        val learned = Versions.learned(context)
        return list.map { s -> refOf(d, s)?.let { byRef[it] }?.takeIf { it.size > 1 }?.let { Versions.represent(it, learned) } ?: s }
    }

    /** The TV is short of memory: the notebook copy and the day's picks go (read again when needed). */
    fun release() { data = null; dataFor = null; dataAt = 0L; memo = null }

    /** The notebook changed (a page completed itself): read again next time. */
    fun invalidate() { dataAt = 0L }

    /** After "Not interested" or a change of playlist: worked out again next time. */
    fun forget() { memo = null }
}
