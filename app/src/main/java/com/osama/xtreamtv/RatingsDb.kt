package com.osama.xtreamtv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The titles database (work list 3.4; arrangement agreed with the owner 2026-11-10).
 *
 * - `titles`: one small row per title in a playlist (playlist, type, the provider's id): which work it
 *   is, how it was matched, and marks that belong to that playlist only (a wrong match).
 * - `works`: one row per real movie or series, shared by every playlist: scores, award, trailer,
 *   poster, year, length. Its key is its MDBList reference ("tmdb/movie/603"); a title with details
 *   but no id gets a work of its own ("local/…"), never sent anywhere.
 * - `people` + `work_people`, `genres` + `work_genres`: cast, directors and genres, linked to works,
 *   so "everything with this actor" is a lookup, not a read of every title.
 * - `playlists`: for the library backup (a playlist's fingerprint, so another TV can recognise it).
 *
 * The same movie in two playlists is asked for once and saved once. Screens never read from here:
 * they read [Ratings]' copy in memory, so a write never holds a screen up.
 * Never stored here: keys, the server's address, logins, what was watched.
 */
internal class RatingsDb(context: Context) : SQLiteOpenHelper(context, "titles.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) = create(db)

    private fun create(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE titles (pl TEXT NOT NULL, type TEXT NOT NULL, id TEXT NOT NULL, ref TEXT, how TEXT, at INTEGER NOT NULL DEFAULT 0, " +
                "state INTEGER NOT NULL DEFAULT 0, seen INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (pl, type, id))",
        )
        db.execSQL("CREATE INDEX titles_ref ON titles (ref)")
        db.execSQL(
            "CREATE TABLE works (ref TEXT PRIMARY KEY, imdb TEXT, rt INTEGER, at INTEGER NOT NULL DEFAULT 0, year INTEGER NOT NULL DEFAULT 0, " +
                "minutes INTEGER NOT NULL DEFAULT 0, trailer TEXT, trailer_ok INTEGER NOT NULL DEFAULT 0, award TEXT, award_ok INTEGER NOT NULL DEFAULT 0, " +
                "poster TEXT, poster_ok INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE TABLE people (pid INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE work_people (ref TEXT NOT NULL, pid INTEGER NOT NULL, role INTEGER NOT NULL, PRIMARY KEY (ref, pid, role))")
        db.execSQL("CREATE INDEX work_people_pid ON work_people (pid)")
        db.execSQL("CREATE TABLE genres (gid INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE work_genres (ref TEXT NOT NULL, gid INTEGER NOT NULL, PRIMARY KEY (ref, gid))")
        db.execSQL("CREATE INDEX work_genres_gid ON work_genres (gid)")
        db.execSQL("CREATE TABLE playlists (pl TEXT PRIMARY KEY, fp TEXT)")
    }

    /** Version 1 (never released; the owner's emulator only) kept one flat row per title: moved over. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != 1) return
        create(db)
        db.execSQL(
            "INSERT OR IGNORE INTO works (ref, imdb, rt, at, year, trailer, trailer_ok, award, award_ok, poster, poster_ok) " +
                "SELECT ref, imdb, rt, MAX(at), year, trailer, trailer_ok, award, award_ok, poster, poster_ok FROM ratings WHERE ref IS NOT NULL GROUP BY ref",
        )
        db.execSQL("INSERT OR IGNORE INTO titles (pl, type, id, ref, how, at) SELECT pl, type, id, ref, how, at FROM ratings")
        val old = ArrayList<Pair<String, Detail>>()
        db.rawQuery("SELECT pl, type, id, state, tmdb, imdb, actors, director, genre, year, minutes, src FROM details", null).use { c ->
            while (c.moveToNext()) {
                old.add("${c.getString(0)}:${c.getString(1)}:${c.getString(2)}" to
                    Detail(c.getInt(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getInt(9), c.getInt(10), c.getString(11) ?: ""))
            }
        }
        for ((key, d) in old) putDetail(db, key, d)
        db.execSQL("DROP TABLE ratings")
        db.execSQL("DROP TABLE details")
    }

    /** What the fill learns about a title on the way to its scores. */
    class Detail(
        val state: Int,
        val tmdb: String?, val imdb: String?,
        /** Names separated by commas, as the provider gives them. */
        val actors: String?, val director: String?, val genre: String?,
        val year: Int, val minutes: Int,
        /** Where it came from: "provider" or "tmdb". */
        val src: String,
    ) {
        companion object {
            const val OK = 0
            /** Asked, and there is no id for this title. */
            const val NO_ID = 1
            /** Its id belongs to a title of another year: left to the title's page. */
            const val BAD_ID = 2
            const val ACTOR = 0
            const val DIRECTOR = 1
        }
    }

    private fun parts(key: String): List<String>? = key.split(':', limit = 3).takeIf { it.size == 3 }

    private fun kind(type: String) = if (type == ContentType.MOVIE.name) "movie" else "show"

    // ---- Scores ([Ratings]' entries) ----

    /** Every title with an answer, joined with its work: the copy [Ratings] keeps in memory. */
    fun loadRatings(): HashMap<String, Ratings.Entry> {
        val map = HashMap<String, Ratings.Entry>()
        readableDatabase.rawQuery(
            "SELECT t.pl, t.type, t.id, t.how, t.at, t.ref, w.imdb, w.rt, w.at, w.year, w.trailer, w.trailer_ok, w.award, w.award_ok, w.poster, w.poster_ok " +
                "FROM titles t LEFT JOIN works w ON w.ref = t.ref WHERE t.how IS NOT NULL AND t.pl NOT LIKE 'fp:%'", null,
        ).use { c ->
            while (c.moveToNext()) {
                val how = runCatching { Ratings.How.valueOf(c.getString(3)) }.getOrDefault(Ratings.How.NONE)
                val ref = c.getString(5)?.takeIf { !it.startsWith("local/") }
                val shared = ref != null && how != Ratings.How.WRONG && !c.isNull(8)
                map["${c.getString(0)}:${c.getString(1)}:${c.getString(2)}"] = if (!shared) {
                    Ratings.Entry(null, null, c.getLong(4), how, ref)
                } else {
                    // The work's own time: one refresh serves every playlist that has this title.
                    Ratings.Entry(
                        c.getString(6), if (c.isNull(7)) null else c.getInt(7), if (c.getLong(8) > 0 || c.getLong(4) == 0L) c.getLong(8) else c.getLong(4), how, ref,
                        c.getString(10), c.getInt(11) != 0, c.getInt(9), c.getString(12), c.getInt(13) != 0, c.getString(14), c.getInt(15) != 0,
                    )
                }
            }
        }
        return map
    }

    /** Saves (or, for a null entry, forgets the answer of) these titles, a few hundred to a transaction. */
    fun writeRatings(rows: Map<String, Ratings.Entry?>) {
        val db = writableDatabase
        for (chunk in rows.entries.chunked(250)) {
            db.beginTransaction()
            try {
                for ((key, e) in chunk) {
                    val p = parts(key) ?: continue
                    val who = arrayOf(p[0], p[1], p[2])
                    if (e == null) {
                        db.execSQL("UPDATE titles SET how = NULL, at = 0 WHERE pl=? AND type=? AND id=?", who)
                        continue
                    }
                    db.execSQL("INSERT OR IGNORE INTO titles (pl, type, id) VALUES (?, ?, ?)", who)
                    // A title's own work ("local/…") stays its work until a real id is known.
                    db.execSQL(
                        "UPDATE titles SET how = ?, at = ?, ref = CASE WHEN ? IS NOT NULL THEN ? WHEN ref LIKE 'local/%' THEN ref ELSE NULL END WHERE pl=? AND type=? AND id=?",
                        arrayOf<Any?>(e.how.name, e.at, e.ref, e.ref, p[0], p[1], p[2]),
                    )
                    val ref = e.ref ?: continue
                    if (e.how == Ratings.How.WRONG) continue
                    db.execSQL("INSERT OR IGNORE INTO works (ref) VALUES (?)", arrayOf(ref))
                    db.update("works", ContentValues().apply {
                        put("imdb", e.imdb); put("rt", e.tomatoes); put("at", e.at)
                        if (e.year > 0) put("year", e.year)
                        put("trailer", e.trailer); put("trailer_ok", if (e.trailerChecked) 1 else 0)
                        put("award", e.award); put("award_ok", if (e.awardChecked) 1 else 0)
                        put("poster", e.poster); put("poster_ok", if (e.posterChecked) 1 else 0)
                    }, "ref = ?", arrayOf(ref))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Refresh saved scores: every answer counts as old (matches are kept). */
    fun expireRatings() {
        val db = writableDatabase
        db.execSQL("UPDATE works SET at = 0")
        db.execSQL("UPDATE titles SET at = 0 WHERE how IS NOT NULL AND how != 'WRONG'")
    }

    /** Remove key: every saved score goes. Which title is which work, and what the provider said about it, stay. */
    fun clearRatings() {
        val db = writableDatabase
        db.execSQL("UPDATE works SET imdb = NULL, rt = NULL, at = 0, trailer = NULL, trailer_ok = 0, award = NULL, award_ok = 0, poster = NULL, poster_ok = 0")
        db.execSQL("UPDATE titles SET how = NULL, at = 0")
    }

    // ---- Details (ids, cast, director, genre, year, length) ----

    fun putDetail(key: String, d: Detail) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            putDetail(db, key, d)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun putDetail(db: SQLiteDatabase, key: String, d: Detail) {
        val p = parts(key) ?: return
        val real = d.tmdb?.let { "tmdb/${kind(p[1])}/$it" } ?: d.imdb?.let { "imdb/${kind(p[1])}/$it" }
        val hasMore = !d.actors.isNullOrBlank() || !d.director.isNullOrBlank() || !d.genre.isNullOrBlank() || d.year > 0 || d.minutes > 0
        val ref = if (d.state == Detail.BAD_ID) null else real ?: if (hasMore) "local/${p[0]}/${p[1]}/${p[2]}" else null
        db.execSQL("INSERT OR IGNORE INTO titles (pl, type, id) VALUES (?, ?, ?)", arrayOf(p[0], p[1], p[2]))
        // A work already chosen for this title (a name match) is kept.
        db.execSQL(
            "UPDATE titles SET state = ?, seen = 1, ref = CASE WHEN ? = 2 THEN NULL WHEN ref IS NULL OR ref LIKE 'local/%' THEN ? ELSE ref END WHERE pl=? AND type=? AND id=?",
            arrayOf<Any?>(d.state, d.state, ref, p[0], p[1], p[2]),
        )
        if (ref == null) return
        db.execSQL("INSERT OR IGNORE INTO works (ref) VALUES (?)", arrayOf(ref))
        if (d.year > 0) db.execSQL("UPDATE works SET year = ? WHERE ref = ? AND year = 0", arrayOf<Any>(d.year, ref))
        if (d.minutes > 0) db.execSQL("UPDATE works SET minutes = ? WHERE ref = ? AND minutes = 0", arrayOf<Any>(d.minutes, ref))
        for ((names, role) in listOf(d.actors to Detail.ACTOR, d.director to Detail.DIRECTOR)) {
            for (name in split(names).take(12)) {
                db.execSQL("INSERT OR IGNORE INTO people (name) VALUES (?)", arrayOf(name))
                db.execSQL("INSERT OR IGNORE INTO work_people (ref, pid, role) SELECT ?, pid, ? FROM people WHERE name = ?", arrayOf<Any>(ref, role, name))
            }
        }
        for (name in split(d.genre?.replace('/', ',')).take(8)) {
            db.execSQL("INSERT OR IGNORE INTO genres (name) VALUES (?)", arrayOf(name))
            db.execSQL("INSERT OR IGNORE INTO work_genres (ref, gid) SELECT ?, gid FROM genres WHERE name = ?", arrayOf(ref, name))
        }
    }

    private fun split(names: String?): List<String> =
        names?.split(',')?.map { it.trim() }?.filter { it.length in 2..80 }?.distinct() ?: emptyList()

    /** One playlist's titles already asked about: id -> (state, its MDBList reference or null). For the fill's queue. */
    fun detailIds(playlist: String, type: ContentType): HashMap<String, Pair<Int, String?>> {
        val map = HashMap<String, Pair<Int, String?>>()
        readableDatabase.rawQuery("SELECT id, state, ref FROM titles WHERE pl=? AND type=? AND (seen = 1 OR state != 0)", arrayOf(playlist, type.name)).use { c ->
            while (c.moveToNext()) map[c.getString(0)] = c.getInt(1) to c.getString(2)?.takeIf { !it.startsWith("local/") }
        }
        return map
    }

    /** The MDBList reference already known for a title (from the fill, a restore, TMDB), or null. */
    fun refOf(key: String): String? {
        val p = parts(key) ?: return null
        readableDatabase.rawQuery("SELECT ref FROM titles WHERE pl=? AND type=? AND id=? AND state != ${Detail.BAD_ID}", arrayOf(p[0], p[1], p[2])).use { c ->
            return if (c.moveToFirst()) c.getString(0)?.takeIf { !it.startsWith("local/") } else null
        }
    }

    /** The id this title carried belongs to another title: never used for it again. */
    fun markBadId(key: String) {
        val p = parts(key) ?: return
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO titles (pl, type, id) VALUES (?, ?, ?)", arrayOf(p[0], p[1], p[2]))
        db.execSQL("UPDATE titles SET state = ${Detail.BAD_ID}, seen = 1, ref = NULL WHERE pl=? AND type=? AND id=?", arrayOf(p[0], p[1], p[2]))
    }

    /** A TMDB key was added: titles the provider had no id for are asked about again. */
    fun retryNoId() {
        writableDatabase.execSQL("UPDATE titles SET seen = 0, state = 0 WHERE state = ${Detail.NO_ID} AND (ref IS NULL OR ref LIKE 'local/%')")
    }

    /** Counts for checks and Settings: titles, works, people, genres. */
    fun counts(): IntArray {
        val out = IntArray(4)
        listOf("titles", "works", "people", "genres").forEachIndexed { i, t ->
            readableDatabase.rawQuery("SELECT COUNT(*) FROM $t", null).use { c -> if (c.moveToFirst()) out[i] = c.getInt(0) }
        }
        return out
    }
}
