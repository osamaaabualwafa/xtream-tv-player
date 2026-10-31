package com.osama.xtreamtv

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

const val USER_AGENT = "XtreamTvPlayer/1.0 (Linux; Android TV) ExoPlayerLib"

enum class ContentType { LIVE, MOVIE, SERIES }

data class Account(val base: String, val username: String, val password: String)

data class Category(val id: String, val name: String)

data class StreamItem(
    val type: ContentType,
    val id: String,
    val name: String,
    val image: String?,
    val categoryId: String?,
    val ext: String?,
    val num: String?,
    val rating: String?,
    /** Extra line shown instead of the rating, e.g. resume progress. */
    val note: String? = null,
    /** Live channels: days of catch-up (tv_archive) the server keeps; 0 = none. */
    val archiveDays: Int = 0,
    /** Movies/series: when the provider added it (unix seconds), for "Recently added". */
    val added: Long = 0,
    /** Every category the item is in (newer panels list several in "category_ids"). */
    val categoryIds: List<String> = listOfNotNull(categoryId),
    /** Shown in the info strip under the poster grid when the list provides them. */
    val genre: String? = null,
    val year: String? = null,
    val plot: String? = null,
    /** Continue watching: how far in (0..1), shown as a bar on the poster; -1 = no bar. */
    val progress: Float = -1f,
)

data class UserInfo(
    val auth: Boolean,
    val status: String?,
    val expDate: Long?,
    val maxConnections: String?,
    val activeCons: String?,
    val formats: List<String>,
)

data class EpgEntry(val title: String, val description: String, val start: Long?, val end: Long?)

/** One programme in the full guide. [startText] is the server-local "yyyy-MM-dd HH:mm:ss". */
data class GuideEntry(
    val title: String,
    val description: String,
    val start: Long,
    val end: Long,
    val startText: String?,
    val hasArchive: Boolean,
) {
    /** "2026-10-30 20:00:00" -> "2026-10-30:20-00", the form timeshift URLs use. */
    fun timeshiftStart(): String {
        val text = startText?.trim()
        if (text != null && text.length >= 16) return text.substring(0, 10) + ":" + text.substring(11, 16).replace(':', '-')
        return java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).format(java.util.Date(start * 1000))
    }
}

data class VodInfo(
    val name: String?,
    val plot: String?,
    val rating: String?,
    val genre: String?,
    val releaseDate: String?,
    val duration: String?,
    val director: String?,
    val cast: String?,
    val cover: String?,
    val ext: String?,
    /** YouTube video id, when the provider has one. */
    val trailer: String? = null,
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int?,
    val ext: String,
    val plot: String?,
    val duration: String?,
    /** The episode's own still (wide), when the provider has one. */
    val image: String? = null,
)

data class SeriesInfo(
    val plot: String?,
    val genre: String?,
    val rating: String?,
    val releaseDate: String?,
    val cover: String?,
    val seasons: Map<Int, List<Episode>>,
    val cast: String? = null,
    val trailer: String? = null,
)

data class ServerInput(val base: String, val username: String?, val password: String?)

/** Defensive JSON readers: values may be strings, numbers, empty or null. */
internal fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val text = opt(key)?.toString()?.trim() ?: return null
    return if (text.isEmpty() || text == "null") null else text
}

internal fun JSONObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()

internal fun JSONObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()

/** "category_id" plus any extra ids in "category_ids" (as numbers or strings). */
internal fun JSONObject.categoryIds(): List<String> {
    val ids = LinkedHashSet<String>()
    str("category_id")?.let { ids.add(it) }
    optJSONArray("category_ids")?.let { arr ->
        for (i in 0 until arr.length()) arr.opt(i)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { ids.add(it) }
    }
    return ids.toList()
}

/** Panels send "0" for unrated items (treated as no rating) and values like "7.491" (shown as "7.5"). */
internal fun JSONObject.rating(key: String): String? {
    val raw = str(key) ?: return null
    val value = raw.toDoubleOrNull() ?: return raw
    if (value <= 0.0) return null
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

/**
 * Accepts "host:port", with or without scheme/trailing slash, or a pasted
 * get.php / player_api.php link carrying username and password.
 */
fun parseServerInput(raw: String): ServerInput? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    if (!text.contains("://")) text = "http://$text"
    val uri = Uri.parse(text)
    val host = uri.host ?: return null
    val scheme = uri.scheme ?: "http"
    val port = if (uri.port > 0) ":${uri.port}" else ""
    var path = uri.path ?: ""
    if (path.endsWith(".php")) path = path.substringBeforeLast('/')
    path = path.trimEnd('/')
    val user = try { uri.getQueryParameter("username") } catch (e: Exception) { null }
    val pass = try { uri.getQueryParameter("password") } catch (e: Exception) { null }
    return ServerInput("$scheme://$host$port$path", user, pass)
}

/** What a stream URL really returns: status, content type and the first bytes. */
data class StreamProbe(val code: Int, val contentType: String?, val head: String) {
    val isHls: Boolean
        get() = head.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U") ||
            contentType?.contains("mpegurl", ignoreCase = true) == true
    val isHtml: Boolean
        get() = contentType?.contains("html", ignoreCase = true) == true || head.trimStart().startsWith("<")

    /** Safe to log: URLs (which carry credentials) are removed. */
    fun summary(): String {
        val start = head.take(60).replace(Regex("https?://\\S+"), "<url>").replace(Regex("\\s+"), " ")
        return "HTTP $code, type=$contentType, starts=\"$start\""
    }
}

/** Same connection rules as the player (IPv4 first, redirects followed). */
suspend fun probeStream(start: String): StreamProbe? = withContext(Dispatchers.IO) {
    try {
        val request = okhttp3.Request.Builder().url(start).header("User-Agent", USER_AGENT).build()
        PlaybackTuning.probeClient.newCall(request).execute().use { r ->
            val bytes = ByteArray(512)
            val n = r.body?.byteStream()?.read(bytes)?.coerceAtLeast(0) ?: 0
            val head = String(bytes, 0, n, Charsets.ISO_8859_1).map { c -> if (c.code in 32..126 || c == '\n' || c == '\r') c else '.' }.joinToString("")
            StreamProbe(r.code, r.header("Content-Type"), head)
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * [cacheDir]: where category and stream lists are kept between app launches so screens open
 * instantly; they are refreshed from the server after [LIST_CACHE_MS] (or with refreshLists()).
 */
class XtreamApi(val account: Account, private val cacheDir: File? = null) {

    companion object {
        private const val LIST_CACHE_MS = 12 * 60 * 60 * 1000L
        /** Lists being refreshed in the background (cache file names). */
        private val refreshing = HashSet<String>()
        private val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        private val CACHED_ACTIONS = setOf(
            "get_live_categories", "get_vod_categories", "get_series_categories",
            "get_live_streams", "get_vod_streams", "get_series",
        )
    }

    /** Drop the saved lists for this account so the next load comes from the server. */
    fun clearListCache() {
        val dir = listCacheDir() ?: return
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun listCacheDir(): File? {
        val root = cacheDir ?: return null
        val id = md5(account.base + "|" + account.username)
        return File(root, "lists/$id").apply { mkdirs() }
    }

    private fun md5(text: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val base = account.base.trimEnd('/')
    private val user = Uri.encode(account.username)
    private val pass = Uri.encode(account.password)

    fun liveUrl(streamId: String, ext: String) = "$base/live/$user/$pass/$streamId.$ext"

    fun movieUrl(streamId: String, ext: String?) = "$base/movie/$user/$pass/$streamId.${ext ?: "mp4"}"

    fun episodeUrl(episodeId: String, ext: String) = "$base/series/$user/$pass/$episodeId.$ext"

    /** Catch-up: [start] is "yyyy-MM-dd:HH-mm" in the server's time, [minutes] the programme length. */
    fun timeshiftUrl(streamId: String, start: String, minutes: Int) = "$base/timeshift/$user/$pass/$minutes/$start/$streamId.ts"

    /** Full guide for one channel (past and upcoming), oldest first. */
    suspend fun fullEpg(streamId: String): List<GuideEntry> {
        val root = call("action" to "get_simple_data_table", "stream_id" to streamId) as? JSONObject ?: return emptyList()
        return objects(root.optJSONArray("epg_listings")).mapNotNull { o ->
            val start = o.long("start_timestamp") ?: return@mapNotNull null
            val end = o.long("stop_timestamp") ?: o.long("end_timestamp") ?: return@mapNotNull null
            GuideEntry(decodeBase64(o.str("title")), decodeBase64(o.str("description")), start, end, o.str("start"), o.int("has_archive") == 1)
        }.distinctBy { it.start }.sortedBy { it.start }
    }

    suspend fun userInfo(): UserInfo {
        val root = call() as? JSONObject ?: throw IOException("This doesn't look like an Xtream Codes server")
        val u = root.optJSONObject("user_info") ?: throw IOException("Wrong username or password")
        val formats = ArrayList<String>()
        u.optJSONArray("allowed_output_formats")?.let { a ->
            for (i in 0 until a.length()) formats.add(a.optString(i).lowercase())
        }
        return UserInfo(
            auth = u.int("auth") == 1,
            status = u.str("status"),
            expDate = u.long("exp_date")?.takeIf { it > 0 },
            maxConnections = u.str("max_connections"),
            activeCons = u.str("active_cons"),
            formats = formats,
        )
    }

    suspend fun categories(type: ContentType): List<Category> {
        val action = when (type) {
            ContentType.LIVE -> "get_live_categories"
            ContentType.MOVIE -> "get_vod_categories"
            ContentType.SERIES -> "get_series_categories"
        }
        return objects(call("action" to action)).mapNotNull { o ->
            val id = o.str("category_id") ?: return@mapNotNull null
            Category(id, o.str("category_name") ?: "Category $id")
        }
    }

    suspend fun streams(type: ContentType, categoryId: String?): List<StreamItem> {
        val action = when (type) {
            ContentType.LIVE -> "get_live_streams"
            ContentType.MOVIE -> "get_vod_streams"
            ContentType.SERIES -> "get_series"
        }
        val result = if (categoryId != null) call("action" to action, "category_id" to categoryId) else call("action" to action)
        val list = objects(result)
        return withContext(Dispatchers.Default) { list.mapNotNull { parseItem(type, it) } }
    }

    private fun parseItem(type: ContentType, o: JSONObject): StreamItem? = when (type) {
        ContentType.LIVE -> o.str("stream_id")?.let { id ->
            StreamItem(
                type, id, o.str("name") ?: "Channel $id", o.str("stream_icon"), o.str("category_id"), null, o.str("num"), null,
                archiveDays = if (o.int("tv_archive") == 1) o.int("tv_archive_duration")?.coerceAtLeast(1) ?: 1 else 0,
                categoryIds = o.categoryIds(),
            )
        }
        ContentType.MOVIE -> o.str("stream_id")?.let { id ->
            StreamItem(
                type, id, o.str("name") ?: "Movie $id", o.str("stream_icon"), o.str("category_id"), o.str("container_extension"), null, o.rating("rating"),
                added = o.long("added") ?: 0,
                categoryIds = o.categoryIds(),
                genre = o.str("genre")?.takeIf { it.isNotBlank() },
                year = (o.str("year") ?: o.str("releasedate") ?: o.str("release_date"))?.let { Regex("(19|20)\\d{2}").find(it)?.value },
                plot = o.str("plot")?.takeIf { it.isNotBlank() },
            )
        }
        ContentType.SERIES -> o.str("series_id")?.let { id ->
            StreamItem(
                type, id, o.str("name") ?: "Series $id", o.str("cover"), o.str("category_id"), null, null, o.rating("rating"),
                added = o.long("last_modified") ?: 0,
                categoryIds = o.categoryIds(),
                genre = o.str("genre")?.takeIf { it.isNotBlank() },
                year = (o.str("releaseDate") ?: o.str("release_date") ?: o.str("year"))?.let { Regex("(19|20)\\d{2}").find(it)?.value },
                plot = o.str("plot")?.takeIf { it.isNotBlank() },
            )
        }
    }

    suspend fun vodInfo(vodId: String): VodInfo {
        val root = call("action" to "get_vod_info", "vod_id" to vodId) as? JSONObject
            ?: throw IOException("No details available")
        val info = root.optJSONObject("info") ?: JSONObject()
        val data = root.optJSONObject("movie_data") ?: JSONObject()
        return VodInfo(
            name = data.str("name") ?: info.str("name"),
            plot = info.str("plot") ?: info.str("description"),
            rating = info.rating("rating"),
            genre = info.str("genre"),
            releaseDate = info.str("releasedate") ?: info.str("release_date"),
            duration = info.str("duration"),
            director = info.str("director"),
            cast = info.str("cast") ?: info.str("actors"),
            cover = info.str("movie_image") ?: info.str("cover_big"),
            ext = data.str("container_extension"),
            trailer = info.str("youtube_trailer"),
        )
    }

    suspend fun seriesInfo(seriesId: String): SeriesInfo {
        val root = call("action" to "get_series_info", "series_id" to seriesId) as? JSONObject
            ?: throw IOException("No details available")
        val info = root.optJSONObject("info") ?: JSONObject()
        val seasons = sortedMapOf<Int, MutableList<Episode>>()

        fun add(o: JSONObject, fallbackSeason: Int) {
            val id = o.str("id") ?: return
            val season = o.int("season") ?: fallbackSeason
            val number = o.int("episode_num")
            val epInfo = o.optJSONObject("info")
            val title = o.str("title") ?: (if (number != null) "Episode $number" else "Episode")
            val still = epInfo?.str("movie_image")?.takeIf { it.startsWith("http") }
            val ep = Episode(id, title, season, number, o.str("container_extension") ?: "mp4", epInfo?.str("plot"), epInfo?.str("duration"), still)
            seasons.getOrPut(season) { ArrayList() }.add(ep)
        }

        // Usually {"1":[...], "2":[...]}, but some panels send an array of arrays.
        when (val eps = root.opt("episodes")) {
            is JSONObject -> {
                val keys = eps.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val fallback = key.toIntOrNull() ?: 0
                    when (val v = eps.opt(key)) {
                        is JSONArray -> for (i in 0 until v.length()) v.optJSONObject(i)?.let { add(it, fallback) }
                        is JSONObject -> add(v, fallback)
                        else -> {}
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until eps.length()) {
                    when (val v = eps.opt(i)) {
                        is JSONArray -> for (j in 0 until v.length()) v.optJSONObject(j)?.let { add(it, i + 1) }
                        is JSONObject -> add(v, i + 1)
                        else -> {}
                    }
                }
            }
            else -> {}
        }
        for (list in seasons.values) list.sortBy { it.number ?: Int.MAX_VALUE }
        return SeriesInfo(
            plot = info.str("plot"),
            genre = info.str("genre"),
            rating = info.rating("rating"),
            releaseDate = info.str("releaseDate") ?: info.str("release_date"),
            cover = info.str("cover"),
            seasons = seasons,
            cast = info.str("cast"),
            trailer = info.str("youtube_trailer"),
        )
    }

    suspend fun shortEpg(streamId: String): List<EpgEntry> {
        val root = call("action" to "get_short_epg", "stream_id" to streamId, "limit" to "2") as? JSONObject
            ?: return emptyList()
        return objects(root.optJSONArray("epg_listings")).map { o ->
            EpgEntry(
                title = decodeBase64(o.str("title")),
                description = decodeBase64(o.str("description")),
                start = o.long("start_timestamp"),
                end = o.long("stop_timestamp") ?: o.long("end_timestamp"),
            )
        }
    }

    private fun decodeBase64(s: String?): String {
        if (s == null) return ""
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            s
        }
    }

    private fun objects(value: Any?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) value.optJSONObject(i)?.let { out.add(it) }
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) value.optJSONObject(keys.next())?.let { out.add(it) }
            }
            else -> {}
        }
        return out
    }

    private suspend fun call(vararg params: Pair<String, String>): Any? = withContext(Dispatchers.IO) {
        val url = StringBuilder(base)
            .append("/player_api.php?username=").append(user)
            .append("&password=").append(pass)
        for ((k, v) in params) url.append('&').append(k).append('=').append(Uri.encode(v))
        // Lists (categories, channels, movies, series) are cached on the device.
        val action = params.firstOrNull { it.first == "action" }?.second
        val cacheFile = if (action in CACHED_ACTIONS) {
            listCacheDir()?.let { File(it, md5(params.joinToString("&") { p -> p.first + "=" + p.second }) + ".json") }
        } else {
            null
        }
        val fresh = cacheFile != null && cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < LIST_CACHE_MS
        val text = if (fresh) {
            cacheFile!!.readText()
        } else if (cacheFile != null && cacheFile.exists()) {
            // Old list: show it straight away and fetch the new one in the background for next time.
            refreshInBackground(url.toString(), cacheFile)
            cacheFile.readText()
        } else {
            try {
                httpGet(url.toString()).also { body -> cacheFile?.let { f -> runCatching { f.writeText(body) } } }
            } catch (e: IOException) {
                // Offline or server hiccup: an old list is better than none.
                if (cacheFile != null && cacheFile.exists()) cacheFile.readText() else throw e
            }
        }.trim().trimStart('\uFEFF')
        if (text.isEmpty()) {
            null
        } else {
            try {
                JSONTokener(text).nextValue()
            } catch (e: Exception) {
                throw IOException("Server returned an invalid response")
            }
        }
    }

    private fun refreshInBackground(url: String, file: File) {
        synchronized(refreshing) { if (!refreshing.add(file.name)) return }
        backgroundScope.launch {
            try {
                val body = httpGet(url)
                if (body.isNotBlank()) {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(body)
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                // Keep the old list; try again next time.
            } finally {
                synchronized(refreshing) { refreshing.remove(file.name) }
            }
        }
    }

    private fun httpGet(start: String): String {
        var url = URL(start)
        // HttpURLConnection won't follow http<->https redirects, so follow them by hand.
        for (hop in 0 until 6) {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 45_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json, */*")
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("HTTP $code without location")
                    url = URL(url, location)
                    continue
                }
                if (code == 401 || code == 403) throw IOException("Access denied by server (HTTP $code)")
                if (code !in 200..299) throw IOException("Server error (HTTP $code)")
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }
}
