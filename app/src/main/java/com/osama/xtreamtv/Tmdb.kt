package com.osama.xtreamtv

import android.app.Application
import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TMDB (agreed with the owner 2026-11-10): recognises the titles that come without an id, for the
 * whole-playlist fill. Most providers' lists carry no id, and asking the provider for each movie's
 * details is slow (one every 5 s); TMDB finds a title by its name and year, and the same answer says
 * its cast, director, genres and length.
 *
 * A match is accepted only when it is safe:
 * - the provider's poster is the very file TMDB lists for that title (its main poster, or one of its
 *   other official posters); or
 * - exactly one TMDB title has the same name and the same year (a year either side only when none
 *   has the exact year). With no year and no matching poster, nothing is accepted.
 *
 * Since 2026-11-12 it also completes what is saved about titles whose id is already known ([details]):
 * for those it gets the id only.
 *
 * Privacy: TMDB gets a title's name and year (and the key), over HTTPS, nothing else: never the
 * playlist's address, logins, its name, categories or what was watched. The key lives with the
 * MDBList key in the ratings' own preferences file, is never logged, and is deleted by Remove keys.
 * Logs carry counts only.
 */
object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"

    private lateinit var app: Application
    private val sp by lazy { app.getSharedPreferences("ratings", Context.MODE_PRIVATE) }

    fun init(application: Application) {
        app = application
    }

    /** Test builds: a stand-in TMDB on this device. Never set in a release build. */
    @Volatile internal var baseForTests: String? = null

    var key: String?
        get() = sp.getString("tmdb_key", null)?.takeIf { it.isNotBlank() }
        set(value) {
            sp.edit().putString("tmdb_key", value?.trim()).putBoolean("tmdb_ok", true).apply()
            // Titles that had no id when only the provider was asked get another try.
            if (value != null) Ratings.saver.execute { runCatching { Ratings.db.retryNoId() }; RatingsFill.rebuild() }
        }

    val hasKey get() = key != null
    val keyEnd get() = key?.takeLast(4)
    val keyWorks get() = sp.getBoolean("tmdb_ok", true)

    /** The fill may ask TMDB now. */
    val usable get() = hasKey && keyWorks && System.currentTimeMillis() >= blockedUntil

    @Volatile private var blockedUntil = 0L

    /** TMDB's own key: 32 letters and digits (its long "read access token" is not used). */
    fun looksLikeKey(text: String): Boolean = text.trim().let { t -> t.length == 32 && t.all { it.isLetterOrDigit() } }

    fun removeKey() {
        sp.edit().remove("tmdb_key").remove("tmdb_ok").apply()
    }

    /** True when the key works, false when refused, null when TMDB can't be reached. */
    fun test(candidate: String): Boolean? = runCatching {
        when (get("/configuration", candidate.trim()).first) {
            200 -> true
            401, 403 -> false
            else -> null
        }
    }.getOrNull()

    /**
     * Whether TMDB could accept this title at all: it needs a year (from the list or the name) or a
     * poster with a file name like TMDB's own. Without either nothing is ever accepted, so the fill
     * doesn't spend requests on it (measured 2026-11-10: most movies of one playlist are like this).
     */
    fun canTry(rawName: String, listYear: String?, poster: String?): Boolean {
        if (listYear != null || Ratings.cleanName(rawName).second != null || Ratings.cleanFurther(rawName).second != null) return true
        val file = poster?.substringBefore('?')?.substringAfterLast('/')?.substringBeforeLast('.') ?: return false
        return file.length >= 20 && file.all { it.isLetterOrDigit() }
    }

    /** What TMDB says about a title it recognised. */
    data class Found(
        val id: String, val year: Int, val minutes: Int, val genres: String?, val cast: String?, val director: String?,
        val plot: String? = null, val keywords: List<String> = emptyList(), val seasons: Map<Int, Int> = emptyMap(),
        /** The details request was answered: everything TMDB has is here. */
        val full: Boolean = false,
        /** The language it was made in ("en"). */
        val lang: String? = null,
        /** Person's name -> photo path at TMDB. */
        val photos: Map<String, String> = emptyMap(),
        /** Matched by TMDB's own poster file: sure (a name and year match is not). */
        val sure: Boolean = false,
        /** Its name and poster at TMDB (for the Wrong title list). */
        val name: String? = null,
        val poster: String? = null,
        /** The film series (TMDB collection id), 0 for none. */
        val coll: Int = 0,
    ) {
        /** For the database. */
        internal fun toDetail() = RatingsDb.Detail(RatingsDb.Detail.OK, id, null, cast?.take(600), director?.take(200), genres?.take(200), year, minutes, "tmdb",
            plot, keywords, seasons, full, lang, photos, coll, name)
    }

    enum class Outcome { FOUND, NONE, OFFLINE, BLOCKED }

    class Result(val outcome: Outcome, val found: Found? = null)

    /**
     * Which movie or series this title is: by name and year, checked against the poster. Usually two
     * requests when found (the search, then its details); up to three more when posters must be compared.
     * Never throws.
     */
    fun lookup(type: ContentType, rawName: String, listYear: String?, poster: String?): Result {
        val k = key ?: return Result(Outcome.BLOCKED)
        if (!usable) return Result(Outcome.BLOCKED)
        val kind = if (type == ContentType.MOVIE) "movie" else "tv"
        val (name, nameYear) = Ratings.cleanName(rawName)
        val (further, furtherYear) = Ratings.cleanFurther(rawName)
        val year = (listYear ?: nameYear ?: furtherYear)?.toIntOrNull() ?: 0
        val posterFile = poster?.substringBefore('?')?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.length >= 16 }
        val wanted = Ratings.nameKey(rawName)
        var id: String? = null
        var byPoster = false
        for (query in listOf(name, further).filter { it.isNotBlank() }.distinct()) {
            val params = ArrayList<Pair<String, String>>()
            params.add("query" to query)
            params.add("include_adult" to "false")
            // Arabic names: TMDB answers with its Arabic titles, so the names can be compared.
            if (query.any { it in '؀'..'ۿ' }) params.add("language" to "ar")
            val (code, body) = try { get("/search/$kind", k, *params.toTypedArray()) } catch (e: Exception) { return Result(Outcome.OFFLINE) }
            if (code == 401 || code == 429) return Result(Outcome.BLOCKED)
            if (code != 200 || body == null) return Result(Outcome.OFFLINE)
            val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: continue
            val sameName = ArrayList<Pair<String, Int>>()
            val inOrder = ArrayList<String>()
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val rid = r.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
                inOrder.add(rid)
                val theirPoster = r.optString("poster_path").substringAfterLast('/').substringBeforeLast('.')
                // The provider shows TMDB's own poster file for this title: the same title.
                if (posterFile != null && theirPoster == posterFile) { id = rid; byPoster = true; break }
                val titles = listOf(r.optString("title"), r.optString("name"), r.optString("original_title"), r.optString("original_name")).filter { it.isNotBlank() }
                if (titles.any { Ratings.nameKey(it) == wanted }) {
                    val y = (r.optString("release_date").ifBlank { r.optString("first_air_date") }).take(4).toIntOrNull() ?: 0
                    sameName.add(rid to y)
                }
            }
            if (id == null && year > 0) {
                id = sameName.filter { it.second == year }.singleOrNull()?.first
                    ?: if (sameName.none { it.second == year }) sameName.filter { it.second in listOf(year - 1, year + 1) }.singleOrNull()?.first else null
            }
            // Still unsure (no year, or several with that name), and the provider's picture is a TMDB file:
            // a title has several official posters, so the first few candidates' poster lists are read
            // (same-name titles first). The provider's very file among them settles it.
            if (id == null && posterFile != null && poster != null && ("tmdb.org" in poster || "/t/p/" in poster)) {
                for (candidate in (sameName.map { it.first } + inOrder).distinct().take(3)) {
                    val (ic, ib) = try { get("/$kind/$candidate/images", k) } catch (e: Exception) { return Result(Outcome.OFFLINE) }
                    if (ic == 401 || ic == 429) return Result(Outcome.BLOCKED)
                    val posters = ib?.let { runCatching { JSONObject(it).optJSONArray("posters") }.getOrNull() } ?: continue
                    val theirs = (0 until posters.length()).any { i ->
                        posters.optJSONObject(i)?.optString("file_path")?.substringAfterLast('/')?.substringBeforeLast('.') == posterFile
                    }
                    if (theirs) { id = candidate; byPoster = true; break }
                }
            }
            if (id != null) break
        }
        val found = id ?: return Result(Outcome.NONE)
        return Result(Outcome.FOUND, (everything(type, found, k) ?: Found(found, 0, 0, null, null, null)).copy(sure = byPoster))
    }

    /**
     * Everything TMDB says about a title whose id is already known (from the playlist, the provider or
     * MDBList): cast, director, genres, length, the story, what it is about, and each season's year,
     * in one request (two for an IMDb id). TMDB gets the id and the key, nothing else. Never throws.
     * [ref]: "tmdb/movie/603", "tmdb/show/1399" or "imdb/movie/tt0133093".
     */
    fun details(type: ContentType, ref: String): Result {
        val k = key ?: return Result(Outcome.BLOCKED)
        if (!usable) return Result(Outcome.BLOCKED)
        val kind = if (type == ContentType.MOVIE) "movie" else "tv"
        var id = ref.substringAfterLast('/')
        if (ref.startsWith("imdb/")) {
            val (code, body) = try { get("/find/$id", k, "external_source" to "imdb_id") } catch (e: Exception) { return Result(Outcome.OFFLINE) }
            if (code == 401 || code == 429) return Result(Outcome.BLOCKED)
            if (code == 404) return Result(Outcome.NONE)
            if (code != 200 || body == null) return Result(Outcome.OFFLINE)
            id = runCatching { JSONObject(body).optJSONArray("${kind}_results")?.optJSONObject(0)?.opt("id")?.toString() }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it != "null" } ?: return Result(Outcome.NONE)
        } else if (!ref.startsWith("tmdb/") || id.isEmpty() || !id.all { it.isDigit() }) {
            return Result(Outcome.NONE)
        }
        val (code, body) = try { get("/$kind/$id", k, "append_to_response" to "credits,keywords") } catch (e: Exception) { return Result(Outcome.OFFLINE) }
        if (code == 401 || code == 429) return Result(Outcome.BLOCKED)
        if (code == 404) return Result(Outcome.NONE)
        if (code != 200 || body == null) return Result(Outcome.OFFLINE)
        return Result(Outcome.FOUND, parse(type, id, body) ?: return Result(Outcome.NONE))
    }

    /** The details request for [id]; null when TMDB did not answer it. */
    private fun everything(type: ContentType, id: String, k: String): Found? {
        val kind = if (type == ContentType.MOVIE) "movie" else "tv"
        val (code, body) = try { get("/$kind/$id", k, "append_to_response" to "credits,keywords") } catch (e: Exception) { -1 to null }
        if (code != 200 || body == null) return null
        return parse(type, id, body)
    }

    private fun parse(type: ContentType, found: String, body: String): Found? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        fun names(a: org.json.JSONArray?, limit: Int, keep: (JSONObject) -> Boolean = { true }): String? {
            a ?: return null
            val out = ArrayList<String>()
            for (i in 0 until a.length()) {
                val p = a.optJSONObject(i) ?: continue
                if (!keep(p)) continue
                p.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { out.add(it.replace(',', ' ')) }
                if (out.size >= limit) break
            }
            return out.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
        val credits = o.optJSONObject("credits")
        val director = names(credits?.optJSONArray("crew"), 3) { it.optString("job") == "Director" } ?: names(o.optJSONArray("created_by"), 3)
        val minutes = if (type == ContentType.MOVIE) o.optInt("runtime") else o.optJSONArray("episode_run_time")?.optInt(0) ?: 0
        val y = (o.optString("release_date").ifBlank { o.optString("first_air_date") }).take(4).toIntOrNull() ?: 0
        // Movies list their keywords under "keywords", series under "results".
        val words = o.optJSONObject("keywords")?.let { it.optJSONArray("keywords") ?: it.optJSONArray("results") }
        val keywords = ArrayList<String>()
        for (i in 0 until (words?.length() ?: 0)) words!!.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }?.let { keywords.add(it) }
        val seasons = HashMap<Int, Int>()
        val list = o.optJSONArray("seasons")
        for (i in 0 until (list?.length() ?: 0)) {
            val s = list!!.optJSONObject(i) ?: continue
            val year = s.optString("air_date").take(4).toIntOrNull() ?: continue
            if (s.has("season_number")) seasons[s.optInt("season_number")] = year
        }
        val plot = o.optString("overview").takeIf { it.isNotBlank() && it != "null" }
        val lang = o.optString("original_language").takeIf { it.length in 2..3 }
        // The photos of the people kept (cast, directors, creators): only their picture's path.
        val photos = HashMap<String, String>()
        for (arr in listOf(credits?.optJSONArray("cast"), credits?.optJSONArray("crew"), o.optJSONArray("created_by"))) {
            for (i in 0 until (arr?.length() ?: 0)) {
                val p = arr!!.optJSONObject(i) ?: continue
                val name = p.optString("name").replace(',', ' ').takeIf { it.isNotBlank() && it != "null" } ?: continue
                val path = p.optString("profile_path").takeIf { it.startsWith("/") } ?: continue
                photos.putIfAbsent(name, path)
            }
        }
        val title = (o.optString("title").ifBlank { o.optString("name") }).takeIf { it.isNotBlank() && it != "null" }
        return Found(found, y, minutes, names(o.optJSONArray("genres"), 8), names(credits?.optJSONArray("cast"), 10), director, plot, keywords, seasons, full = true, lang = lang, photos = photos,
            name = title, poster = o.optString("poster_path").takeIf { it.startsWith("/") },
            coll = o.optJSONObject("belongs_to_collection")?.optInt("id") ?: 0)
    }

    /**
     * Test builds: how many titles of the open playlist TMDB recognises, on a sample of [n] movies and
     * [n] series that carry no id. Logs counts only ("TmdbProbe"): never names, ids or the key.
     *   adb shell am broadcast -a com.osama.xtreamtv.debug.TMDB --ei sample 80
     */
    fun probeForTests(n: Int) {
        if (!BuildConfig.DEBUG) return
        Thread {
            for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
                val all = Session.streamCache["${type.name}:__all__"]
                if (all == null) { android.util.Log.d("TmdbProbe", "${type.name}: list not loaded"); continue }
                val sample = all.filter { it.tmdb == null }.shuffled(java.util.Random(11)).take(n)
                var found = 0; var none = 0; var other = 0
                var withYear = 0; var foundWithYear = 0; var tmdbPoster = 0; var foundTmdbPoster = 0; var details = 0
                for (item in sample) {
                    val hasYear = item.year != null || Ratings.cleanName(item.name).second != null || Ratings.cleanFurther(item.name).second != null
                    val ownPoster = item.image?.let { "tmdb.org" in it || "/t/p/" in it } == true
                    if (hasYear) withYear++
                    if (ownPoster) tmdbPoster++
                    val r = lookup(type, item.name, item.year, item.image)
                    when (r.outcome) {
                        Outcome.FOUND -> {
                            found++
                            if (hasYear) foundWithYear++
                            if (ownPoster) foundTmdbPoster++
                            if (r.found?.cast != null || r.found?.genres != null) details++
                        }
                        Outcome.NONE -> none++
                        else -> other++
                    }
                    Thread.sleep(200)
                }
                android.util.Log.d("TmdbProbe", "${type.name}: ${all.size} in the list, ${all.count { it.tmdb != null }} with an id from the list · sample ${sample.size}: " +
                    "$found recognised, $none not, $other not asked · with a year: $foundWithYear of $withYear · with a TMDB poster: $foundTmdbPoster of $tmdbPoster · with cast or genres: $details")
            }
            android.util.Log.d("TmdbProbe", "done")
        }.start()
    }

    /** One choice in "Which title is this?". */
    class Candidate(val ref: String, val name: String, val year: Int, val poster: String?, val genres: String?, val score: Float)

    private val GENRE_NAMES = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
        10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance",
        878 to "Science Fiction", 53 to "Thriller", 10752 to "War", 37 to "Western", 10759 to "Action & Adventure", 10762 to "Kids",
        10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10768 to "War & Politics", 10764 to "Reality", 10767 to "Talk",
    )

    /** Up to five titles TMDB finds for [query] (its search; TMDB gets the words and the key). Null when TMDB can't be reached. */
    fun candidates(type: ContentType, query: String): List<Candidate>? {
        val k = key ?: return null
        if (!usable) return null
        val kind = if (type == ContentType.MOVIE) "movie" else "tv"
        val ours = if (type == ContentType.MOVIE) "movie" else "show"
        val params = ArrayList<Pair<String, String>>()
        params.add("query" to query)
        params.add("include_adult" to "false")
        if (query.any { it in '؀'..'ۿ' }) params.add("language" to "ar")
        val (code, body) = try { get("/search/$kind", k, *params.toTypedArray()) } catch (e: Exception) { return null }
        if (code != 200 || body == null) return null
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val id = r.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
            val name = (r.optString("title").ifBlank { r.optString("name") }).takeIf { it.isNotBlank() } ?: continue
            val y = (r.optString("release_date").ifBlank { r.optString("first_air_date") }).take(4).toIntOrNull() ?: 0
            val g = r.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).mapNotNull { GENRE_NAMES[a.optInt(it)] }.take(3).joinToString(", ") }?.ifEmpty { null }
            out.add(Candidate("tmdb/$ours/$id", name, y, r.optString("poster_path").takeIf { it.startsWith("/") }, g, r.optDouble("vote_average", 0.0).toFloat()))
            if (out.size >= 5) break
        }
        return out
    }

    /** One title of TMDB's trending list. */
    class Trend(val type: ContentType, val id: String, val name: String, val year: String?, val poster: String?, val plot: String?, val genres: String?)

    /**
     * What is most watched this week (Package 3, Trending): [kind] "all", "movie" or "tv"; two pages
     * (about 40 titles), TMDB's order. Null when TMDB can't be asked now. TMDB gets only the key.
     */
    fun trending(kind: String): List<Trend>? {
        val k = key ?: return null
        if (!usable) return null
        val out = ArrayList<Trend>()
        for (page in 1..2) {
            val (code, body) = try { get("/trending/$kind/week", k, "page" to page.toString()) } catch (e: Exception) { return out.ifEmpty { null } }
            if (code != 200 || body == null) return out.ifEmpty { null }
            val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: break
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val media = r.optString("media_type").ifBlank { if (kind == "tv") "tv" else "movie" }
                val type = when (media) { "movie" -> ContentType.MOVIE; "tv" -> ContentType.SERIES; else -> continue }
                val id = r.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
                val name = (r.optString("title").ifBlank { r.optString("name") }).takeIf { it.isNotBlank() && it != "null" } ?: continue
                val year = (r.optString("release_date").ifBlank { r.optString("first_air_date") }).take(4).takeIf { it.length == 4 }
                val genres = r.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).mapNotNull { GENRE_NAMES[a.optInt(it)] }.take(3).joinToString(", ") }?.ifEmpty { null }
                out.add(Trend(type, id, name, year, r.optString("poster_path").takeIf { it.startsWith("/") },
                    r.optString("overview").takeIf { it.isNotBlank() && it != "null" }, genres))
            }
        }
        return out
    }

    /** A YouTube trailer's id for this TMDB title (official trailers first), or null. */
    fun trailer(type: ContentType, id: String): String? {
        val k = key ?: return null
        if (!usable) return null
        val kind = if (type == ContentType.MOVIE) "movie" else "tv"
        val (code, body) = try { get("/$kind/$id/videos", k) } catch (e: Exception) { return null }
        if (code != 200 || body == null) return null
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return null
        val videos = (0 until results.length()).mapNotNull { results.optJSONObject(it) }.filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() }
        return (videos.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") } ?: videos.firstOrNull { it.optString("type") == "Trailer" } ?: videos.firstOrNull())
            ?.optString("key")
    }

    /** A poster at card size, from TMDB's picture server. */
    fun cardPosterUrl(path: String?): String? = path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" }

    /** A person's film list: their photo and every work (our ref, "tmdb/movie/603") with how they took part. */
    class PersonFilms(val photo: String?, val works: List<Pair<String, Int>>)

    /**
     * Asks TMDB for the person named exactly [name] and their films and series (two requests; TMDB
     * gets the name and the key). Null when TMDB can't be asked now (no key, offline, busy);
     * an empty list when TMDB has nobody by that exact name.
     */
    fun personFilms(name: String): PersonFilms? {
        val k = key ?: return null
        if (!usable) return null
        val (code, body) = try { get("/search/person", k, "query" to name, "include_adult" to "false") } catch (e: Exception) { return null }
        if (code == 404) return PersonFilms(null, emptyList())
        if (code != 200 || body == null) return null
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return PersonFilms(null, emptyList())
        fun plain(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().trim()
        val wanted = plain(name)
        // The most popular person with exactly this name (TMDB lists the most popular first).
        val person = (0 until minOf(results.length(), 10)).mapNotNull { results.optJSONObject(it) }
            .firstOrNull { plain(it.optString("name")) == wanted } ?: return PersonFilms(null, emptyList())
        val id = person.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: return PersonFilms(null, emptyList())
        val photo = person.optString("profile_path").takeIf { it.startsWith("/") }
        val (c2, b2) = try { get("/person/$id/combined_credits", k) } catch (e: Exception) { return null }
        if (c2 == 404) return PersonFilms(photo, emptyList())
        if (c2 != 200 || b2 == null) return null
        val o = runCatching { JSONObject(b2) }.getOrNull() ?: return PersonFilms(photo, emptyList())
        val works = LinkedHashSet<Pair<String, Int>>()
        fun take(a: org.json.JSONArray?, role: (JSONObject) -> Int?) {
            a ?: return
            for (i in 0 until a.length()) {
                val w = a.optJSONObject(i) ?: continue
                val kind = when (w.optString("media_type")) { "movie" -> "movie"; "tv" -> "show"; else -> continue }
                val wid = w.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
                val r = role(w) ?: continue
                works.add("tmdb/$kind/$wid" to r)
            }
        }
        // Talk shows and award nights ("Self", "Himself") are not their films.
        take(o.optJSONArray("cast")) { w ->
            val part = w.optString("character").lowercase()
            if (part == "self" || part.startsWith("self ") || part.startsWith("self -") || part.startsWith("himself") || part.startsWith("herself")) null else RatingsDb.Detail.ACTOR
        }
        take(o.optJSONArray("crew")) { w -> if (w.optString("job") in setOf("Director", "Creator")) RatingsDb.Detail.DIRECTOR else null }
        return PersonFilms(photo, works.toList())
    }

    /** A poster, small, from TMDB's picture server. */
    fun posterUrl(path: String?): String? = path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w92$it" }

    /** A person's photo, small, from TMDB's picture server (no key needed). */
    fun photoUrl(path: String?): String? = path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w185$it" }

    /** GET on TMDB. The key goes in the address TMDB asks for; it is never logged. */
    private fun get(path: String, key: String, vararg params: Pair<String, String>): Pair<Int, String?> {
        val query = (params.toList() + ("api_key" to key)).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val base = if (BuildConfig.DEBUG) baseForTests ?: BASE else BASE
        val conn = URL("$base$path?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", "XtreamTV/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            // The saved key refused (changed or deleted on themoviedb.org): Settings shows it.
            if (code == 401 && key == Tmdb.key) sp.edit().putBoolean("tmdb_ok", false).apply()
            if (code == 429) {
                val wait = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000) ?: 10_000L
                blockedUntil = System.currentTimeMillis() + wait.coerceIn(1_000L, 600_000L)
            }
            return code to if (code in 200..299) conn.inputStream.bufferedReader().readText() else null
        } finally {
            conn.disconnect()
        }
    }
}
