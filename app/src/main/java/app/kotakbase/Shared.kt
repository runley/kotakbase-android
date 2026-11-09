package app.kotakbase

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** The desktop's shared JS (release.js, lang.js, manga.js through assets/js/sources.js), run in one sandbox. */
object Shared {
    private lateinit var js: Js
    private val boot = Mutex()
    private var loaded = false

    fun init(context: Context) {
        if (::js.isInitialized) return // the activity can start again in the same process
        js = Js(context.assets) { name -> runCatching { context.assets.open("js/$name").bufferedReader().readText() }.getOrNull() }
    }

    /** Evaluates `expr` against the module's exports (`api`), as JSON. */
    suspend fun call(expr: String): Any? {
        boot.withLock { if (!loaded) { js.module("import * as api from 'sources.js'; globalThis.api = api", "boot-shared.js"); loaded = true } }
        return js.run("JSON.stringify($expr)")?.let { org.json.JSONTokener(it).nextValue() }
    }

    // Settings' audio and subtitle language lists, under the desktop's keys and with its defaults
    fun langs(key: String): List<String> = Store.string(key)?.let { s -> runCatching { JSONArray(s).let { a -> List(a.length()) { a.getString(it) } } }.getOrNull() }
        ?: if (key == "audioLangs") listOf("jpn") else listOf("eng")
    fun prefs() = JSONObject().put("audio", JSONArray(langs("audioLangs"))).put("subs", JSONArray(langs("subLangs")))
        .put("first", Store.string("langFirst")?.trim('"') ?: "audio")

    // ---- ani.zip: the AniDB/TVDB/… ids most extensions search by ----
    private val zip = ConcurrentHashMap<Int, Pair<Long, JSONObject>>()
    private val prequel = ConcurrentHashMap<Int, Boolean>()

    private suspend fun aniZip(id: Int): JSONObject {
        zip[id]?.let { (at, json) -> if (System.currentTimeMillis() - at < 3600_000) return json } // newly aired episodes: refetch after an hour
        val json = withContext(Dispatchers.IO) {
            runCatching { AniList.http.newCall(Request.Builder().url("https://api.ani.zip/mappings?anilist_id=$id").build()).execute().use { JSONObject(it.body?.string().orEmpty()) } }.getOrDefault(JSONObject())
        }
        zip[id] = System.currentTimeMillis() to json
        return json
    }

    // whether a show continues another (a season 2, a part 2); unknown counts as yes, which changes nothing
    private suspend fun hasPrequel(id: Int) = prequel[id] ?: runCatching {
        val edges = AniList.query("query(\$id: Int) { Media(id: \$id) { relations { edges { relationType node { type } } } } }", mapOf("id" to id))
            .getJSONObject("Media").getJSONObject("relations").getJSONArray("edges")
        (0 until edges.length()).any { edges.getJSONObject(it).let { e -> e.optString("relationType") == "PREQUEL" && e.getJSONObject("node").optString("type") == "ANIME" } }
    }.getOrDefault(true).also { prequel[id] = it }

    /** The ids extensions get with a search (desktop app.js mappings()). */
    suspend fun mappings(id: Int, episode: Int): JSONObject {
        val res = aniZip(id)
        var ep = res.optJSONObject("episodes")?.optJSONObject(episode.toString()) ?: JSONObject()
        val mp = res.optJSONObject("mappings") ?: JSONObject()
        // A franchise's first season starts at episode 1. An offset there is the database counting an extra TVDB episode
        // (Food Wars: episode 1 mapped to S01E02), so its own number is used, and no TVDB episode id
        if (ep.optInt("seasonNumber") == 1 && ep.optInt("absoluteEpisodeNumber") != episode && !hasPrequel(id))
            ep = JSONObject(ep.toString()).put("absoluteEpisodeNumber", episode).put("episodeNumber", episode).apply { remove("tvdbId") }
        return JSONObject().apply {
            mp.opt("anidb_id")?.let { put("anidbAid", it) }; ep.opt("anidbEid")?.let { put("anidbEid", it) }
            mp.opt("thetvdb_id")?.let { put("tvdbId", it) }; ep.opt("tvdbId")?.let { put("tvdbEId", it) }
            mp.opt("imdb_id")?.let { put("imdbId", it) }; mp.opt("themoviedb_id")?.let { put("tmdbId", it) }
            mp.opt("mal_id")?.let { put("malId", it) }; mp.opt("kitsu_id")?.let { put("kitsuId", it) }
            ep.opt("absoluteEpisodeNumber")?.let { put("absoluteEpisodeNumber", it) }
            ep.opt("seasonNumber")?.let { put("tvdbSeason", it) }; ep.opt("episodeNumber")?.let { put("tvdbSeasonEpisode", it) }
        }
    }
}
