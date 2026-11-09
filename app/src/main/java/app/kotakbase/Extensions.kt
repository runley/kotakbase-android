package app.kotakbase

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Hayase-compatible extensions, as on desktop (ui/extensions.js): the same manifest JSON, the same gh:/npm:/http link
 * rules and the same calls. Each runs in its own [Js] sandbox. Settings live under the desktop's keys; the code is
 * kept in files, not preferences, since it can be large.
 */
object Extensions {
    private lateinit var dir: File
    private lateinit var assets: android.content.res.AssetManager
    private val runtimes = HashMap<String, Js>()
    private val boot = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped when the installed set or a setting changes, so screens redraw. */
    val version = mutableIntStateOf(0)

    fun init(context: Context) {
        if (::dir.isInitialized) return // the activity can start again in the same process
        dir = File(context.filesDir, "extensions").apply { mkdirs() }
        assets = context.assets
        scope.launch { runCatching { update() } }
    }

    private fun obj(key: String) = Store.string(key)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    val configs get() = obj("extensions") // id -> manifest entry
    private val settings get() = obj("extensions-options") // id -> { enabled, options }
    fun enabled(id: String) = settings.optJSONObject(id)?.optBoolean("enabled", true) ?: false
    fun ids(type: String) = configs.let { c -> c.keys().asSequence().filter { c.getJSONObject(it).optString("type") == type && enabled(it) }.toList() }
    fun hasManga() = ids("manga").isNotEmpty()

    private fun after(url: String) = url.substring(url.indexOf(':') + 1)
    // gh:user/repo[/path] and npm:pkg[/path] resolve through esm.sh, exactly like Hayase
    private fun jsonUrl(url: String): String {
        if (url.startsWith("http")) return url
        require(Regex("^(gh|npm):").containsMatchIn(url)) { t("Invalid URL") }
        val u = "https://esm.sh${if (url.startsWith("gh:")) "/gh" else ""}/${after(url)}"
        return if (u.endsWith(".json")) u else "$u/index.json"
    }
    private fun jsUrl(url: String): String {
        if (url.startsWith("http")) return url
        val parts = after(url).split("/")
        if (url.startsWith("gh:")) return "https://esm.sh/gh/${parts[0]}/${parts[1]}/es2022/${parts.drop(2).joinToString("/")}.mjs"
        if (url.startsWith("npm:")) return "https://esm.sh/${parts[0]}/es2022/${parts.drop(1).joinToString("/")}.mjs"
        throw IllegalArgumentException(t("Invalid URL"))
    }

    private val REQUIRED = listOf("name", "version", "id", "type", "accuracy", "icon", "update", "code")
    private fun valid(c: Any?) = c is JSONObject && REQUIRED.all(c::has)

    private fun get(url: String): Pair<Int, String> = AniList.http.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body?.string().orEmpty() }

    private suspend fun manifest(url: String): List<Any?> = withContext(Dispatchers.IO) {
        val (code, text) = get(jsonUrl(url.trim().trimEnd('/')))
        if (code !in 200..299) throw Exception(t("Manifest request failed ({status})", "status" to code))
        val json = text.trim()
        if (json.startsWith("[")) JSONArray(json).let { a -> List(a.length()) { a.get(it) } } else listOf(JSONObject(json))
    }

    private suspend fun install(c: JSONObject) {
        val id = c.getString("id")
        val (status, code) = withContext(Dispatchers.IO) { get(jsUrl(c.getString("code"))) }
        if (status !in 200..299) throw Exception("$id: code request failed ($status)")
        File(dir, "$id.js").writeText(code)
        boot.withLock { runtimes.remove(id)?.close() }
        Store.putString("extensions", configs.put(id, c).toString())
        val s = settings
        if (!s.has(id)) Store.putString("extensions-options", s.put(id, JSONObject().put("enabled", true).put("options", JSONObject())).toString())
        version.intValue++
    }

    /** The user pastes a manifest link (the same links that work in Hayase). */
    suspend fun importRepo(url: String) {
        val list = manifest(url)
        if (!list.all(::valid)) throw Exception(t("Not a valid Hayase extension manifest"))
        val skipped = list.map { it as JSONObject }.filter { configs.has(it.getString("id")) }.map { it.getString("id") }
        for (c in list.map { it as JSONObject }.filter { !configs.has(it.getString("id")) }) install(c)
        if (skipped.isNotEmpty()) throw Exception(t("Already installed, delete first to replace: {names}", "names" to skipped.joinToString(", ")))
    }

    suspend fun remove(id: String) {
        boot.withLock { runtimes.remove(id)?.close() }
        File(dir, "$id.js").delete()
        Store.putString("extensions", configs.apply { remove(id) }.toString())
        Store.putString("extensions-options", settings.apply { remove(id) }.toString())
        version.intValue++
    }

    fun setEnabled(id: String, on: Boolean) {
        val s = settings
        s.optJSONObject(id)?.put("enabled", on)
        Store.putString("extensions-options", s.toString())
        version.intValue++
    }

    /** At start: pull new versions from each manifest's update link. */
    private suspend fun update() {
        val c = configs
        val links = c.keys().asSequence().map { c.getJSONObject(it).optString("update") }.filter { it.isNotEmpty() }.toSet()
        for (link in links) runCatching { manifest(link) }.getOrNull()?.forEach { n ->
            if (!valid(n)) return@forEach
            val old = configs.optJSONObject((n as JSONObject).getString("id")) ?: return@forEach
            if (old.optString("update") == n.optString("update") && old.optString("version") != n.optString("version")) runCatching { install(n) }
        }
    }

    private suspend fun runtime(id: String): Js = boot.withLock {
        runtimes.getOrPut(id) {
            val url = jsUrl(configs.getJSONObject(id).getString("code"))
            val code = File(dir, "$id.js").readText()
            // the extension is loaded under its own link, so its relative imports resolve like in a browser
            Js(assets) { name -> if (name == url) code else null }.also { it.module("import ext from '$url'; globalThis.__ext = ext", "boot-$id.js") }
        }
    }

    private fun options(id: String): JSONObject {
        val defaults = configs.getJSONObject(id).optJSONObject("options")
        val out = JSONObject()
        defaults?.keys()?.forEach { k -> out.put(k, defaults.getJSONObject(k).opt("default")) }
        settings.optJSONObject(id)?.optJSONObject("options")?.let { o -> o.keys().forEach { out.put(it, o.get(it)) } }
        return out
    }

    /** Calls `fn` of extension `id` with `query` (the extension gets `fetch` in it, as on desktop); the answer as JSON. */
    private suspend fun call(id: String, fn: String, query: JSONObject): Any? {
        val out = runtime(id).run("JSON.stringify(await __ext.$fn(Object.assign(${lit(query.toString())}, { fetch }), ${lit(options(id).toString())}) ?? null)")
        return out?.let { org.json.JSONTokener(it).nextValue() }
    }

    // ---- torrents ----

    /** A source: one torrent, maybe found by several extensions under several names. */
    class Source(val json: JSONObject) {
        val hash: String = json.getString("hash")
        val link: String = json.getString("link")
        val title: String = json.getString("title")
        val seeders = json.optInt("seeders")
        val size = json.optLong("size")
        val type: String? = json.optString("type").ifEmpty { null }
        val accuracy: String = json.optString("accuracy", "low")
        val group: String? = json.optString("group").ifEmpty { null }
        val audio = json.optJSONArray("audio").strings()
        val subs = json.optJSONArray("subs").strings()
        val tags = json.optJSONArray("tags").strings()
    }
    private fun JSONArray?.strings() = this?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()

    private val ACCURACY = listOf("high", "medium", "low")
    private val TYPES = listOf("batch", "best", "alt")
    private fun num(v: Any?) = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { maxOf(0.0, it).toLong() } ?: 0L

    /** Extension output is untrusted: only well-formed results are kept (desktop ext-clean.js). */
    fun clean(results: Any?): List<JSONObject> = (results as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        .filter { r -> r.optString("hash").matches(Regex("(?i)[0-9a-f]{40}|[0-9a-f]{64}")) && r.optString("link").matches(Regex("(?i)(magnet:|https://).*")) }
        .map { r ->
            JSONObject().put("hash", r.getString("hash").lowercase()).put("link", r.getString("link"))
                .put("title", (r.opt("title") as? String) ?: r.getString("hash"))
                .put("seeders", num(r.opt("seeders"))).put("leechers", num(r.opt("leechers"))).put("downloads", num(r.opt("downloads"))).put("size", num(r.opt("size")))
                .put("date", runCatching { Instant.parse(r.optString("date")).toString() }.getOrDefault("1970-01-01T00:00:00Z"))
                .put("accuracy", r.optString("accuracy").takeIf { it in ACCURACY } ?: "low")
                .apply { r.optString("type").takeIf { it in TYPES }?.let { put("type", it) } }
        }

    private val RANK = mapOf("high" to 0, "medium" to 1, "low" to 2)
    // torrents with no seeders can't stream, so they sink below everything else
    private val order = compareByDescending<JSONObject> { it.optLong("seeders") > 0 }.thenBy { RANK[it.optString("accuracy")] ?: 2 }
        .thenByDescending { it.optString("type") == "best" }.thenByDescending { it.optLong("seeders") }

    /**
     * Asks every enabled torrent extension for sources, with the query Hayase sends. onUpdate runs as each answers, so
     * the first sources show without waiting for the slowest. Returns (results, errors); results carry `extensions`
     * and `titles`, the same torrent's names from each extension that found it.
     */
    suspend fun search(query: JSONObject, movie: Boolean, batch: Boolean, onUpdate: (List<JSONObject>) -> Unit): Pair<List<JSONObject>, List<String>> {
        val ids = ids("torrent")
        if (ids.isEmpty()) throw Exception(t("No torrent extensions enabled. Add one in Extensions."))
        val byHash = LinkedHashMap<String, JSONObject>()
        val errors = mutableListOf<String>()
        val lock = Mutex()
        coroutineScope {
            ids.flatMap { id -> listOfNotNull("single", if (movie) "movie" else null, if (batch) "batch" else null).map { id to it } }.map { (id, fn) ->
                async {
                    val name = configs.getJSONObject(id).optString("name", id)
                    val found = runCatching { clean(call(id, fn, query)) }
                    lock.withLock {
                        found.onFailure { e ->
                            val m = e.message ?: e.toString()
                            // a site that's down answers with an HTML error page, which the extension then fails to read as JSON
                            errors += "$name ($fn): " + if (Regex("not valid JSON|Unexpected token|unexpected character").containsMatchIn(m)) t("the site sent an error page instead of results (it's probably down; try again later)") else m
                        }
                        for (r in found.getOrDefault(emptyList())) {
                            val prev = byHash[r.getString("hash")]
                            if (prev != null) {
                                prev.getJSONArray("extensions").put(id)
                                prev.put("seeders", maxOf(prev.optLong("seeders"), r.optLong("seeders")))
                                prev.getJSONArray("titles").let { ts -> if ((0 until ts.length()).none { ts.getString(it) == r.getString("title") }) ts.put(r.getString("title")) }
                                // Seadex's "best" outranks other labels
                                if (r.optString("type") == "best") prev.put("type", "best") else if (!prev.has("type") && r.has("type")) prev.put("type", r.getString("type"))
                            } else byHash[r.getString("hash")] = r.put("extensions", JSONArray().put(id)).put("titles", JSONArray().put(r.getString("title")))
                        }
                        onUpdate(byHash.values.sortedWith(order))
                    }
                }
            }.awaitAll()
        }
        return byHash.values.sortedWith(order) to errors
    }

    // ---- manga: search({ titles, anilistId, malId }), chapters({ id, langs }), pages({ id }) ----

    /** Every enabled manga extension's matches, each tagged with its extension; errors: the ones that failed. */
    suspend fun mangaSearch(query: JSONObject): Pair<List<JSONObject>, List<String>> = coroutineScope {
        val ids = ids("manga")
        if (ids.isEmpty()) throw Exception(t("No manga extensions enabled. Add one in Extensions."))
        val errors = mutableListOf<String>()
        val lists = ids.map { id ->
            async {
                val name = configs.getJSONObject(id).optString("name", id)
                runCatching { (call(id, "search", query) as? JSONArray).objects() }.fold(
                    { list -> list.filter { it.optString("id").isNotEmpty() }.map { s -> JSONObject().put("id", s.optString("id").take(500)).put("title", s.optString("title").take(300).ifEmpty { s.optString("id") }).put("anilistId", s.opt("anilistId") as? Int).put("ext", id).put("extName", name) } },
                    { e -> synchronized(errors) { errors += "$name: ${e.message}" }; emptyList() })
            }
        }.awaitAll()
        lists.flatten() to errors
    }

    suspend fun mangaChapters(ext: String, id: String, langs: List<String>): List<JSONObject> =
        (call(ext, "chapters", JSONObject().put("id", id).put("langs", JSONArray(langs))) as? JSONArray).objects().filter { it.optString("id").isNotEmpty() }.map { c ->
            JSONObject().put("id", c.optString("id").take(500)).put("number", (c.opt("number") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 })
                .put("title", c.optString("title").take(300)).put("group", c.optString("group").take(300)).put("lang", c.optString("lang").take(300).lowercase())
                .put("date", runCatching { Instant.parse(c.optString("date")).toString() }.getOrNull())
        }

    /** The chapter's page images, in order (https only, as on desktop). */
    suspend fun mangaPages(ext: String, id: String): List<String> =
        (call(ext, "pages", JSONObject().put("id", id)) as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty().filter { it.startsWith("https://", true) }

    private fun JSONArray?.objects() = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
}
