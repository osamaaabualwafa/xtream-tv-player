package com.osama.xtreamtv

import android.content.Context

/**
 * The Movies and Series filter (package 2, item 1, approved 2026-11-12): what the notebook knows about
 * each title of the open playlist, and the counting behind the quick line and the full panel.
 *
 * Sections: Watched, Genre, Year, IMDb score, Rotten Tomatoes, Awards, Length (movies only).
 * One [State] is shared by the line and the panel. Every choice shows how many titles it gives with
 * the other sections applied. Titles the fill has not completed (no facts) are left out while a
 * section that needs facts is set; "Not watched yet" alone needs none.
 *
 * Everything here runs on the TV from memory and the local database; nothing is sent anywhere.
 */
object TitleFilter {

    /** What is known about one title: year, length, IMDb, Rotten Tomatoes, award, genres. */
    class Fact(val year: Int, val minutes: Int, val imdb: Float, val rt: Int, val award: String?, val genres: List<String>, val ref: String)

    enum class Section(val label: String) {
        WATCHED("Watched"), GENRE("Genre"), YEAR("Year"), IMDB("IMDb"), TOMATOES("Tomatoes"), AWARDS("Awards"), LENGTH("Length")
    }

    /** The choices. Empty strings mean "Any" / "All". */
    data class State(
        val notWatched: Boolean = false,
        val genre: String = "",
        val year: String = "",
        val imdb: String = "",
        val tomatoes: String = "",
        val award: String = "",
        val length: String = "",
    ) {
        val count: Int get() = listOf(notWatched, genre.isNotEmpty(), year.isNotEmpty(), imdb.isNotEmpty(), tomatoes.isNotEmpty(), award.isNotEmpty(), length.isNotEmpty()).count { it }
        val needsFacts: Boolean get() = genre.isNotEmpty() || year.isNotEmpty() || imdb.isNotEmpty() || tomatoes.isNotEmpty() || award.isNotEmpty() || length.isNotEmpty()

        fun get(s: Section): String = when (s) {
            Section.WATCHED -> if (notWatched) NOT_WATCHED else ""
            Section.GENRE -> genre
            Section.YEAR -> year
            Section.IMDB -> imdb
            Section.TOMATOES -> tomatoes
            Section.AWARDS -> award
            Section.LENGTH -> length
        }

        fun with(s: Section, v: String): State = when (s) {
            Section.WATCHED -> copy(notWatched = v == NOT_WATCHED)
            Section.GENRE -> copy(genre = v)
            Section.YEAR -> copy(year = v)
            Section.IMDB -> copy(imdb = v)
            Section.TOMATOES -> copy(tomatoes = v)
            Section.AWARDS -> copy(award = v)
            Section.LENGTH -> copy(length = v)
        }
    }

    const val NOT_WATCHED = "Not watched yet"
    val YEARS = listOf("2025 and newer", "2020 and newer", "2010s", "Older")
    val SCORES = listOf("6 and up", "7 and up", "8 and up")
    val TOMATOES = listOf("Fresh", "Rotten")
    val LENGTHS = listOf("Under 90 min", "90 to 120 min", "Over 2 hours")

    fun sections(type: ContentType): List<Section> =
        Section.values().filter { it != Section.LENGTH || type == ContentType.MOVIE }

    /** How a chosen value reads on the line: "Genre: Action", "Not watched yet", "IMDb 7 and up". */
    fun lineLabel(s: Section, v: String): String = when {
        v.isEmpty() -> s.label
        s == Section.WATCHED -> v
        s == Section.IMDB -> "IMDb $v"
        s == Section.TOMATOES -> v
        s == Section.AWARDS -> v
        else -> "${s.label}: $v"
    }

    /** Section names in the full panel. */
    fun panelLabel(s: Section): String = when (s) {
        Section.IMDB -> "IMDb score"
        Section.TOMATOES -> "Rotten Tomatoes"
        else -> s.label
    }

    // ---- Facts, from the database (kept in memory, made again when scores change) ----

    @Volatile private var facts: Map<String, Fact> = emptyMap()
    @Volatile private var factsFor = ""
    @Volatile private var factsVersion = -1L
    @Volatile private var factsAt = 0L

    /** Facts of the active playlist, "TYPE:id" -> fact. Call off the screen's thread. */
    fun facts(context: Context): Map<String, Fact> {
        val pl = Prefs(context).activeId ?: return emptyMap()
        val v = Ratings.version
        val fresh = factsFor == pl && (factsVersion == v || System.currentTimeMillis() - factsAt < 60_000)
        if (fresh) return facts
        synchronized(this) {
            if (factsFor == pl && factsVersion == v) return facts
            runCatching { Ratings.flush() }
            val map = runCatching { Ratings.db.filterFacts(pl) }.getOrElse { return facts }
            facts = map; factsFor = pl; factsVersion = v; factsAt = System.currentTimeMillis()
            return map
        }
    }

    /** Genres as one list: "Action & Adventure" and "Action / Thriller" are split; the same genre in two spellings is one. */
    fun normaliseGenres(raw: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (r in raw) for (part in r.split('/', ',', '&', '|', '،')) {
            val p = part.trim().trim('.')
            if (p.length < 2) continue
            val key = p.lowercase()
            out.add(when (key) {
                "sci-fi", "science fiction", "scifi", "sci fi" -> "Science Fiction"
                "kids", "children" -> "Kids"
                "war & politics", "politics" -> "War"
                "documentary", "documentaries" -> "Documentary"
                else -> if (p.any { it in '؀'..'ۿ' }) p else p.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            })
        }
        return out.toList()
    }

    // ---- Matching and counting ----

    private val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    private fun matchOne(s: Section, v: String, f: Fact?, watched: Boolean): Boolean {
        if (v.isEmpty()) return true
        if (s == Section.WATCHED) return !watched
        f ?: return false
        return when (s) {
            Section.GENRE -> v in f.genres
            Section.YEAR -> when (v) {
                YEARS[0] -> f.year >= thisYear - 1
                YEARS[1] -> f.year >= 2020
                YEARS[2] -> f.year in 2010..2019
                else -> f.year in 1..2009
            }
            Section.IMDB -> !f.imdb.isNaN() && f.imdb >= v.substringBefore(' ').toFloat()
            Section.TOMATOES -> f.rt >= 0 && (if (v == TOMATOES[0]) f.rt >= 60 else f.rt < 60)
            Section.AWARDS -> f.award == v
            Section.LENGTH -> f.minutes > 0 && when (v) {
                LENGTHS[0] -> f.minutes < 90
                LENGTHS[1] -> f.minutes in 90..120
                else -> f.minutes > 120
            }
            Section.WATCHED -> true
        }
    }

    /** True when [item] passes every section of [state] except [skip]. */
    fun matches(state: State, f: Fact?, watched: Boolean, skip: Section? = null): Boolean {
        for (s in Section.values()) if (s != skip && !matchOne(s, state.get(s), f, watched)) return false
        return true
    }

    /** One section's choices with their counts, "Any" first; zero-count choices are kept (shown dimmed). */
    class Choices(val section: Section, val values: List<Pair<String, Int>>)

    class Result(val shown: List<StreamItem>, val withFacts: Int, val choices: Map<Section, Choices>, val shownTotal: Int)

    /**
     * The filtered list and every section's counts, for the items of the open category. [factOf] and
     * [watchedOf] look a title up. Call off the screen's thread: a category can hold 25,000 titles.
     */
    fun run(type: ContentType, items: List<StreamItem>, state: State, factOf: (StreamItem) -> Fact?, watchedOf: (StreamItem) -> Boolean): Result {
        val fs = items.map(factOf)
        val ws = items.map(watchedOf)
        val shown = items.filterIndexed { i, _ -> matches(state, fs[i], ws[i]) }
        val choices = HashMap<Section, Choices>()
        for (s in sections(type)) {
            // The other sections applied, this one left open: what each of its choices would give.
            val base = items.indices.filter { matches(state, fs[it], ws[it], skip = s) }
            val values: List<String> = when (s) {
                Section.WATCHED -> listOf(NOT_WATCHED)
                Section.GENRE -> base.flatMap { fs[it]?.genres.orEmpty() }.groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }.map { it.key }.let { if (state.genre.isNotEmpty() && state.genre !in it) it + state.genre else it }
                Section.YEAR -> YEARS
                Section.IMDB -> SCORES
                Section.TOMATOES -> TOMATOES
                // Only the awards this playlist's titles of this kind have (no Emmy for movies).
                Section.AWARDS -> items.indices.mapNotNull { fs[it]?.award }.distinct().sortedWith(compareBy({ !it.endsWith("winner") }, { it }))
                Section.LENGTH -> LENGTHS
            }
            val list = ArrayList<Pair<String, Int>>()
            list.add("" to base.size)
            for (v in values) list.add(v to base.count { matchOne(s, v, fs[it], ws[it]) })
            choices[s] = Choices(s, list)
        }
        return Result(shown, fs.count { it != null }, choices, items.size)
    }
}
