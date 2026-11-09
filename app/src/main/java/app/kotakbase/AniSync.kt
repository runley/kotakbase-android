package app.kotakbase

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * AniList sign-in (desktop anilist-auth.js): AniList's sign-in page redirects to REDIRECT with the token in the URL
 * fragment, and the in-app sign-in view catches that redirect before it loads. The token stays in the app's private
 * storage; screens only see who is signed in.
 */
object Auth {
    const val REDIRECT = "https://anilist.co/api/v2/oauth/pin" // registered with the app's Client ID on AniList
    val configured get() = BuildConfig.ANILIST_CLIENT_ID.isNotEmpty()
    val authorizeUrl get() = "https://anilist.co/api/v2/oauth/authorize?client_id=${Uri.encode(BuildConfig.ANILIST_CLIENT_ID)}&response_type=token"
    private lateinit var prefs: android.content.SharedPreferences

    class User(val id: Int, val name: String, val avatar: String?)
    var user by mutableStateOf<User?>(null)
        private set
    var expired by mutableStateOf(false)
        private set
    /** Set while the sign-in page should show; completed with null (signed in), an error, or "" (closed). */
    var signingIn by mutableStateOf<CompletableDeferred<String?>?>(null)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("anilist", Context.MODE_PRIVATE)
        read()
    }

    private fun read() {
        val a = prefs.getString("account", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        user = a?.optJSONObject("user")?.let { User(it.getInt("id"), it.optString("name"), it.optString("avatar").ifEmpty { null }) }
        expired = a != null && (a.optString("token").isEmpty() || (a.optLong("expires") in 1 until System.currentTimeMillis()))
    }
    private fun token() = prefs.getString("account", null)?.let { JSONObject(it).optString("token") }.orEmpty()
    val signedIn get() = user != null && !expired

    /** The redirect to REDIRECT: (token, expires) or an error; null for any other URL. */
    fun parseRedirect(url: String): Result<Pair<String, Long?>>? {
        val u = Uri.parse(url)
        if ("${u.scheme}://${u.host}${u.path}" != REDIRECT) return null
        val hash = Uri.parse("x://x?" + (u.fragment ?: ""))
        fun get(k: String) = hash.getQueryParameter(k) ?: u.getQueryParameter(k)
        get("error")?.let { return Result.failure(Exception(get("error_description") ?: it)) }
        val token = hash.getQueryParameter("access_token") ?: return Result.failure(Exception("AniList sent no access token"))
        val expiresIn = hash.getQueryParameter("expires_in")?.toLongOrNull()
        return Result.success(token to expiresIn?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 1000 })
    }

    /** AniList's GraphQL as the signed-in user: (status, data or error, Retry-After); status 0 = unreachable. */
    class Answer(val status: Int, val data: JSONObject? = null, val error: String? = null, val retryAfter: Long? = null)

    suspend fun query(query: String, variables: JSONObject = JSONObject(), with: String = token()): Answer = withContext(Dispatchers.IO) {
        if (with.isEmpty()) return@withContext Answer(401)
        val req = Request.Builder().url("https://graphql.anilist.co").header("Authorization", "Bearer $with").header("Accept", "application/json")
            .post(JSONObject().put("query", query).put("variables", variables).toString().toRequestBody("application/json".toMediaType())).build()
        val r = runCatching { AniList.http.newCall(req).execute() }.getOrElse { return@withContext Answer(0, error = it.message) }
        r.use {
            val json = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
            val error = json?.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
            if (it.isSuccessful && json?.optJSONObject("data") != null && error == null) return@withContext Answer(200, json.getJSONObject("data"))
            val status = if (it.code == 401 || Regex("(?i)invalid token|unauthori[sz]ed").containsMatchIn(error ?: "")) 401 else if (it.isSuccessful) 400 else it.code
            // expired or revoked: forget the token, keep who it was so Settings can say "sign in again"
            if (status == 401 && with == token()) { prefs.edit().putString("account", JSONObject().put("user", JSONObject(prefs.getString("account", "{}")!!).optJSONObject("user")).toString()).apply(); read() }
            Answer(status, error = error ?: it.message, retryAfter = it.header("retry-after")?.toLongOrNull())
        }
    }

    /** Opens AniList's sign-in page; null when signed in, else the error ("" when the page was just closed). */
    suspend fun signIn(): String? {
        if (!configured) return "AniList sign-in isn't set up in this build (no Client ID)."
        val done = CompletableDeferred<String?>()
        signingIn = done
        return done.await().also { signingIn = null }
    }

    /** The sign-in page caught the redirect: who is it? */
    suspend fun finish(token: String, expires: Long?): String? {
        val r = query("{ Viewer { id name avatar { medium } } }", with = token)
        if (r.status != 200) return "AniList didn't accept the sign-in (${r.error ?: r.status})."
        val v = r.data!!.getJSONObject("Viewer")
        prefs.edit().putString("account", JSONObject().put("token", token).put("expires", expires ?: 0)
            .put("user", JSONObject().put("id", v.getInt("id")).put("name", v.optString("name")).put("avatar", v.optJSONObject("avatar")?.optString("medium"))).toString()).apply()
        read()
        return null
    }

    fun signOut() {
        prefs.edit().remove("account").apply()
        android.webkit.CookieManager.getInstance().removeAllCookies(null) // AniList's own sign-in cookie too
        read()
    }
}

/**
 * Kotakbase's progress, Your List and ratings ⇄ the AniList list, only while signed in (desktop createSync): the
 * desktop's rules (merge, pushTarget, localEntry, toLocal, saveVars) run unchanged through [Shared]; this is the loop
 * around them: pulling the list, a persisted outbox of changed shows sent one at a time, paced as on desktop.
 */
class AniSync(private val manga: Boolean) {
    companion object {
        val anime = AniSync(false)
        val manga = AniSync(true)
        fun of(mg: Boolean) = if (mg) manga else anime
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** The first sync's question: (title, body lines) waiting for Sync (true) or Cancel. */
        var asking by mutableStateOf<Triple<String, List<String>, CompletableDeferred<Boolean>>?>(null)
    }

    private val type = if (manga) "MANGA" else "ANIME"
    private fun key(k: String) = if (manga) "manga:$k" else k
    private val BASE = if (manga) "anilistBaseManga" else "anilistBase" // { user, at, entries: { mediaId: entry as last synced } }
    private val OUTBOX = if (manga) "anilistOutboxManga" else "anilistOutbox"
    private val GAP = 2000L // between requests: 30 a minute, inside AniList's limit

    var syncing by mutableStateOf(false)
        private set
    var problem by mutableStateOf("")
        private set
    var note by mutableStateOf("")
        private set
    var version by mutableIntStateOf(0) // bumped when the outbox or base changes, for Settings
        private set
    @Volatile var applying = false // saving AniList's entries here, which isn't a change to send back
        private set
    private var lastLocal = emptyMap<String, String>()
    private val pumping = Mutex()
    private var fails = 0

    private fun obj(k: String) = Store.string(k)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun base() = obj(BASE)
    private fun box() = obj(OUTBOX) ?: JSONObject()
    fun pending() = box().length()
    private fun active() = Auth.signedIn && base()?.optInt("user") == Auth.user?.id
    private fun save(k: String, v: Any?) { Store.putString(k, v?.toString()); version++ }

    private fun shows(): JSONObject = JSONObject().apply {
        (obj(key("progress"))?.keys()?.asSequence().orEmpty() + Store.myList(manga).map { it.toString() }).forEach { id ->
            AniList.cached(id.toInt())?.json?.let { put(id, it) }
        }
    }
    private suspend fun locals() = Shared.call("api.syncLocals(${lit((obj(key("progress")) ?: JSONObject()).toString())}, ${lit(JSONArray(Store.myList(manga)).toString())}, " +
        "${lit((obj(key("scores")) ?: JSONObject()).toString())}, ${lit(shows().toString())})") as JSONObject
    private fun snapshot(now: JSONObject) { lastLocal = now.keys().asSequence().associateWith { now.get(it).toString() } }

    private fun enqueue(id: String, unwatch: Boolean = false) {
        val box = box()
        val item = box.optJSONObject(id) ?: JSONObject()
        if (unwatch) item.put("unwatch", true)
        box.put(id, item.put("n", item.optInt("n") + 1))
        save(OUTBOX, box)
        pump()
    }

    /** The app saved progress, list or ratings: queue the shows whose entry changed. */
    fun changed() {
        if (applying) return
        scope.launch {
            if (!active()) return@launch
            val now = locals()
            for (id in now.keys().asSequence().toSet() + lastLocal.keys) if (now.opt(id)?.toString() != lastLocal[id]) enqueue(id)
            snapshot(now)
        }
    }

    /** "Mark unwatched": the one change allowed to lower AniList's progress; called before the app saves it. */
    fun unwatch(id: Int) { if (active()) enqueue(id.toString(), unwatch = true) }

    private fun fromApi(e: JSONObject) = JSONObject().put("entryId", e.getInt("id")).put("status", e.optString("status"))
        .put("progress", e.optInt("progress")).put("score", e.optInt("score"))

    private suspend fun sendOne(id: String, item: JSONObject): Auth.Answer {
        val b = base() ?: return Auth.Answer(401)
        val entries = b.optJSONObject("entries") ?: JSONObject()
        val L = locals().opt(id) ?: JSONObject.NULL
        val old = entries.opt(id) ?: JSONObject.NULL
        val unwatch = item.optBoolean("unwatch")
        val first = Shared.call("api.syncPush($id, ${lit(L.toString())}, ${lit(old.toString())}, $unwatch, ${lit(old.toString())})") as JSONObject
        if (first.isNull("target")) return Auth.Answer(200, error = "skipped")
        // the base is from the last pull, maybe days ago: target AniList's entry as it is now
        var r = Auth.query("query(\$userId: Int, \$mediaId: Int) { MediaList(userId: \$userId, mediaId: \$mediaId) { id mediaId status progress score(format: POINT_100) } }",
            JSONObject().put("userId", b.optInt("user")).put("mediaId", id.toInt()))
        if (r.status != 200 && r.status != 404) return r
        val live = r.data?.optJSONObject("MediaList")?.let(::fromApi)
        val push = Shared.call("api.syncPush($id, ${lit(L.toString())}, ${lit(old.toString())}, $unwatch, ${lit((live ?: JSONObject.NULL).toString())})") as JSONObject
        if (push.isNull("target")) return Auth.Answer(200)
        delay(GAP)
        if (push.opt("target") == "delete") {
            r = Auth.query("mutation(\$id: Int) { DeleteMediaListEntry(id: \$id) { deleted } }", JSONObject().put("id", live?.optInt("entryId")))
            if (r.status == 200 || r.status == 404) { entries.remove(id); r = Auth.Answer(200) }
        } else {
            val v = push.getJSONObject("vars")
            val types = mapOf("mediaId" to "Int", "status" to "MediaListStatus", "progress" to "Int", "scoreRaw" to "Int")
            val args = v.keys().asSequence().toList()
            r = Auth.query("mutation(${args.joinToString { "\$$it: ${types[it]}" }}) { SaveMediaListEntry(${args.joinToString { "$it: \$$it" }}) { id mediaId status progress score(format: POINT_100) } }", v)
            if (r.status == 200) {
                val saved = fromApi(r.data!!.getJSONObject("SaveMediaListEntry"))
                entries.put(id, saved)
                // a 73 on a 10-point AniList list is kept as 70: take that here too, or every sync would send 73 again
                val kept = saved.optInt("score")
                if (kept != push.getJSONObject("target").optInt("score")) {
                    val scores = obj(key("scores")) ?: JSONObject()
                    if (kept > 0) scores.put(id, kept) else scores.remove(id)
                    applying = true
                    try { save(key("scores"), scores) } finally { applying = false }
                }
            }
        }
        if (r.status == 200) save(BASE, b.put("entries", entries).put("at", System.currentTimeMillis()))
        return r
    }

    /** Sends the outbox one show at a time, paced; never awaited. */
    private fun pump() {
        if (pumping.isLocked) return
        scope.launch {
            pumping.withLock {
                while (active()) {
                    val box = box()
                    val id = box.keys().asSequence().firstOrNull() ?: break
                    val item = box.getJSONObject(id)
                    val r = runCatching { sendOne(id, item) }.getOrElse { Auth.Answer(0, error = it.message) }
                    if (r.status == 401) break
                    val failed = r.status == 0 || r.status == 429 || r.status >= 500
                    // anything else (sent, or refused, e.g. a show AniList no longer has) leaves the queue, unless it changed meanwhile
                    if (!failed) { val now = box(); if (now.optJSONObject(id)?.optInt("n") == item.optInt("n")) { now.remove(id); save(OUTBOX, now) } }
                    problem = if (!failed) "" else if (r.status == 429) t("AniList is busy, waiting a moment") else t("AniList unreachable")
                    val wait = when {
                        r.error == "skipped" -> 0L
                        r.status == 429 -> minOf(60, r.retryAfter ?: 60) * 1000
                        r.status == 0 || r.status >= 500 -> minOf(5 * 60_000L, 5000L shl fails.coerceAtMost(6))
                        else -> GAP
                    }
                    fails = if (!failed) 0 else if (r.status == 429) fails else fails + 1
                    delay(wait)
                }
            }
        }
    }

    /** Pulls the whole list and merges it, the first time after asking. */
    private suspend fun pull() {
        val user = Auth.user ?: return
        val first = !active()
        val r = Auth.query("query(\$userId: Int) { MediaListCollection(userId: \$userId, type: $type) { lists { entries { " +
            "id mediaId status progress score(format: POINT_100) updatedAt media { episodes chapters isAdult } } } } }", JSONObject().put("userId", user.id))
        if (r.status == 401) return
        if (r.status != 200) throw Exception(if (r.status == 0) t("AniList unreachable") else "AniList: ${r.error ?: r.status}")
        val raw = JSONObject()
        val remote = JSONObject()
        val lists = r.data!!.getJSONObject("MediaListCollection").getJSONArray("lists")
        for (i in 0 until lists.length()) {
            val es = lists.getJSONObject(i).getJSONArray("entries")
            for (j in 0 until es.length()) {
                val e = es.getJSONObject(j)
                if (e.optJSONObject("media")?.optBoolean("isAdult") == true) continue // custom lists repeat entries
                raw.put(e.getInt("mediaId").toString(), e); remote.put(e.getInt("mediaId").toString(), fromApi(e))
            }
        }
        val old = if (first) JSONObject() else base()?.optJSONObject("entries") ?: JSONObject()
        val box = if (first) JSONObject() else box()
        val local = locals()
        val plan = Shared.call("api.syncPull(${lit(local.toString())}, ${lit(remote.toString())}, ${lit(old.toString())}, ${lit(box.toString())})") as JSONObject
        val imports = plan.getJSONArray("imports")
        val sends = plan.getJSONArray("sends")
        if (first && (imports.length() > 0 || sends.length() > 0)) {
            val shows = { n: Int -> t("{n} shows", "n" to n) }
            val answer = CompletableDeferred<Boolean>()
            asking = Triple(if (manga) t("First AniList manga sync for {name}", "name" to user.name) else t("First AniList sync for {name}", "name" to user.name), listOf(
                t("{shows} from AniList go into Kotakbase", "shows" to shows(imports.length())),
                t("{shows} from Kotakbase go to your AniList list", "shows" to shows(sends.length())),
                t("Progress is never lowered on either side. Cancel signs you out and changes nothing.")), answer)
            val ok = answer.await()
            asking = null
            if (!ok) { Auth.signOut(); return }
        }
        // AniList as it is now; an entry deleted on AniList and unchanged here keeps its old copy, so it isn't sent back
        val entries = JSONObject(remote.toString())
        val sendIds = (0 until sends.length()).map { sends.getInt(it).toString() }.toSet()
        old.keys().forEach { id -> if (!remote.has(id) && local.has(id) && id !in sendIds) entries.put(id, old.get(id)) }
        save(BASE, JSONObject().put("user", user.id).put("at", System.currentTimeMillis()).put("entries", entries))
        if (imports.length() > 0) {
            val applied = Shared.call("api.syncApply(${lit(imports.toString())}, ${lit(raw.toString())}, ${lit((obj(key("progress")) ?: JSONObject()).toString())}, " +
                "${lit(JSONArray(Store.myList(manga)).toString())}, ${lit((obj(key("scores")) ?: JSONObject()).toString())}, ${lit(shows().toString())})") as JSONObject
            applying = true
            try { save(key("progress"), applied.get("progress")); save(key("list"), applied.get("list")); save(key("scores"), applied.get("scores")) } finally { applying = false }
        }
        snapshot(locals())
        if (first) {
            save(OUTBOX, JSONObject())
            note = t("Imported {imported}, sending {sent} to AniList.", "imported" to t("{n} shows", "n" to imports.length()), "sent" to sends.length())
        }
        sendIds.forEach { enqueue(it) }
    }

    fun syncNow() {
        if (syncing) return
        syncing = true
        scope.launch {
            runCatching { pull() }.onSuccess { if (pending() == 0) problem = ""; pump() }.onFailure { problem = it.message ?: it.toString() }
            syncing = false
        }
    }

    /** At start, or right after signing in (first): the first sync for this account, else pull after a moment. */
    fun start(first: Boolean = false) {
        if (!Auth.signedIn) return
        if (base()?.optInt("user") != Auth.user?.id) { if (first) { save(BASE, null); save(OUTBOX, JSONObject()); syncNow() }; return }
        scope.launch {
            snapshot(locals())
            pump()
            delay(5000) // after the home rows' own AniList requests
            syncNow()
        }
    }

    fun signedOut() { save(BASE, null); save(OUTBOX, JSONObject()); note = ""; problem = "" }
}
