package com.osama.xtreamtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Instant start (Package 4, item 1, approved 2026-11-13): what each Home row showed when Home was
 * last left, saved per playlist, so the next start draws every row at once. The big lists load
 * behind it as before and the rows then update in place. A few hundred cards, never the whole list.
 * Also (item 2) the ids of every movie and series at the last visit, for "New since your last visit".
 */
object HomeSnapshot {

    class Snapshot(
        val rows: Map<String, List<StreamItem>>,
        /** Tile view id name -> its count line ("21,868 movies"). */
        val counts: Map<String, String>,
        /** Picked for you: "TYPE:id" -> why, and the row's name. */
        val because: Map<String, String>,
        val pickedName: String?,
    )

    private fun file(context: Context, pl: String) = File(context.filesDir, "home_snapshot_${pl.filter { it.isLetterOrDigit() }}.json")

    private fun enc(s: StreamItem): JSONObject = JSONObject()
        .put("type", s.type.name).put("id", s.id).put("name", s.name).put("image", s.image).put("cat", s.categoryId)
        .put("ext", s.ext).put("rating", s.rating).put("note", s.note).put("added", s.added).put("genre", s.genre)
        .put("year", s.year).put("tmdb", s.tmdb).put("rid", s.ratingId).put("progress", s.progress.toDouble())

    private fun get(o: JSONObject): StreamItem? {
        fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        val type = runCatching { ContentType.valueOf(o.optString("type")) }.getOrNull() ?: return null
        val id = str("id") ?: return null
        return StreamItem(
            type, id, str("name") ?: id, str("image"), str("cat"), str("ext"), null, str("rating"),
            note = str("note"), added = o.optLong("added"), genre = str("genre"), year = str("year"), tmdb = str("tmdb"),
            ratingId = str("rid"), progress = o.optDouble("progress", -1.0).toFloat(),
        )
    }

    fun save(context: Context, pl: String, snap: Snapshot) {
        runCatching {
            val rows = JSONObject()
            for ((k, list) in snap.rows) rows.put(k, JSONArray().apply { list.take(100).forEach { put(enc(it)) } })
            val o = JSONObject().put("rows", rows).put("counts", JSONObject(snap.counts as Map<*, *>))
                .put("because", JSONObject(snap.because as Map<*, *>)).put("picked_name", snap.pickedName)
            val f = file(context, pl)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }.onFailure { android.util.Log.w("HomeSnapshot", "not saved", it) }
    }

    fun load(context: Context, pl: String): Snapshot? = runCatching {
        val f = file(context, pl).takeIf { it.exists() } ?: return null
        val o = JSONObject(f.readText())
        val rows = HashMap<String, List<StreamItem>>()
        o.optJSONObject("rows")?.let { r -> for (k in r.keys()) rows[k] = r.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::get) } }.orEmpty() }
        fun map(name: String): Map<String, String> = o.optJSONObject(name)?.let { m -> m.keys().asSequence().associateWith { m.optString(it) } }.orEmpty()
        Snapshot(rows, map("counts"), map("because"), o.optString("picked_name").takeIf { it.isNotEmpty() && it != "null" })
    }.getOrNull()

    // ---- Item 2: every title id at the last visit ----

    private fun idsFile(context: Context, pl: String, which: String) = File(context.filesDir, "home_ids_${which}_${pl.filter { it.isLetterOrDigit() }}.json")

    /** The ids seen at the last visit ("MOVIE" / "SERIES" -> ids) and when; null before the first visit. */
    fun lastIds(context: Context, pl: String, which: String): Pair<Long, Map<String, Set<String>>>? = runCatching {
        val f = idsFile(context, pl, which).takeIf { it.exists() } ?: return null
        val o = JSONObject(f.readText())
        val map = HashMap<String, Set<String>>()
        for (k in listOf("MOVIE", "SERIES")) map[k] = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.optString(it) } }.orEmpty()
        o.optLong("at") to map
    }.getOrNull()

    fun saveIds(context: Context, pl: String, which: String, at: Long, ids: Map<String, Collection<String>>) {
        runCatching {
            val o = JSONObject().put("at", at)
            for ((k, v) in ids) o.put(k, JSONArray(v))
            val f = idsFile(context, pl, which)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    /**
     * New since your last visit (item 2): titles not in the playlist at the previous visit, compared over
     * at least 24 hours (two saved id lists: the one compared against, and the latest). Null on the
     * first visit (nothing to compare with yet). Off the main thread.
     */
    fun newSinceLastVisit(context: Context, pl: String, movies: List<StreamItem>, series: List<StreamItem>): List<StreamItem>? {
        val now = System.currentTimeMillis()
        val current = mapOf("MOVIE" to movies.mapTo(HashSet()) { it.id }, "SERIES" to series.mapTo(HashSet()) { it.id })
        var base = lastIds(context, pl, "base")
        val last = lastIds(context, pl, "last")
        if (base == null) {
            saveIds(context, pl, "base", now, current)
            saveIds(context, pl, "last", now, current)
            return null
        }
        // The list compared against moves on to the previous visit once it is a day old.
        if (now - base.first >= 24 * 3600_000L && last != null && last.first > base.first) {
            saveIds(context, pl, "base", last.first, last.second)
            base = last
        }
        saveIds(context, pl, "last", now, current)
        val oldMovies = base.second["MOVIE"].orEmpty()
        val oldSeries = base.second["SERIES"].orEmpty()
        return movies.filter { it.id !in oldMovies } + series.filter { it.id !in oldSeries }
    }
}
