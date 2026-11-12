package com.osama.xtreamtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trending this week (Package 3, approved 2026-11-13): TMDB's list of the week, asked once a day and
 * kept on the TV, with IMDb / Tomatoes / trailer for the titles the playlist doesn't have (one MDBList
 * batch per kind). Which ones the playlist has is worked out on the TV each time Home draws the row.
 */
object Trending {

    /** One title of the list, with what is known for the info page. */
    class Entry(val trend: Tmdb.Trend, val rank: Int, val imdb: String?, val tomatoes: Int?, val trailer: String?)

    private const val DAY_MS = 24 * 3600 * 1000L

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences("trending", Context.MODE_PRIVATE)

    /** [kind]: "all" (mixed), "movie" or "tv". The saved list when less than a day old, else a fresh one. Off the main thread. */
    fun list(context: Context, kind: String): List<Entry> {
        val sp = sp(context)
        val savedAt = sp.getLong("at_$kind", 0L)
        val saved = sp.getString("list_$kind", null)?.let { parse(it) }.orEmpty()
        if (saved.isNotEmpty() && System.currentTimeMillis() - savedAt < DAY_MS) return saved
        val fresh = runCatching { Tmdb.trending(kind) }.getOrNull()
        if (fresh.isNullOrEmpty()) return saved
        // Scores and trailers in one request per kind (only the ids; nothing goes to the notebook).
        val scores = HashMap<String, Ratings.Entry>()
        for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
            val ids = fresh.filter { it.type == type }.map { it.id }
            if (ids.isNotEmpty()) runCatching { Ratings.scoresForTmdb(type, ids) }.getOrNull()?.forEach { (id, e) -> scores["${type.name}:$id"] = e }
        }
        val entries = fresh.mapIndexed { i, t ->
            val e = scores["${t.type.name}:${t.id}"]
            Entry(t, i + 1, e?.imdb, e?.tomatoes, e?.trailer)
        }
        sp.edit().putString("list_$kind", toJson(entries)).putLong("at_$kind", System.currentTimeMillis()).apply()
        Diag.log("ratings", "trending ($kind): ${entries.size} titles from TMDB, scores for ${scores.size}")
        return entries
    }

    private fun toJson(list: List<Entry>): String {
        val a = JSONArray()
        for (e in list) a.put(JSONObject().put("type", e.trend.type.name).put("id", e.trend.id).put("name", e.trend.name).put("year", e.trend.year)
            .put("poster", e.trend.poster).put("plot", e.trend.plot).put("genres", e.trend.genres).put("rank", e.rank)
            .put("imdb", e.imdb).put("rt", e.tomatoes ?: -1).put("trailer", e.trailer))
        return a.toString()
    }

    private fun parse(text: String): List<Entry> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            fun str(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
            val type = runCatching { ContentType.valueOf(o.optString("type")) }.getOrNull() ?: return@mapNotNull null
            Entry(Tmdb.Trend(type, str("id") ?: return@mapNotNull null, str("name") ?: return@mapNotNull null, str("year"), str("poster"), str("plot"), str("genres")),
                o.optInt("rank"), str("imdb"), o.optInt("rt", -1).takeIf { it >= 0 }, str("trailer"))
        }
    }.getOrDefault(emptyList())

    /** A card for a title the playlist doesn't have (it can't play; OK opens the info page). */
    fun placeholder(e: Entry): StreamItem = StreamItem(
        e.trend.type, "tmdb:${e.trend.id}", e.trend.name, Tmdb.cardPosterUrl(e.trend.poster), null, null, null, null,
        year = e.trend.year, genre = e.trend.genres, plot = e.trend.plot, tmdb = e.trend.id,
    )

    fun isPlaceholder(s: StreamItem) = s.id.startsWith("tmdb:")
}
