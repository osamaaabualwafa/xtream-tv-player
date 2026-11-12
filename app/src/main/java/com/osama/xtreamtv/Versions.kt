package com.osama.xtreamtv

import android.content.Context
import java.util.Locale

/**
 * One card per title (package 2, item 2, approved 2026-11-12).
 *
 * The provider lists every quality and every dub as a title of its own ("Beast 2022 4k", "Beast HD",
 * "Beast مدبلج"), each with one picture quality inside (measured on the owner's playlists). Here:
 * - [clean]: the name shown on cards, pages and the player, without quality and language words;
 * - [copy]: what a copy's name says about it (quality, language);
 * - [group]: copies the notebook knows are the same work become one card, its best copy in front;
 * - [best]: the copy that plays when a title is opened fresh: its original language first, then the
 *   highest quality this TV can play. (Resume plays the copy that was being watched.)
 * Nothing is grouped by name alone: two different films with the same name never merge.
 */
object Versions {

    enum class Lang { ORIGINAL, DUBBED, SUBTITLED, MULTI }

    /** What a copy's name says about it. [height]: 2160, 1080, 720, 480, 1 for CAM, 0 when nothing is said. */
    class Copy(val item: StreamItem, val height: Int, val lang: Lang)

    private val QUALITY = listOf(
        2160 to Regex("(?i)(?<![a-z0-9])(4k|uhd|2160p?)(?![a-z0-9])"),
        1080 to Regex("(?i)(?<![a-z0-9])(fhd|1080p?)(?![a-z0-9])"),
        720 to Regex("(?i)(?<![a-z0-9])(hd|720p?)(?![a-z0-9])"),
        480 to Regex("(?i)(?<![a-z0-9])(sd|480p?)(?![a-z0-9])"),
        1 to Regex("(?i)(?<![a-z0-9])(cam|hdcam|hdts|telesync)(?![a-z0-9])"),
    )
    private val DUB = Regex("(?i)(مدبلج|dubbed|(?<![a-z])dub(?![a-z]))")
    private val SUB = Regex("(?i)(مترجم|subbed|(?<![a-z])sub(?![a-z]))")
    private val MULTI = Regex("(?i)(?<![a-z])multi(?![a-z])")
    private val OTHER = Regex("(?i)(?<![a-z0-9])(hevc|h\\.?265|x265|h\\.?264|x264|3d|hdr|dv)(?![a-z0-9])")
    private val PREFIX = Regex("^\\s*([A-Za-z]{2,3}|[\\u0600-\\u06FF]{2,})\\s*[-|:]\\s+")

    fun copy(item: StreamItem): Copy {
        val n = item.name
        val h = QUALITY.firstOrNull { it.second.containsMatchIn(n) }?.first ?: 0
        val lang = when {
            DUB.containsMatchIn(n) -> Lang.DUBBED
            SUB.containsMatchIn(n) -> Lang.SUBTITLED
            MULTI.containsMatchIn(n) -> Lang.MULTI
            else -> Lang.ORIGINAL
        }
        return Copy(item, h, lang)
    }

    /**
     * The name without quality, format and language words, language prefixes ("EN - "), dots used as
     * spaces and stray punctuation. A name that would come out too short stays as it is.
     *
     * The release year at the end goes too (owner, 2026-11-12: it has its own chip): only when it is
     * the title's [year] (one either way), or in brackets when the year isn't known. A number that is
     * part of the name stays: "Blade Runner 2049" (2017), "Wonder Woman 1984" (2020), "1917".
     */
    fun clean(name: String, year: String? = null): String {
        var n = name.replace(PREFIX, "")
        // "The.Island.2005" -> "The Island 2005" (only when dots stand for spaces).
        if (n.count { it == '.' } >= 2 && !n.contains(' ')) n = n.replace('.', ' ')
        for ((_, rx) in QUALITY) n = n.replace(rx, " ")
        n = n.replace(DUB, " ").replace(SUB, " ").replace(MULTI, " ").replace(OTHER, " ")
        n = n.replace(Regex("[\\[(]\\s*[\\])]"), " ")
        n = n.replace(Regex("\\s+"), " ").trim().trim('.', '-', '|', ':', ',', ' ', '_')
        if (n.length < 2 || n.length < name.trim().length / 3) return name.trim()
        return dropYear(n, year?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toInt() })
    }

    private val END_YEAR = Regex("^(.*?\\S)[\\s\\-–:,]*([\\[(]\\s*)?((?:19|20)\\d{2})(\\s*[\\])])?$")

    private fun dropYear(n: String, year: Int?): String {
        val m = END_YEAR.find(n) ?: return n
        val rest = m.groupValues[1].trim().trimEnd('-', '–', ':', ',', ' ')
        if (rest.length < 2 || rest.all { it.isDigit() }) return n
        val y = m.groupValues[3].toInt()
        val bracketed = m.groupValues[2].isNotEmpty() && m.groupValues[4].isNotEmpty()
        val drop = if (year != null) kotlin.math.abs(y - year) <= 1 else bracketed
        return if (drop) rest else n
    }

    /** For cards: movies and series only (live channels keep their names). */
    fun shownName(item: StreamItem): String {
        if (item.type == ContentType.LIVE) return item.name
        val year = item.year ?: runCatching { Ratings.knownYear(item.type, item.ratingId ?: item.id)?.toString() }.getOrNull()
        // An Arabic provider name for a show that isn't Arabic: TMDB's name (owner, 2026-11-13).
        if (hasArabic(item.name)) runCatching { Ratings.tmdbName(item.type, item.ratingId ?: item.id) }.getOrNull()?.let { return clean(it, year) }
        return clean(item.name, year)
    }

    fun hasArabic(s: String) = s.any { it in '\u0600'..'\u06FF' }

    // ---- What this TV can play ----

    @Volatile private var maxHevc = -1

    /** The tallest HEVC picture this TV decodes (4K copies are HEVC on the owner's playlists). */
    private fun hevcHeight(): Int {
        if (maxHevc >= 0) return maxHevc
        maxHevc = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/hevc", true) } }
                .maxOfOrNull { info -> info.getCapabilitiesForType("video/hevc").videoCapabilities.supportedHeights.upper } ?: 0
        }.getOrDefault(0)
        return maxHevc
    }

    /** False for a copy this TV cannot show (a 4K copy without a 4K HEVC decoder). */
    fun playable(height: Int): Boolean = height < 2000 || hevcHeight() >= 2000

    /** A copy's height: what playing it showed (saved), else what its name says. */
    fun height(copy: Copy, learned: Map<String, Int>): Int = learned["${copy.item.type.name}:${copy.item.id}"]?.takeIf { it > 0 } ?: copy.height

    // ---- Heights learned by playing, kept in memory ----

    @Volatile private var learnedMap: Map<String, Int> = emptyMap()
    @Volatile private var learnedFor = ""
    @Volatile private var learnedDirty = true

    /** "TYPE:id" -> the height a copy showed when it played (active playlist). Off the screen's thread. */
    fun learned(context: Context): Map<String, Int> {
        val pl = Prefs(context).activeId ?: return emptyMap()
        if (!learnedDirty && learnedFor == pl) return learnedMap
        learnedMap = runCatching { Ratings.db.learnedHeights(pl) }.getOrDefault(learnedMap)
        learnedFor = pl; learnedDirty = false
        return learnedMap
    }

    /** The player saw a copy's real picture height: saved, and the cards take it into account. */
    fun noteHeight(context: Context, type: ContentType, id: String, height: Int) {
        if (height <= 0 || type == ContentType.LIVE) return
        val pl = Prefs(context).activeId ?: return
        if (learnedFor == pl && learnedMap["${type.name}:$id"] == height) return
        Ratings.saver.execute {
            runCatching { Ratings.db.saveHeight("$pl:${type.name}:$id", height) }
            learnedDirty = true
        }
    }

    // ---- Groups ----

    /** Copies ordered as they are chosen: original language first, then the tallest picture this TV plays. */
    fun order(copies: List<Copy>, learned: Map<String, Int>): List<Copy> = copies.sortedWith(
        compareBy<Copy>({ if (it.lang == Lang.ORIGINAL || it.lang == Lang.MULTI) 0 else if (it.lang == Lang.SUBTITLED) 1 else 2 },
            { if (playable(height(it, learned))) 0 else 1 },
            // A copy whose quality is not known yet comes before CAM (CAM is always the weakest).
            { -rank(height(it, learned)) }),
    )

    private fun rank(h: Int) = when (h) { 1 -> -1; 0 -> 400; else -> h }

    /** The copy to play when a title is opened fresh. */
    fun best(copies: List<Copy>, learned: Map<String, Int>): Copy? = order(copies, learned).firstOrNull()

    /**
     * One card per work: copies with the same notebook work (and so the same year) are one, its best
     * copy in front, in the place of the first copy of the list. Titles without a known work stay single.
     */
    fun group(items: List<StreamItem>, refOf: (StreamItem) -> String?, learned: Map<String, Int>): List<StreamItem> {
        if (items.size < 2) return items
        val byRef = HashMap<String, ArrayList<StreamItem>>()
        for (i in items) refOf(i)?.let { byRef.getOrPut(it) { ArrayList() }.add(i) }
        if (byRef.values.none { it.size > 1 }) return items
        val out = ArrayList<StreamItem>(items.size)
        val placed = HashSet<String>()
        for (i in items) {
            val ref = refOf(i)
            val same = ref?.let { byRef[it] }
            if (same == null || same.size < 2) { out.add(i); continue }
            if (!placed.add(ref)) continue
            out.add(represent(same, learned) ?: i)
        }
        return out
    }

    /**
     * The card for copies of one title: the best copy; when the copies are in different languages
     * (original and dubbed), it shows the official poster instead of the provider's (owner, 2026-11-13:
     * a dubbed copy's poster is in another language).
     */
    fun represent(copies: List<StreamItem>, learned: Map<String, Int>): StreamItem? {
        val list = copies.map(::copy)
        val chosen = best(list, learned)?.item ?: return null
        if (list.map { it.lang }.distinct().size < 2) return chosen
        val official = copies.firstNotNullOfOrNull { runCatching { Ratings.officialPoster(it.type, it.ratingId ?: it.id) }.getOrNull() }
        return if (official != null) chosen.copy(image = official) else chosen
    }

    // ---- Languages, for the title page and the player ----

    /** "English" from TMDB's "en"; null when unknown. */
    fun languageName(code: String?): String? = code?.takeIf { it.length in 2..3 }?.let {
        Locale(it).getDisplayLanguage(Locale.ENGLISH).takeIf { n -> n.isNotBlank() && !n.equals(it, true) }
    }

    /** How a copy's language reads: "English", "Arabic dubbed", "Arabic subtitles", "Several languages". */
    fun languageLabel(lang: Lang, original: String?): String = when (lang) {
        Lang.ORIGINAL -> original ?: "Original"
        Lang.DUBBED -> "Arabic dubbed"
        Lang.SUBTITLED -> "Arabic subtitles"
        Lang.MULTI -> "Several languages"
    }

    /** "4K", "1080p", "720p", "CAM"; null when unknown. */
    fun qualityLabel(height: Int): String? = when {
        height >= 2000 -> "4K"
        height >= 1000 -> "1080p"
        height >= 700 -> "720p"
        height >= 400 -> "${height}p"
        height == 1 -> "CAM"
        else -> null
    }

    // ---- Copies of one title, for its page and the player ----

    class CopySet(val copies: List<Copy>, val original: String?, val learned: Map<String, Int>)

    /**
     * Every copy of [item] in its whole list (the active playlist), with what playing them has shown.
     * Call off the screen's thread. A title with no known work has itself only.
     */
    suspend fun of(context: Context, api: XtreamApi, item: StreamItem): CopySet {
        val facts = TitleFilter.facts(context)
        val ref = facts["${item.type.name}:${item.id}"]?.ref
        val all = if (ref == null) emptyList() else runCatching { Session.all(api, item.type) }.getOrDefault(emptyList())
        val same = if (ref == null) listOf(item) else all.filter { facts["${it.type.name}:${it.id}"]?.ref == ref }.ifEmpty { listOf(item) }
        val learned = learned(context)
        val lang = ref?.let { runCatching { Ratings.db.originalLanguage(it) }.getOrNull() }
        return CopySet(same.map(::copy), languageName(lang), learned)
    }
}
