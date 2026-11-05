package com.osama.xtreamtv.qa

import com.osama.xtreamtv.Account
import com.osama.xtreamtv.ContentType
import com.osama.xtreamtv.UserInfo
import com.osama.xtreamtv.XtreamApi
import com.osama.xtreamtv.accountProblem
import com.osama.xtreamtv.parseServerInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's data layer against the fake server: login answers, lists, details, guide. */
class T01_DataTest : QaTest() {

    private fun api(user: String) = XtreamApi(Account(Qa.server, user, user), Qa.ctx.cacheDir.resolve("qa_$user").apply { deleteRecursively(); mkdirs() })

    @Test
    fun serverAddressFormats() {
        Qa.step("typed server addresses are understood")
        assertEquals("http://example.com:8080", parseServerInput("example.com:8080")?.base)
        assertEquals("http://example.com:8080", parseServerInput("  http://example.com:8080/  ")?.base)
        assertEquals("https://example.com", parseServerInput("https://example.com/")?.base)
        val m3u = parseServerInput("http://example.com:8080/get.php?username=abc&password=xyz&type=m3u_plus&output=ts")
        assertEquals("http://example.com:8080", m3u?.base)
        assertEquals("abc", m3u?.username)
        assertEquals("xyz", m3u?.password)
        val panel = parseServerInput("http://example.com/player_api.php?username=u1&password=p1")
        assertEquals("http://example.com", panel?.base)
        assertEquals("u1", panel?.username)
        assertNull(parseServerInput(""))
        assertNull(parseServerInput("   "))
    }

    @Test
    fun accountChecks() {
        val now = System.currentTimeMillis() / 1000
        assertNull(accountProblem(UserInfo(true, "Active", now + 86400, "1", "0", listOf("m3u8"))))
        assertNull(accountProblem(UserInfo(true, "Active", null, "1", "0", emptyList())))
        assertNotNull(accountProblem(UserInfo(false, null, null, null, null, emptyList())))
        assertNotNull(accountProblem(UserInfo(true, "Banned", null, null, null, emptyList())))
        assertNotNull(accountProblem(UserInfo(true, "Active", now - 86400, null, null, emptyList())))
    }

    @Test
    fun loginAnswers() = runBlocking {
        val demo = api("demo").userInfo()
        assertTrue(demo.auth)
        assertNull(accountProblem(demo))
        assertTrue("m3u8" in demo.formats)
        assertTrue(accountProblem(api("expired").userInfo())!!.contains("Expired", ignoreCase = true))
        assertTrue(accountProblem(api("banned").userInfo())!!.contains("Banned"))
        val wrong = XtreamApi(Account(Qa.server, "demo", "wrong"), Qa.ctx.cacheDir).userInfo()
        assertFalse(wrong.auth)
        assertEquals(listOf("ts"), api("tsonly").userInfo().formats)
    }

    @Test
    fun listsAndDetails() = runBlocking {
        val a = api("demo")
        assertEquals(6, a.categories(ContentType.LIVE).size)
        val live = a.streams(ContentType.LIVE, null)
        assertEquals(48, live.size)
        assertTrue("ids sent as numbers and as text both work", live.any { it.id == "1" } && live.any { it.id == "2" })
        assertTrue("catch-up channels are recognised", live.any { it.archiveDays > 0 })
        assertTrue("a category has its channels", a.streams(ContentType.LIVE, "1").isNotEmpty())
        assertTrue("empty category is empty", a.streams(ContentType.LIVE, "6").isEmpty())

        val movies = a.streams(ContentType.MOVIE, null)
        assertEquals(72, movies.size)
        assertNull("\"0\" rating means no rating", movies.first { it.id == "102" }.rating)
        assertTrue("several categories per movie", movies.any { it.categoryIds.size > 1 })
        val info = a.vodInfo("101")
        assertNotNull(info.plot)
        assertEquals("mp4", info.ext)

        val series = a.streams(ContentType.SERIES, null)
        assertEquals(18, series.size)
        val s1 = a.seriesInfo("201") // episodes as an object keyed by season
        assertEquals(3, s1.seasons.size)
        assertEquals(4, s1.seasons[1]?.size)
        val s2 = a.seriesInfo("202") // episodes as an array of arrays
        assertTrue("array-style episodes are read", s2.seasons.values.sumOf { it.size } > 0)
        val empty = a.seriesInfo("215")
        assertTrue(empty.seasons.values.sumOf { it.size } == 0)

        val epg = a.shortEpg("3")
        assertEquals(2, epg.size)
        assertTrue("guide titles are decoded from base64", epg[0].title.startsWith("Morning show"))
        assertEquals("نشرة الأخبار", epg[1].title)
        assertEquals(24, a.fullEpg("4").size)
    }

    @Test
    fun brokenServerAnswersDontCrash() = runBlocking {
        val a = api("broken")
        assertTrue(a.userInfo().auth)
        for (type in ContentType.values()) {
            val cats = runCatching { a.categories(type) }
            val items = runCatching { a.streams(type, null) }
            Qa.step("$type: categories ${if (cats.isSuccess) "ok (${cats.getOrNull()?.size})" else "error ${cats.exceptionOrNull()?.javaClass?.simpleName}"}, " +
                "items ${if (items.isSuccess) "ok (${items.getOrNull()?.size})" else "error ${items.exceptionOrNull()?.javaClass?.simpleName}"}")
            // Errors are fine (the screen shows a message); anything else than an Exception is not.
            listOf(cats, items).forEach { r -> r.exceptionOrNull()?.let { assertTrue("Unexpected ${it.javaClass}", it is Exception) } }
        }
    }

    @Test
    fun hugeListsLoadInTime() = runBlocking {
        val a = api("huge")
        for (type in ContentType.values()) {
            val t = System.currentTimeMillis()
            val n = a.streams(type, null).size
            val ms = System.currentTimeMillis() - t
            Qa.metric("huge_${type.name.lowercase()}_load_ms", ms)
            Qa.metric("huge_${type.name.lowercase()}_count", n)
            if (ms > 15_000) Qa.finding("warning", "Huge ${type.name.lowercase()} list takes ${ms / 1000} s to read", "$n items")
        }
    }
}
