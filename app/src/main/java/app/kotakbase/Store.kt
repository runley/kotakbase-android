package app.kotakbase

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Kotakbase keeps on the phone, under the desktop app's keys and in its JSON shapes (so backups and AniList sync
 * can be shared later): mode, language, list, progress, scores, each manga one under "manga:".
 */
object Store {
    private lateinit var prefs: SharedPreferences

    /** Bumped on every save, so screens showing your own data redraw. */
    var version by mutableIntStateOf(0)
        private set

    /** Manga mode: the same pages on AniList's manga, with its own list, progress and ratings. */
    var manga by mutableStateOf(false)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("kotakbase", Context.MODE_PRIVATE)
        manga = string("mode") == "\"manga\""
    }

    fun string(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, json: String?) {
        prefs.edit().apply { if (json == null) remove(key) else putString(key, json) }.apply()
        version++
        // your progress, list or ratings changed: AniList sync queues what it needs to send (desktop save())
        when (key) { "progress", "list", "scores" -> AniSync.anime.changed(); "manga:progress", "manga:list", "manga:scores" -> AniSync.manga.changed() }
    }

    private fun k(key: String, mg: Boolean) = if (mg) "manga:$key" else key
    private fun obj(key: String) = string(key)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    fun setMode(toManga: Boolean) {
        manga = toManga
        putString("mode", JSONObject.quote(if (toManga) "manga" else "anime"))
    }

    var language: String?
        get() = string("language")?.let { runCatching { JSONArray("[$it]").optString(0) }.getOrNull() }?.ifEmpty { null }
        set(v) = putString("language", v?.let(JSONObject::quote))

    // ---- your list: newest first ----
    fun myList(mg: Boolean = manga): List<Int> =
        string(k("list", mg))?.let { s -> runCatching { JSONArray(s).let { a -> List(a.length()) { a.getInt(it) } } }.getOrNull() } ?: emptyList()

    fun toggleList(id: Int, mg: Boolean = manga) {
        val list = myList(mg)
        putString(k("list", mg),JSONArray(if (id in list) list - id else listOf(id) + list).toString())
    }

    // ---- progress: id -> { episode, time, duration, at, hidden?, finished? }; a manga's episode is its chapter ----
    data class Progress(val episode: Int, val time: Double, val duration: Double, val at: Long, val hidden: Boolean, val finished: Boolean)

    fun progress(mg: Boolean = manga): Map<Int, Progress> {
        val o = obj(k("progress", mg))
        return o.keys().asSequence().mapNotNull { id ->
            val p = o.optJSONObject(id) ?: return@mapNotNull null
            id.toInt() to Progress(p.optInt("episode", 1), p.optDouble("time", 0.0), p.optDouble("duration", 1.0), p.optLong("at"),
                p.optBoolean("hidden"), p.optBoolean("finished"))
        }.toMap()
    }

    private fun editProgress(m: Media, change: JSONObject.() -> Unit) {
        val key = k("progress", m.isManga)
        putString(key, obj(key).apply(change).toString())
    }

    /** Everything up to and including n watched (read). */
    fun markWatched(m: Media, n: Int) = editProgress(m) {
        val last = n >= m.epCount
        put(m.id.toString(), JSONObject().apply {
            put("episode", if (last) n else n + 1); put("time", if (last) 1 else 0); put("duration", 1); put("at", System.currentTimeMillis())
            if (last && m.status == "FINISHED") put("finished", true)
        })
    }

    /** n onwards unwatched. */
    fun markUnwatched(m: Media, n: Int) {
        AniSync.of(m.isManga).unwatch(m.id) // the one change allowed to lower AniList's progress
        editProgress(m) {
            if (n <= 1) remove(m.id.toString())
            else put(m.id.toString(), JSONObject().apply { put("episode", n); put("time", 0); put("duration", 1); put("at", System.currentTimeMillis()) })
        }
    }

    /**
     * The player's progress (desktop app.js play() onProgress): watched once the ending credits start or 90% in; the
     * last watched episode is saved as played to the end; the final episode of a finished show leaves Continue Watching.
     * Returns whether this episode counts as watched.
     */
    fun saveProgress(m: Media, episode: Int, time: Double, duration: Double, credits: Double?): Boolean {
        val watched = time > duration * 0.9 || (credits != null && time >= credits)
        val last = episode >= m.epCount
        val old = obj(k("progress", m.isManga)).optJSONObject(m.id.toString())
        val wasFinished = isFinished(m, progress(m.isManga)[m.id])
        editProgress(m) {
            put(m.id.toString(), JSONObject().apply {
                if (watched && !last) { put("episode", episode + 1); put("time", 0); put("duration", 1) }
                else { put("episode", episode); put("time", if (watched) duration else time); put("duration", duration) }
                // watching a show you'd finished again: it stays in Continue Watching
                if (watched && last && m.status == "FINISHED") put("finished", true)
                else if (wasFinished || old?.optBoolean("rewatch") == true) put("rewatch", true)
                put("at", System.currentTimeMillis())
            })
        }
        return watched
    }

    /** One history entry per playback (desktop logWatched); the last 2000 are kept. */
    fun logWatched(m: Media, episode: Int) {
        val key = k("history", m.isManga)
        val list = string(key)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        list.put(JSONObject().put("id", m.id).put("episode", episode).put("at", System.currentTimeMillis()).put("finished", episode >= m.epCount && m.status == "FINISHED"))
        putString(key, JSONArray((maxOf(0, list.length() - 2000) until list.length()).map { list.get(it) }).toString())
    }

    fun setHidden(m: Media, hidden: Boolean) = editProgress(m) {
        optJSONObject(m.id.toString())?.let { if (hidden) it.put("hidden", true) else it.remove("hidden") }
    }

    // ---- your 1-100 scores (AniList's scale) ----
    fun scores(mg: Boolean = manga): Map<Int, Int> = obj(k("scores", mg)).let { o -> o.keys().asSequence().associate { it.toInt() to o.optInt(it) } }

    fun setScore(m: Media, score: Int) {
        val key = k("scores", m.isManga)
        putString(key, obj(key).apply { if (score > 0) put(m.id.toString(), score.coerceAtMost(100)) else remove(m.id.toString()) }.toString())
    }
}
