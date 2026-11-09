package app.kotakbase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One anime or manga as AniList describes it; `json` is kept whole so it can be saved and re-read. */
class Media(val json: JSONObject) {
    val id = json.getInt("id")
    val type = json.optString("type")
    val isManga get() = type == "MANGA"
    private val titles = json.optJSONObject("title") ?: JSONObject()
    val romaji = titles.str("romaji")
    val native = titles.str("native")
    /** Japanese: the romaji title, which is what AniList's search finds; otherwise English. */
    val title get() = (if (I18n.lang == "ja") romaji else null) ?: titles.str("english") ?: titles.str("userPreferred") ?: romaji ?: ""
    val cover = json.optJSONObject("coverImage")?.str("large")
    val coverXl = json.optJSONObject("coverImage")?.str("extraLarge") ?: cover
    val color = json.optJSONObject("coverImage")?.str("color")
    val banner = json.str("bannerImage")
    val description = plain(json.str("description"))
    val format = json.str("format")
    val status = json.str("status")
    val season = json.str("season")
    val seasonYear = json.optInt("seasonYear").takeIf { it > 0 }
    val startYear = json.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }
    val averageScore = json.optInt("averageScore").takeIf { it > 0 }
    val genres = json.optJSONArray("genres").strings()
    val tags = json.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("isMediaSpoiler") }.map { it.getString("name") } }.orEmpty()
    private val nextEpisode = json.optJSONObject("nextAiringEpisode")?.optInt("episode")?.takeIf { it > 0 }
    /** Fetched with every field (the show page's), not just a card's. */
    val full = json.has("synonyms")

    /** A manga's "episodes" are its chapters (MAX_VALUE: still running, so no total yet). */
    val epCount: Int
        get() = if (isManga) json.optInt("chapters").takeIf { it > 0 } ?: Int.MAX_VALUE
        else json.optInt("episodes").takeIf { it > 0 } ?: nextEpisode?.let { it - 1 } ?: 1
    /** Episodes out so far: an airing show's planned total hasn't all aired yet. */
    val aired get() = nextEpisode?.let { it - 1 } ?: epCount
}

private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k).ifEmpty { null }
private fun JSONArray?.strings() = this?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()

/** AniList's GraphQL API, as the desktop app uses it: the same queries, fields and caching. */
object AniList {
    private const val FIELDS = """id idMal type title { romaji english native userPreferred } synonyms coverImage { large extraLarge color } bannerImage
  description(asHtml: false) episodes chapters volumes countryOfOrigin format status season seasonYear averageScore genres duration
  nextAiringEpisode { episode } startDate { year month day } tags { name rank isMediaSpoiler }"""
    // just what cards show, for long lists
    private const val LIST_FIELDS = """id type title { romaji english native userPreferred } coverImage { large extraLarge color } bannerImage
  episodes chapters format status season seasonYear averageScore genres nextAiringEpisode { episode } startDate { year month day }"""
    const val ROW_LIMIT = 20

    val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<Int, Media>()
    private val cacheAt = ConcurrentHashMap<Int, Long>()
    private val once = ConcurrentHashMap<String, List<Media>>() // lists asked for once per app start

    fun cached(id: Int) = cache[id]

    suspend fun query(query: String, variables: Map<String, Any?> = emptyMap(), tries: Int = 0): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("query", query).put("variables", JSONObject(variables)).toString()
        val req = Request.Builder().url("https://graphql.anilist.co").post(body.toRequestBody("application/json".toMediaType())).build()
        val (code, retryAfter, text) = http.newCall(req).execute().use { Triple(it.code, it.header("Retry-After"), it.body?.string().orEmpty()) }
        // AniList allows ~30-90 requests a minute; past that it says 429 and when to come back
        if (code == 429 && tries < 2) {
            delay((retryAfter?.toLongOrNull() ?: 60).coerceAtMost(60) * 1000)
            return@withContext query(query, variables, tries + 1)
        }
        val json = runCatching { JSONObject(text) }.getOrElse { throw Exception("AniList: HTTP $code") }
        json.optJSONArray("errors")?.let { throw Exception(it.getJSONObject(0).optString("message")) }
        json.getJSONObject("data")
    }

    private fun remember(list: List<Media>) = list.onEach { m -> if (m.full || cache[m.id]?.full != true) { cache[m.id] = m; cacheAt[m.id] = System.currentTimeMillis() } }

    private fun type(manga: Boolean?) = when (manga) { null -> ""; true -> "type: MANGA, "; false -> "type: ANIME, " }

    private suspend fun page(args: String, vars: String, variables: Map<String, Any?>, perPage: Int = 50, pageNum: Int = 1, fields: String = LIST_FIELDS, manga: Boolean? = Store.manga): List<Media> {
        val d = query("query${if (vars.isEmpty()) "" else "($vars)"} { Page(page: $pageNum, perPage: $perPage) { media(${type(manga)}isAdult: false, $args) { $fields } } }", variables)
        val a = d.getJSONObject("Page").getJSONArray("media")
        return remember(List(a.length()) { Media(a.getJSONObject(it)) })
    }

    /**
     * The first `limit` results of a list query (AniList hands out 50 per request). Shown straight from what was saved
     * last time and re-downloaded once per app start in the background, for the next visit, like the desktop's rows.
     */
    suspend fun top(args: String, vars: String = "", variables: Map<String, Any?> = emptyMap(), limit: Int = ROW_LIMIT): List<Media> {
        val manga = Store.manga
        val key = "list:" + JSONArray(listOf(if (manga) "MANGA" else "ANIME", args, JSONObject(variables).toString(), limit, LIST_FIELDS.length))
        once[key]?.let { return it }
        suspend fun download(): List<Media> = coroutineScope {
            (1..(limit + 49) / 50).map { p ->
                async {
                    val get = suspend { page(args, vars, variables, minOf(50, limit), p, manga = manga) }
                    get().ifEmpty { delay(1000); get() } // AniList now and then answers a busy moment with an empty list
                }
            }.awaitAll().flatten().take(limit)
        }.also { list ->
            once[key] = list
            Store.putString(key, JSONObject().put("at", System.currentTimeMillis()).put("list", JSONArray(list.map { it.json })).toString())
        }
        val saved = Store.string(key)?.let { s -> runCatching { JSONObject(s).getJSONArray("list").let { a -> List(a.length()) { Media(a.getJSONObject(it)) } } }.getOrNull() }
        if (saved != null) {
            once[key] = remember(saved)
            scope.launch { runCatching { download() } }
            return saved
        }
        return download()
    }

    fun trending(limit: Int = ROW_LIMIT) = suspend { top("sort: TRENDING_DESC", limit = limit) }
    fun popular(limit: Int = ROW_LIMIT) = suspend { top("sort: POPULARITY_DESC", limit = limit) }
    private fun bySeason(s: Pair<String, Int>, limit: Int) = suspend {
        top("sort: POPULARITY_DESC, season: \$s, seasonYear: \$y", "\$s: MediaSeason, \$y: Int", mapOf("s" to s.first, "y" to s.second), limit)
    }
    fun seasonal(limit: Int = ROW_LIMIT) = bySeason(thisSeason(), limit)
    /** "Fuel the Hype": next season's most followed = most awaited. */
    fun upcoming(limit: Int = ROW_LIMIT) = bySeason(nextSeason(), limit)

    /** Shows by id, in that order; ones already loaded this app start aren't asked for again. */
    suspend fun byIds(ids: List<Int>): List<Media> {
        val missing = ids.filter { cache[it]?.full != true }
        if (missing.isNotEmpty()) coroutineScope {
            missing.chunked(50).map { c -> async { page("id_in: \$ids", "\$ids: [Int]", mapOf("ids" to JSONArray(c)), fields = FIELDS, manga = null) } }.awaitAll()
        }
        return ids.mapNotNull { cache[it] }
    }

    /** A show's page needs current details, so anything older than 5 minutes is fetched again (the old copy if that fails). */
    suspend fun media(id: Int): Media {
        if (cache[id]?.full != true || System.currentTimeMillis() - (cacheAt[id] ?: 0) > 5 * 60_000) {
            runCatching { page("id: \$id", "\$id: Int", mapOf("id" to id), 1, fields = FIELDS, manga = null) }
                .onFailure { if (cache[id]?.full != true) throw it }
        }
        return cache.getValue(id)
    }

    suspend fun search(q: String, filters: Map<String, String>): List<Media> {
        if (q.isBlank() && filters.values.all { it.isEmpty() }) return trending()() // nothing typed or picked: trending, as on desktop
        val (args, vars, values) = searchArgs(q, filters)
        return page(args.joinToString(", "), vars.joinToString(", "), values)
    }

    /** AniList's user recommendations, best rated first. */
    suspend fun similar(id: Int) = once.getOrPut("similar$id") {
        val d = query("query(\$id: Int) { Media(id: \$id) { recommendations(sort: RATING_DESC, perPage: 30) { nodes { mediaRecommendation { isAdult $LIST_FIELDS } } } } }", mapOf("id" to id))
        val nodes = d.getJSONObject("Media").getJSONObject("recommendations").getJSONArray("nodes")
        remember((0 until nodes.length()).mapNotNull { nodes.getJSONObject(it).optJSONObject("mediaRecommendation") }.filter { !it.optBoolean("isAdult") }.map(::Media))
    }

    private val FRANCHISE = setOf("PREQUEL", "SEQUEL", "PARENT", "SIDE_STORY", "SPIN_OFF", "ALTERNATIVE", "SUMMARY", "COMPILATION")

    /**
     * The whole franchise, not just direct neighbours: seasons, side stories, spin-offs etc. followed outward two hops
     * per request, in release order. "OTHER" links count too (except music videos) but aren't followed further.
     */
    suspend fun related(m: Media) = once.getOrPut("related${m.id}") {
        val seen = linkedSetOf(m.id)
        var next = listOf(m.id)
        while (next.isNotEmpty() && seen.size < 100) { // shortcut: stops at 100 entries, enough for any but the longest franchises
            val edges = "relations { edges { relationType node { id type format isAdult REST } } }"
            val d = query("query(\$ids: [Int]) { Page(perPage: 50) { media(id_in: \$ids) { ${edges.replace("REST", edges.replace(" REST", ""))} } } }", mapOf("ids" to JSONArray(next.take(50))))
            next = emptyList()
            val found = mutableListOf<Int>()
            fun JSONObject.node() = getJSONObject("node")
            fun JSONObject.sameKind() = node().optString("type") == m.type && !node().optBoolean("isAdult")
            fun JSONObject.inFranchise() = sameKind() && optString("relationType") in FRANCHISE
            fun JSONObject.loose() = sameKind() && optString("relationType") == "OTHER" && node().optString("format") != "MUSIC"
            val media = d.getJSONObject("Page").getJSONArray("media")
            for (i in 0 until media.length()) {
                val es = media.getJSONObject(i).getJSONObject("relations").getJSONArray("edges")
                for (j in 0 until es.length()) {
                    val e = es.getJSONObject(j)
                    val id = e.node().getInt("id")
                    if (e.loose()) seen += id
                    if (!e.inFranchise()) continue
                    seen += id // its own relations came in the same answer
                    val es2 = e.node().getJSONObject("relations").getJSONArray("edges")
                    for (k in 0 until es2.length()) {
                        val e2 = es2.getJSONObject(k)
                        val id2 = e2.node().getInt("id")
                        if (id2 in seen) continue
                        if (e2.inFranchise()) { seen += id2; found += id2 } else if (e2.loose()) seen += id2
                    }
                }
            }
            next = found
        }
        seen -= m.id
        fun date(x: Media) = x.json.optJSONObject("startDate").let { d -> (d?.optInt("year")?.takeIf { it > 0 } ?: 9999) * 10000 + (d?.optInt("month")?.takeIf { it > 0 } ?: 12) * 100 + (d?.optInt("day")?.takeIf { it > 0 } ?: 31) }
        byIds(seen.toList()).sortedBy(::date)
    }
}
