package com.osama.xtreamtv

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
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
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int?,
    val ext: String,
    val plot: String?,
    val duration: String?,
)

data class SeriesInfo(
    val plot: String?,
    val genre: String?,
    val rating: String?,
    val releaseDate: String?,
    val cover: String?,
    val seasons: Map<Int, List<Episode>>,
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

class XtreamApi(val account: Account) {

    private val base = account.base.trimEnd('/')
    private val user = Uri.encode(account.username)
    private val pass = Uri.encode(account.password)

    fun liveUrl(streamId: String, ext: String) = "$base/live/$user/$pass/$streamId.$ext"

    fun movieUrl(streamId: String, ext: String?) = "$base/movie/$user/$pass/$streamId.${ext ?: "mp4"}"

    fun episodeUrl(episodeId: String, ext: String) = "$base/series/$user/$pass/$episodeId.$ext"

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
            StreamItem(type, id, o.str("name") ?: "Channel $id", o.str("stream_icon"), o.str("category_id"), null, o.str("num"), null)
        }
        ContentType.MOVIE -> o.str("stream_id")?.let { id ->
            StreamItem(type, id, o.str("name") ?: "Movie $id", o.str("stream_icon"), o.str("category_id"), o.str("container_extension"), null, o.str("rating"))
        }
        ContentType.SERIES -> o.str("series_id")?.let { id ->
            StreamItem(type, id, o.str("name") ?: "Series $id", o.str("cover"), o.str("category_id"), null, null, o.str("rating"))
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
            rating = info.str("rating"),
            genre = info.str("genre"),
            releaseDate = info.str("releasedate") ?: info.str("release_date"),
            duration = info.str("duration"),
            director = info.str("director"),
            cast = info.str("cast") ?: info.str("actors"),
            cover = info.str("movie_image") ?: info.str("cover_big"),
            ext = data.str("container_extension"),
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
            val ep = Episode(id, title, season, number, o.str("container_extension") ?: "mp4", epInfo?.str("plot"), epInfo?.str("duration"))
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
            rating = info.str("rating"),
            releaseDate = info.str("releaseDate") ?: info.str("release_date"),
            cover = info.str("cover"),
            seasons = seasons,
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
        val text = httpGet(url.toString()).trim().trimStart('﻿')
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
