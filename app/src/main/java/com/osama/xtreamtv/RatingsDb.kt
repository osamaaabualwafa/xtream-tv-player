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
 * - Version 3 (2026-11-12, "complete the notebook"): a work's story (`plot`), whether TMDB has been
 *   asked for everything about it (`full`), `keywords` + `work_keywords` (what the story is about:
 *   heist, time travel…), and `work_seasons` (the year each season came out).
 *
 * The same movie in two playlists is asked for once and saved once. Screens never read from here:
 * they read [Ratings]' copy in memory, so a write never holds a screen up.
 * Never stored here: keys, the server's address, logins, what was watched.
 */
internal class RatingsDb(context: Context) : SQLiteOpenHelper(context, "titles.db", null, 6) {

    override fun onCreate(db: SQLiteDatabase) = create(db)

    /**
     * A person's own film list, asked from TMDB the first time their name shows on a page (owner,
     * 2026-11-12): kept apart from work_people, which holds each title's own cast and director.
     * `person_asked` remembers when, so each person is asked once a month at most.
     */
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (db.isReadOnly) return
        db.execSQL("CREATE TABLE IF NOT EXISTS person_works (name TEXT NOT NULL, ref TEXT NOT NULL, role INTEGER NOT NULL, PRIMARY KEY (name, ref, role))")
        db.execSQL("CREATE INDEX IF NOT EXISTS person_works_ref ON person_works (ref)")
        db.execSQL("CREATE TABLE IF NOT EXISTS person_asked (name TEXT PRIMARY KEY, at INTEGER NOT NULL, photo TEXT)")
    }

    /** Of [names], those whose film list was asked in the last 30 days. */
    fun personAsked(names: Collection<String>): Set<String> {
        if (names.isEmpty()) return emptySet()
        val since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val out = HashSet<String>()
        for (chunk in names.distinct().chunked(50)) {
            readableDatabase.rawQuery("SELECT name FROM person_asked WHERE at > ? AND name IN (${chunk.joinToString(",") { "?" }})",
                arrayOf(since.toString()) + chunk.toTypedArray()).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        }
        return out
    }

    /** Saves a person's film list ([works]: ref to role); an empty list still counts as asked. */
    fun savePersonWorks(name: String, photo: String?, works: Collection<Pair<String, Int>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM person_works WHERE name = ?", arrayOf(name))
            for ((ref, role) in works) db.execSQL("INSERT OR IGNORE INTO person_works (name, ref, role) VALUES (?, ?, ?)", arrayOf<Any>(name, ref, role))
            db.execSQL("INSERT OR REPLACE INTO person_asked (name, at, photo) VALUES (?, ?, ?)", arrayOf<Any?>(name, System.currentTimeMillis(), photo))
            if (photo != null) db.execSQL("UPDATE people SET photo = ? WHERE name = ? AND (photo IS NULL OR photo = '')", arrayOf(photo, name))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Photos TMDB gave with [names]' film lists. */
    fun personPhotos(names: Collection<String>): Map<String, String> {
        if (names.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (chunk in names.distinct().chunked(50)) {
            readableDatabase.rawQuery("SELECT name, photo FROM person_asked WHERE photo IS NOT NULL AND photo != '' AND name IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray())
                .use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        }
        return out
    }

    /** Each of [names]' film list from TMDB (ref to role), as saved. */
    fun personWorks(names: Collection<String>): Map<String, Map<String, Int>> {
        if (names.isEmpty()) return emptyMap()
        val out = HashMap<String, HashMap<String, Int>>()
        for (chunk in names.distinct().chunked(50)) {
            readableDatabase.rawQuery("SELECT name, ref, role FROM person_works WHERE name IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray())
                .use { c -> while (c.moveToNext()) out.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = c.getInt(2) }
        }
        return out
    }

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
        addVersion3(db)
    }

    /** Only adds: nothing saved by version 2 (released as 2.2.0) is changed or removed. */
    private fun addVersion3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE works ADD COLUMN plot TEXT")
        db.execSQL("ALTER TABLE works ADD COLUMN full INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS keywords (kid INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS work_keywords (ref TEXT NOT NULL, kid INTEGER NOT NULL, PRIMARY KEY (ref, kid))")
        db.execSQL("CREATE INDEX IF NOT EXISTS work_keywords_kid ON work_keywords (kid)")
        db.execSQL("CREATE TABLE IF NOT EXISTS work_seasons (ref TEXT NOT NULL, season INTEGER NOT NULL, year INTEGER NOT NULL, PRIMARY KEY (ref, season))")
        addVersion4(db)
    }

    /**
     * Version 4 (2026-11-12, one card per title): a work's original language (TMDB's code), and the
     * picture height a copy showed when it played (its name can say "HD" for 1080p, or nothing for 720p).
     */
    private fun addVersion4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE works ADD COLUMN lang TEXT")
        db.execSQL("ALTER TABLE titles ADD COLUMN height INTEGER NOT NULL DEFAULT 0")
        addVersion5(db)
    }

    /**
     * Version 5 (2026-11-12, names you can press): a person's photo (TMDB's picture path). Works
     * completed before are asked once more, for the photos.
     */
    private fun addVersion5(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE people ADD COLUMN photo TEXT")
        db.execSQL("UPDATE works SET full = 0")
        addVersion6(db)
    }

    /**
     * Version 6 (2026-11-12, Wrong title): whether a match is sure (TMDB's own poster matched), and the
     * match a title had before the owner chose another (so it can be chosen again).
     */
    private fun addVersion6(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE titles ADD COLUMN sure INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE titles ADD COLUMN orig_ref TEXT")
    }

    /**
     * A database file put in place by Undo or kept from before may be of version 2: it is brought up
     * to date the next time it is opened, like any update.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 2) { addVersion3(db); return }
        if (oldVersion == 3) { addVersion4(db); return }
        if (oldVersion == 4) { addVersion5(db); return }
        if (oldVersion == 5) { addVersion6(db); return }
        // Version 1 (never released; the owner's emulator only) kept one flat row per title: moved over.
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
        /** The story in a few lines (TMDB's). */
        val plot: String? = null,
        /** What the story is about, in TMDB's words: "heist", "time travel". */
        val keywords: List<String> = emptyList(),
        /** Season number -> the year it came out. */
        val seasons: Map<Int, Int> = emptyMap(),
        /** TMDB was asked for everything about this work: it is not asked again. */
        val full: Boolean = false,
        /** The language it was made in (TMDB's code: "en", "tr"). */
        val lang: String? = null,
        /** Person's name -> their photo's path at TMDB ("/abc.jpg"). */
        val photos: Map<String, String> = emptyMap(),
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
                    // Named by TMDB, no answer yet: the work's own row (another title may share it) is left alone.
                    if (e.at == 0L && e.imdb == null && e.tomatoes == null) continue
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
        putWork(db, ref, d)
    }

    /** What is known about a work itself (TMDB's answer for a title whose id was already known). */
    fun putWorkDetail(ref: String, d: Detail) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            putWork(db, ref, d)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Works TMDB has been asked everything about. */
    fun fullRefs(): HashSet<String> {
        val set = HashSet<String>()
        // Completed before the original language was kept (version 3): asked once more, for it.
        readableDatabase.rawQuery("SELECT ref FROM works WHERE full = 1 AND lang IS NOT NULL", null).use { c -> while (c.moveToNext()) set.add(c.getString(0)) }
        return set
    }

    private fun putWork(db: SQLiteDatabase, ref: String, d: Detail) {
        db.execSQL("INSERT OR IGNORE INTO works (ref) VALUES (?)", arrayOf(ref))
        if (!d.plot.isNullOrBlank()) db.execSQL("UPDATE works SET plot = ? WHERE ref = ? AND (plot IS NULL OR plot = '')", arrayOf(d.plot.take(1200), ref))
        for (word in d.keywords.map { it.trim().lowercase() }.filter { it.length in 2..60 }.distinct().take(25)) {
            db.execSQL("INSERT OR IGNORE INTO keywords (name) VALUES (?)", arrayOf(word))
            db.execSQL("INSERT OR IGNORE INTO work_keywords (ref, kid) SELECT ?, kid FROM keywords WHERE name = ?", arrayOf(ref, word))
        }
        for ((season, year) in d.seasons) if (season in 0..200 && year in 1900..2100) {
            db.execSQL("INSERT OR REPLACE INTO work_seasons (ref, season, year) VALUES (?, ?, ?)", arrayOf<Any>(ref, season, year))
        }
        if (d.full) db.execSQL("UPDATE works SET full = 1 WHERE ref = ?", arrayOf(ref))
        if (!d.lang.isNullOrBlank()) db.execSQL("UPDATE works SET lang = ? WHERE ref = ?", arrayOf(d.lang.take(8), ref))
        for ((name, path) in d.photos) if (path.startsWith("/") && path.length < 80) {
            db.execSQL("UPDATE people SET photo = ? WHERE name = ? AND (photo IS NULL OR photo = '')", arrayOf(path, name))
        }
        // Asked, and TMDB said no language: "" (not NULL), so it is not asked again.
        else if (d.full) db.execSQL("UPDATE works SET lang = '' WHERE ref = ? AND lang IS NULL", arrayOf(ref))
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

    /**
     * For the Movies and Series filter: every title of [playlist] that knows its work, "TYPE:id" ->
     * year, length, scores, award and genres. A match marked wrong keeps nothing of that work.
     */
    fun filterFacts(playlist: String): Map<String, TitleFilter.Fact> {
        val db = readableDatabase
        val genres = HashMap<String, ArrayList<String>>()
        db.rawQuery("SELECT wg.ref, g.name FROM work_genres wg JOIN genres g ON g.gid = wg.gid", null).use { c ->
            while (c.moveToNext()) genres.getOrPut(c.getString(0)) { ArrayList() }.add(c.getString(1))
        }
        val norm = HashMap<String, List<String>>()
        val map = HashMap<String, TitleFilter.Fact>()
        db.rawQuery(
            "SELECT t.type, t.id, t.ref, w.year, w.minutes, w.imdb, w.rt, w.award FROM titles t JOIN works w ON w.ref = t.ref " +
                "WHERE t.pl = ? AND (t.how IS NULL OR t.how != 'WRONG')", arrayOf(playlist),
        ).use { c ->
            while (c.moveToNext()) {
                val ref = c.getString(2)
                val g = norm.getOrPut(ref) { TitleFilter.normaliseGenres(genres[ref].orEmpty()) }
                map["${c.getString(0)}:${c.getString(1)}"] = TitleFilter.Fact(
                    c.getInt(3), c.getInt(4), c.getString(5)?.toFloatOrNull() ?: Float.NaN,
                    if (c.isNull(6)) -1 else c.getInt(6), c.getString(7)?.takeIf { it.isNotBlank() }, g, ref,
                )
            }
        }
        return map
    }

    /** A series' season years, from its work (season 0, the extras, has none). */
    fun seasonYears(key: String): Map<Int, Int> {
        val ref = refAny(key) ?: return emptyMap()
        val map = HashMap<Int, Int>()
        readableDatabase.rawQuery("SELECT season, year FROM work_seasons WHERE ref = ? AND season > 0", arrayOf(ref)).use { c ->
            while (c.moveToNext()) map[c.getInt(0)] = c.getInt(1)
        }
        return map
    }

    // ---- How sure a match is, and the owner's own choice (package 2, item 5) ----

    /** TMDB matched this title by its own poster: sure. */
    fun markSure(key: String) {
        val p = parts(key) ?: return
        writableDatabase.execSQL("UPDATE titles SET sure = 1 WHERE pl=? AND type=? AND id=?", arrayOf(p[0], p[1], p[2]))
    }

    fun isSure(key: String): Boolean {
        val p = parts(key) ?: return false
        return readableDatabase.rawQuery("SELECT sure FROM titles WHERE pl=? AND type=? AND id=?", arrayOf(p[0], p[1], p[2])).use { c -> c.moveToFirst() && c.getInt(0) == 1 }
    }

    /** The owner chose which work this title is: it replaces the match (the first match is kept to choose again). */
    fun chooseRef(key: String, ref: String) {
        val p = parts(key) ?: return
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO titles (pl, type, id) VALUES (?, ?, ?)", arrayOf(p[0], p[1], p[2]))
        db.execSQL("UPDATE titles SET orig_ref = ref WHERE pl=? AND type=? AND id=? AND orig_ref IS NULL AND ref IS NOT NULL AND ref NOT LIKE 'local/%'", arrayOf(p[0], p[1], p[2]))
        db.execSQL("UPDATE titles SET ref = ?, state = 0, sure = 1 WHERE pl=? AND type=? AND id=?", arrayOf(ref, p[0], p[1], p[2]))
        db.execSQL("INSERT OR IGNORE INTO works (ref) VALUES (?)", arrayOf(ref))
    }

    /** The match this title had before the owner chose another, or null. */
    fun origRef(key: String): String? {
        val p = parts(key) ?: return null
        return readableDatabase.rawQuery("SELECT orig_ref FROM titles WHERE pl=? AND type=? AND id=?", arrayOf(p[0], p[1], p[2])).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    // ---- People (package 2, item 3) ----

    /** A person on a title: name, 0 actor / 1 director, photo path (or null). */
    class Person(val name: String, val role: Int, val photo: String?)

    /** The cast and directors saved for a title's work, in their saved order. */
    fun peopleOf(key: String): List<Person> {
        val ref = refAny(key) ?: return emptyList()
        val list = ArrayList<Person>()
        readableDatabase.rawQuery(
            "SELECT p.name, wp.role, p.photo FROM work_people wp JOIN people p ON p.pid = wp.pid WHERE wp.ref = ? ORDER BY wp.rowid", arrayOf(ref),
        ).use { c -> while (c.moveToNext()) list.add(Person(c.getString(0), c.getInt(1), c.getString(2)?.takeIf { it.isNotBlank() })) }
        return list
    }

    private fun refAny(key: String): String? {
        val p = parts(key) ?: return null
        return readableDatabase.rawQuery("SELECT ref FROM titles WHERE pl=? AND type=? AND id=? AND state != ${Detail.BAD_ID}", arrayOf(p[0], p[1], p[2])).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** How many different titles of [playlist] each of [names] is in. */
    /**
     * How many titles of [playlist] each of [names] is in: the titles' own cast lists, plus the
     * person's film list from TMDB matched to the notebook's titles and to [listRefs] (the
     * provider's own TMDB ids, "tmdb/movie/603", for titles the notebook hasn't reached yet).
     */
    fun titleCounts(playlist: String, names: Collection<String>, listRefs: Set<String> = emptySet()): Map<String, Int> {
        if (names.isEmpty()) return emptyMap()
        val refs = HashMap<String, HashSet<String>>()
        for (chunk in names.distinct().chunked(50)) {
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT p.name, t.ref FROM people p JOIN work_people wp ON wp.pid = p.pid JOIN titles t ON t.ref = wp.ref " +
                    "WHERE t.pl = ? AND p.name IN ($marks) AND (t.how IS NULL OR t.how != 'WRONG')", arrayOf(playlist) + chunk.toTypedArray(),
            ).use { c -> while (c.moveToNext()) refs.getOrPut(c.getString(0)) { HashSet() }.add(c.getString(1)) }
            readableDatabase.rawQuery(
                "SELECT pw.name, t.ref FROM person_works pw JOIN titles t ON t.ref = pw.ref " +
                    "WHERE t.pl = ? AND pw.name IN ($marks) AND (t.how IS NULL OR t.how != 'WRONG')", arrayOf(playlist) + chunk.toTypedArray(),
            ).use { c -> while (c.moveToNext()) refs.getOrPut(c.getString(0)) { HashSet() }.add(c.getString(1)) }
        }
        if (listRefs.isNotEmpty()) for ((name, works) in personWorks(names)) {
            for (ref in works.keys) if (ref in listRefs) refs.getOrPut(name) { HashSet() }.add(ref)
        }
        return refs.mapValues { it.value.size }
    }

    /** One person: their photo, whether they act and/or direct, and every title of [playlist] they are in ("TYPE", id). */
    /** [films]: the person's film list from TMDB (ref to role), to match titles the notebook hasn't reached. */
    class PersonTitles(val photo: String?, val acts: Boolean, val directs: Boolean, val titles: List<Pair<String, String>>, val films: Map<String, Int> = emptyMap())

    fun personTitles(playlist: String, name: String): PersonTitles {
        val db = readableDatabase
        var photo: String? = null
        db.rawQuery("SELECT photo FROM people WHERE name = ?", arrayOf(name)).use { c -> if (c.moveToFirst()) photo = c.getString(0)?.takeIf { it.isNotBlank() } }
        if (photo == null) db.rawQuery("SELECT photo FROM person_asked WHERE name = ?", arrayOf(name)).use { c -> if (c.moveToFirst()) photo = c.getString(0)?.takeIf { it.isNotBlank() } }
        var acts = false; var directs = false
        val titles = ArrayList<Pair<String, String>>()
        for (sql in listOf(
            "SELECT t.type, t.id, wp.role FROM people p JOIN work_people wp ON wp.pid = p.pid JOIN titles t ON t.ref = wp.ref WHERE p.name = ? AND t.pl = ?",
            "SELECT t.type, t.id, pw.role FROM person_works pw JOIN titles t ON t.ref = pw.ref WHERE pw.name = ? AND t.pl = ?",
        )) db.rawQuery("$sql AND (t.how IS NULL OR t.how != 'WRONG')", arrayOf(name, playlist)).use { c ->
            while (c.moveToNext()) {
                titles.add(c.getString(0) to c.getString(1))
                if (c.getInt(2) == Detail.DIRECTOR) directs = true else acts = true
            }
        }
        return PersonTitles(photo, acts, directs, titles.distinct(), personWorks(listOf(name))[name].orEmpty())
    }

    /** A person found by Search: their name, how many titles of the playlist, photo, whether they act and/or direct. */
    class FoundPerson(val name: String, val titles: Int, val photo: String?, val acts: Boolean, val directs: Boolean)

    /** People whose name has a word starting with [query], with how many titles of [playlist] they are in. Most titles first. */
    fun searchPeople(playlist: String, query: String, limit: Int): List<FoundPerson> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val list = ArrayList<FoundPerson>()
        readableDatabase.rawQuery(
            "SELECT p.name, COUNT(DISTINCT t.ref) n, p.photo, MIN(wp.role), MAX(wp.role) FROM people p JOIN work_people wp ON wp.pid = p.pid JOIN titles t ON t.ref = wp.ref " +
                "WHERE t.pl = ? AND (p.name LIKE ? OR p.name LIKE ?) GROUP BY p.name ORDER BY n DESC, p.name LIMIT ?",
            arrayOf(playlist, "$q%", "% $q%", limit.toString()),
        ).use { c ->
            while (c.moveToNext()) list.add(FoundPerson(c.getString(0), c.getInt(1), c.getString(2)?.takeIf { it.isNotBlank() },
                c.getInt(3) == Detail.ACTOR, c.getInt(4) == Detail.DIRECTOR))
        }
        return list
    }

    /** Picture heights copies showed when they played, "TYPE:id" -> height, for [playlist]. */
    fun learnedHeights(playlist: String): Map<String, Int> {
        val map = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT type, id, height FROM titles WHERE pl = ? AND height > 0", arrayOf(playlist)).use { c ->
            while (c.moveToNext()) map["${c.getString(0)}:${c.getString(1)}"] = c.getInt(2)
        }
        return map
    }

    /** The player saw this copy's real picture height. */
    fun saveHeight(key: String, height: Int) {
        val p = parts(key) ?: return
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO titles (pl, type, id) VALUES (?, ?, ?)", arrayOf(p[0], p[1], p[2]))
        db.execSQL("UPDATE titles SET height = ? WHERE pl = ? AND type = ? AND id = ?", arrayOf<Any>(height, p[0], p[1], p[2]))
    }

    /** The language a work was made in (TMDB's code), or null. */
    fun originalLanguage(ref: String): String? =
        readableDatabase.rawQuery("SELECT lang FROM works WHERE ref = ?", arrayOf(ref)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** What is saved about one title, for its page: shown at once, before the provider answers. */
    class Saved(val year: Int, val minutes: Int, val genres: String?, val director: String?, val cast: String?, val plot: String?)

    fun saved(key: String): Saved? {
        val p = parts(key) ?: return null
        val db = readableDatabase
        val ref = db.rawQuery("SELECT ref FROM titles WHERE pl=? AND type=? AND id=? AND state != ${Detail.BAD_ID}", arrayOf(p[0], p[1], p[2])).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return null
        var year = 0
        var minutes = 0
        var plot: String? = null
        db.rawQuery("SELECT year, minutes, plot FROM works WHERE ref = ?", arrayOf(ref)).use { c ->
            if (c.moveToFirst()) { year = c.getInt(0); minutes = c.getInt(1); plot = c.getString(2)?.takeIf { it.isNotBlank() } }
        }
        fun names(sql: String): String? {
            val list = ArrayList<String>()
            db.rawQuery(sql, arrayOf(ref)).use { c -> while (c.moveToNext()) list.add(c.getString(0)) }
            return list.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
        // In the order they were saved: the provider and TMDB both list the main names first.
        val genres = names("SELECT g.name FROM work_genres w JOIN genres g ON g.gid = w.gid WHERE w.ref = ? ORDER BY w.rowid")
        val director = names("SELECT p.name FROM work_people w JOIN people p ON p.pid = w.pid WHERE w.ref = ? AND w.role = ${Detail.DIRECTOR} ORDER BY w.rowid")
        val cast = names("SELECT p.name FROM work_people w JOIN people p ON p.pid = w.pid WHERE w.ref = ? AND w.role = ${Detail.ACTOR} ORDER BY w.rowid")
        if (year == 0 && minutes == 0 && genres == null && director == null && cast == null && plot == null) return null
        return Saved(year, minutes, genres, director, cast, plot)
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
