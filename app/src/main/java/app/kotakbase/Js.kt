package app.kotakbase

import android.content.res.AssetManager
import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.moduleLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * One QuickJS sandbox, like one browser Worker on desktop: an extension's code (or the desktop's shared JS modules)
 * with the browser APIs of assets/js/web.js. Modules load from `modules` (name → code); a module importing a URL
 * relative to an http(s) one is fetched. Values cross as JSON text.
 */
class Js(private val assets: AssetManager, private val modules: (String) -> String?) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val lock = Mutex() // one evaluation at a time, as a Worker runs one task at a time

    private val js = QuickJs.create(dispatcher, moduleLoader {
        normalize { base, name ->
            when {
                name.startsWith("http://") || name.startsWith("https://") -> name
                base.startsWith("http") -> URI(base).resolve(name).toString()
                else -> name.removePrefix("./")
            }
        }
        load { name -> (modules(name) ?: if (name.startsWith("https://")) get(name) else null)?.let { ModuleContent.Source(it) } }
    }).apply {
        memoryLimit = 64L * 1024 * 1024
        function("__log") { args -> android.util.Log.i("kotakbase-js", args.joinToString(" ")) }
        function("__url") { args -> parseUrl(args[0] as String, args[1] as String) }
        asyncFunction("__sleep") { args -> delay((args[0] as Number).toLong()) }
        asyncFunction("__fetch") { args -> fetch(args[0] as String, args[1] as String, args[2] as String, args[3] as String?) }
    }
    private var ready = false

    private fun get(url: String) = AniList.http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }

    /** Runs `code` (a script; `await` allowed) and returns what it evaluates to, as text. */
    suspend fun run(code: String, timeoutMs: Long = 30_000): String? = lock.withLock {
        withContext(dispatcher) {
            if (!ready) { js.evaluate<Any?>(assets.open("js/web.js").bufferedReader().readText(), "web.js"); ready = true }
            withTimeout(timeoutMs) { js.evaluate<String?>(code, "call.js") }
        }
    }

    /** Evaluates an ES module (its imports go through `modules`). */
    suspend fun module(code: String, name: String) = lock.withLock {
        withContext(dispatcher) {
            if (!ready) { js.evaluate<Any?>(assets.open("js/web.js").bufferedReader().readText(), "web.js"); ready = true }
            js.evaluate<Any?>(code, name, asModule = true)
        }
    }

    fun close() = js.close()

    private fun parseUrl(href: String, base: String): String = runCatching {
        val u = if (base.isEmpty()) URI(href) else URI(base).resolve(href)
        require(u.scheme != null && u.isAbsolute)
        val auth = u.rawUserInfo.orEmpty().split(":", limit = 2)
        JSONObject().put("protocol", u.scheme + ":").put("username", auth[0]).put("password", auth.getOrElse(1) { "" })
            .put("hostname", u.host.orEmpty()).put("port", if (u.port < 0) "" else u.port.toString())
            .put("pathname", u.rawPath.ifEmpty { "/" }).put("search", u.rawQuery?.let { "?$it" } ?: "").put("hash", u.rawFragment?.let { "#$it" } ?: "")
    }.getOrElse { JSONObject().put("error", it.message ?: "invalid") }.toString()

    private suspend fun fetch(url: String, method: String, headers: String, body: String?): String = withContext(Dispatchers.IO) {
        runCatching {
            val h = JSONObject(headers).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.toMutableMap()
            // MangaDex sends browser User-Agents to a bot check and asks apps to name themselves, as on desktop
            if (url.startsWith("https://api.mangadex.org/")) h["user-agent"] = "Kotakbase/${BuildConfig.VERSION_NAME}"
            val req = Request.Builder().url(url).headers(h.toHeaders())
                .method(method, body?.toRequestBody(h["content-type"]?.toMediaTypeOrNull()) ?: if (method == "GET" || method == "HEAD") null else ByteArray(0).toRequestBody())
                .build()
            AniList.http.newCall(req).execute().use { r ->
                JSONObject().put("status", r.code).put("statusText", r.message).put("url", r.request.url.toString())
                    .put("headers", JSONArray(r.headers.map { JSONArray(listOf(it.first, it.second)) }))
                    .put("body", r.body?.string().orEmpty())
            }
        }.getOrElse { JSONObject().put("error", it.message ?: it.toString()) }.toString()
    }
}

/** JSON text as a JS literal for code passed to [Js.run]. */
fun lit(json: String) = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
