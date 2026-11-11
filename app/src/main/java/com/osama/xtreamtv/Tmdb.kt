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
    class Found(val id: String, val year: Int, val minutes: Int, val genres: String?, val cast: String?, val director: String?)

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
                if (posterFile != null && theirPoster == posterFile) { id = rid; break }
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
                    if (theirs) { id = candidate; break }
                }
            }
            if (id != null) break
        }
        val found = id ?: return Result(Outcome.NONE)
        // Its details: genres, length, cast and director, in one request.
        val (code, body) = try { get("/$kind/$found", k, "append_to_response" to "credits") } catch (e: Exception) { -1 to null }
        if (code != 200 || body == null) return Result(Outcome.FOUND, Found(found, 0, 0, null, null, null))
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return Result(Outcome.FOUND, Found(found, 0, 0, null, null, null))
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
        return Result(Outcome.FOUND, Found(found, y, minutes, names(o.optJSONArray("genres"), 8), names(credits?.optJSONArray("cast"), 10), director))
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
