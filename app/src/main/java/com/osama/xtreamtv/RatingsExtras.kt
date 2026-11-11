package com.osama.xtreamtv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What MDBList knows about a title beyond its scores (1.1E extras, 2026-11-06): the age rating,
 * awards, next and last episode, recommendations, and the description, year, length and genres used
 * to fill gaps the provider leaves. Asked for once from the title's page (one lookup, which also
 * refreshes its scores) and kept for a week, or until the next episode's date has passed.
 */
object RatingsExtras {
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    class Episode(val season: Int, val number: Int, val title: String?, val airAt: Long) {
        fun toJson() = JSONObject().put("s", season).put("e", number).put("t", title).put("a", airAt)
    }

    class Extras(
        val at: Long,
        val description: String?,
        val year: Int,
        val runtime: Int,
        val genres: List<String>,
        val certification: String?,
        val awards: String?,
        val status: String?,
        val next: Episode?,
        val last: Episode?,
        /** The recommended titles, best first: "TMDB id<TAB>title" (older saves: the id only). */
        val recs: List<String>,
        /** MDBList's poster link: used when the provider has no picture, or its picture doesn't load. */
        val poster: String? = null,
        /** False for extras saved before posters were kept: asked again once. */
        val posterChecked: Boolean = true,
    ) {
        // Saved before recommendations kept their titles (2026-11-07): asked again once. Kept a week;
        // once the listed next episode has aired, asked again at most once a day until MDBList moves on
        // (it used to ask on every visit for a week, spending a lookup each time).
        val fresh: Boolean get() {
            val now = System.currentTimeMillis()
            if (recs.isNotEmpty() && !recs.first().contains('\t')) return false
            if (now - at >= WEEK_MS || !posterChecked) return false
            val aired = next != null && next.airAt > 0 && next.airAt <= now
            return !aired || now - at < DAY_MS
        }

        fun toJson() = JSONObject().put("a", at).put("d", description).put("y", year).put("r", runtime)
            .put("g", JSONArray(genres)).put("c", certification).put("w", awards).put("st", status)
            .put("n", next?.toJson()).put("l", last?.toJson()).put("rc", JSONArray(recs)).put("p", poster ?: "")
    }

    private lateinit var app: Context
    private val file by lazy { File(app.filesDir, "ratings-extras.json") }

    fun init(context: Context) {
        app = context.applicationContext
    }

    private val saved: HashMap<String, Extras> by lazy {
        val map = HashMap<String, Extras>()
        runCatching {
            val o = JSONObject(file.readText())
            for (k in o.keys()) map[k] = parse(o.getJSONObject(k))
        }
        map
    }

    private fun parse(e: JSONObject): Extras {
        fun ep(o: JSONObject?) = o?.let { Episode(it.optInt("s"), it.optInt("e"), it.optString("t").takeIf { t -> t.isNotEmpty() && t != "null" }, it.optLong("a")) }
        fun list(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a?.optString(it)?.takeIf { s -> s.isNotEmpty() } }
        fun str(k: String) = e.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        return Extras(e.optLong("a"), str("d"), e.optInt("y"), e.optInt("r"), list(e.optJSONArray("g")), str("c"), str("w"), str("st"),
            ep(e.optJSONObject("n")), ep(e.optJSONObject("l")), list(e.optJSONArray("rc")), str("p"), e.has("p"))
    }

    /** Written on the ratings' one save thread, so two saves never mix in the same file. */
    private fun save() {
        Ratings.saver.execute {
            val text = synchronized(saved) { JSONObject().apply { for ((k, e) in saved) put(k, e.toJson()) }.toString() }
            Ratings.writeFile(file, text)
        }
    }

    fun clear() {
        synchronized(saved) { saved.clear() }
        file.delete()
    }

    /** What is saved for a title (any age), or null. */
    fun saved(type: ContentType, id: String): Extras? = if (Ratings.isOn) synchronized(saved) { saved[Ratings.keyOf(type, id)] } else null

    /**
     * The title's extras: saved ones while fresh, else one MDBList lookup by the title's matched id
     * (so the title must have been found first: [Ratings.lookup]). Null when unknown.
     */
    suspend fun forTitle(type: ContentType, id: String): Extras? = withContext(Dispatchers.IO) {
        val have = saved(type, id)
        if (have != null && have.fresh) return@withContext have
        val media = Ratings.fullAnswer(type, id) ?: return@withContext have
        val e = fromMedia(media)
        synchronized(saved) { saved[Ratings.keyOf(type, id)] = e }
        save()
        e
    }

    private fun fromMedia(m: JSONObject): Extras {
        fun s(k: String) = m.optString(k).takeIf { it.isNotBlank() && it != "null" }
        fun ep(o: JSONObject?): Episode? {
            o ?: return null
            val air = o.optString("air_date").takeIf { it.length >= 10 }?.let { parseDate(it) } ?: 0L
            val title = o.optString("title").takeIf { it.isNotBlank() && it != "null" && !Regex("(?i)^episode \\d+$").matches(it) }
            return Episode(o.optInt("season"), o.optInt("episode"), title, air)
        }
        val genres = m.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotBlank() } } } ?: emptyList()
        val recs = m.optJSONArray("recommendations")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optJSONObject("ids")?.opt("tmdb")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: ""
                val title = o.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: ""
                if (id.isEmpty() && title.isEmpty()) null else "$id\t$title"
            }
        } ?: emptyList()
        // Shows: "runtime" is the whole series; only a movie's length is shown.
        val runtime = if (m.optString("type") == "movie") m.optInt("runtime") else 0
        return Extras(System.currentTimeMillis(), s("description"), m.optInt("year"), runtime, genres, s("certification"), s("awards"), s("status"),
            ep(m.optJSONObject("next_episode")), ep(m.optJSONObject("last_episode")), recs, s("poster")?.takeIf { it.startsWith("http") })
    }

    private fun parseDate(text: String): Long? = runCatching {
        val f = java.text.SimpleDateFormat(if (text.length >= 19) "yyyy-MM-dd'T'HH:mm:ss" else "yyyy-MM-dd", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.parse(text.take(19))?.time
    }.getOrNull()

    /** "Won: … (Oscars, 2011). Plus 3 more Oscars…" -> "Oscar winner"; null when nothing notable. */
    fun awardBadge(awards: String?): String? {
        val a = awards ?: return null
        val won = a.substringBefore("Plus").let { it.startsWith("Won") }
        return when {
            Regex("(?i)\\(Oscars?").containsMatchIn(a.substringBefore("Plus")) && won -> "Oscar winner"
            Regex("(?i)Nominated for \\d+ Oscars?").containsMatchIn(a) -> "Oscar nominee"
            Regex("(?i)Emmy").containsMatchIn(a.substringBefore("Plus")) && won -> "Emmy winner"
            Regex("(?i)Nominated for \\d+ (Primetime )?Emmys?").containsMatchIn(a) -> "Emmy nominee"
            Regex("(?i)Golden Globe").containsMatchIn(a.substringBefore("Plus")) && won -> "Golden Globe winner"
            Regex("(?i)BAFTA").containsMatchIn(a.substringBefore("Plus")) && won -> "BAFTA winner"
            else -> null
        }
    }

    /**
     * The series page's line: "Next episode · S03 E05 "Title" · Fri 14 Nov" (within 30 days), "Next
     * episode · S04 E01 · July 2027", "S03 E05 aired Fri 14 Nov · not in your playlist yet", "Series ended
     * · last episode Jan 2025"; null when nothing is known. [have]: the episodes the provider lists.
     */
    fun nextLine(e: Extras, have: (Int, Int) -> Boolean): String? {
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
        val month = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
        val n = e.next
        if (n != null && n.airAt > 0) {
            val code = "S%02d E%02d".format(n.season, n.number)
            val title = n.title?.let { " \"$it\"" } ?: ""
            return when {
                n.airAt > now && n.airAt - now <= 30L * 86_400_000 -> "Next episode · $code$title · ${day.format(java.util.Date(n.airAt))}"
                n.airAt > now -> "Next episode · $code · ${month.format(java.util.Date(n.airAt))}"
                !have(n.season, n.number) -> "$code aired ${day.format(java.util.Date(n.airAt))} · not in your playlist yet"
                else -> null
            }
        }
        val ended = e.status?.lowercase()?.let { it.contains("ended") || it.contains("cancel") } == true
        val l = e.last
        if (ended && l != null && l.airAt > 0) return "Series ended · last episode ${java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(l.airAt))}"
        return null
    }

    /** A short date for a card: "New ep Fri 14 Nov" when the next episode airs within 14 days. */
    fun cardLine(e: Extras?): String? {
        val n = e?.next ?: return null
        val now = System.currentTimeMillis()
        if (n.airAt <= now || n.airAt - now > 14L * 86_400_000) return null
        return "New ep " + java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(n.airAt))
    }
}
