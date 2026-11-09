package app.kotakbase

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/** Reading manga from manga extensions (desktop app.js chapterList/readChapter): the series, its chapters, the pages. */
object Reading {
    private val once = ConcurrentHashMap<String, Any>() // asked once per app start, as on desktop

    class Series(val ext: String, val id: String, val title: String, val extName: String)
    private fun series(o: JSONObject) = Series(o.getString("ext"), o.getString("id"), o.optString("title"), o.optString("extName"))

    @Suppress("UNCHECKED_CAST")
    suspend fun search(m: Media): Pair<List<JSONObject>, List<String>> = once.getOrPut("series${m.id}") {
        val titles = Shared.call("api.mangaTitles(${lit(m.json.toString())})") as JSONArray
        Extensions.mangaSearch(JSONObject().put("titles", titles).put("anilistId", m.id).put("malId", m.json.opt("idMal")))
    } as Pair<List<JSONObject>, List<String>>

    /** The series to read it from: the one picked by hand, else the one that's clearly it; null when there's none. */
    suspend fun seriesFor(m: Media): Series? {
        val (results, _) = search(m)
        val picked = Store.string("mangaSources")?.let { runCatching { JSONObject(it).optJSONObject(m.id.toString()) }.getOrNull() }
        results.firstOrNull { it.optString("ext") == picked?.optString("ext") && it.optString("id") == picked?.optString("id") }?.let { return series(it) }
        return (Shared.call("api.series(${lit(m.json.toString())}, ${lit(JSONArray(results).toString())})") as? JSONObject)?.let(::series)
    }

    fun pick(m: Media, s: JSONObject) = Store.putString("mangaSources",
        (Store.string("mangaSources")?.let(::JSONObject) ?: JSONObject()).put(m.id.toString(), JSONObject().put("ext", s.getString("ext")).put("id", s.getString("id"))).toString())

    /** One row per chapter number, in your languages (Settings' subtitle languages), as desktop's chapterRows. */
    @Suppress("UNCHECKED_CAST")
    suspend fun chapters(s: Series): List<JSONObject> = once.getOrPut("chapters${s.ext}${s.id}") {
        val langs = Shared.call("api.readLangs(${lit(JSONArray(Shared.langs("subLangs")).toString())})") as JSONArray
        val list = Extensions.mangaChapters(s.ext, s.id, List(langs.length()) { langs.getString(it) })
        val rows = Shared.call("api.chapters(${lit(JSONArray(list).toString())}, ${lit(langs.toString())})") as JSONArray
        List(rows.length()) { rows.getJSONObject(it) }
    } as List<JSONObject>

    fun label(row: JSONObject) = row.optDouble("number").takeIf { !it.isNaN() }?.let { n -> t("Chapter {n}", "n" to (if (n == floor(n)) n.toLong().toString() else n.toString())) }
        ?: row.optString("title").ifEmpty { "—" }
}

/** The manga page's Chapters tab: the source picker, then a row per chapter. */
@Composable
fun ChapterList(m: Media, p: Store.Progress?, open: (Reading.Series, List<JSONObject>, Int) -> Unit) {
    var version by remember { mutableIntStateOf(0) } // a new source picked
    val state by loading(m.id, version) {
        val (results, errors) = Reading.search(m)
        val s = Reading.seriesFor(m)
        Triple(results, errors, s?.let { it to Reading.chapters(it) })
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val r = state ?: return@Column MutedText(t("Loading…"))
        val (results, errors, chapters) = r.getOrElse { return@Column ErrorText(it) }
        if (results.isNotEmpty()) {
            var menu by remember { mutableStateOf(false) }
            Box {
                TextButton({ menu = true }) { Text("${t("Source")}: " + (chapters?.first?.let { "${it.title} · ${it.extName}" } ?: t("Pick the right series"))) }
                DropdownMenu(menu, { menu = false }) {
                    results.forEach { s -> DropdownMenuItem({ Text("${s.optString("title")} · ${s.optString("extName")}") }, { Reading.pick(m, s); menu = false; version++ }) }
                }
            }
        }
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (chapters == null) return@Column MutedText(if (results.isNotEmpty()) t("Pick the right series") else t("None of your manga extensions has this manga."))
        val (series, rows) = chapters
        if (rows.isEmpty()) MutedText(t("Nothing here yet."))
        val read = chaptersRead(p)
        val next = rows.indexOfFirst { !it.isNull("number") && it.getDouble("number") > read }
        rows.forEachIndexed { i, row ->
            val done = !row.isNull("number") && row.getDouble("number") <= read
            Row(Modifier.fillMaxWidth().clickable { open(series, rows, i) }.background(if (i == next) Panel2 else Bg).padding(vertical = 10.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(Reading.label(row), color = if (done) Muted else TextColor, fontWeight = if (i == next) FontWeight.Bold else FontWeight.Normal)
                if (!row.isNull("number")) Text(row.optString("title"), Modifier.weight(1f), color = Muted, maxLines = 1)
                MutedText(row.optString("group"))
            }
        }
    }
}

/**
 * A vertical reader: the chapter's pages as one strip. Reaching its end counts the chapter read (never lowering your
 * progress: 10.5 counts as 10). shortcut: vertical only; the paged right-to-left and left-to-right modes come with phase 3.
 */
@Composable
fun Reader(m: Media, series: Reading.Series, rows: List<JSONObject>, start: Int, close: () -> Unit) {
    var index by remember { mutableIntStateOf(start) }
    val row = rows[index]
    val pages by loading(series.ext, row.optString("id")) { Extensions.mangaPages(series.ext, row.getString("id")) }
    val list = rememberLazyListState()
    LaunchedEffect(index) { list.scrollToItem(0) }
    LaunchedEffect(index, pages) {
        val n = pages?.getOrNull()?.size ?: return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val number = row.optDouble("number")
            if (last >= n && !number.isNaN() && floor(number).toInt() > chaptersRead(Store.progress(true)[m.id])) Store.markWatched(m, floor(number).toInt())
        }
    }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        LazyColumn(Modifier.fillMaxSize().background(Bg), state = list) {
            item {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${m.title} · ${Reading.label(row)}", Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1)
                    TextButton(close) { Text(t("Close")) }
                }
            }
            val p = pages
            when {
                p == null -> item { MutedText(t("Loading…"), Modifier.padding(16.dp)) }
                p.isFailure -> item { Box(Modifier.padding(16.dp)) { ErrorText(p.exceptionOrNull()!!) } }
                else -> itemsIndexed(p.getOrThrow(), key = { i, _ -> "$index:$i" }) { _, url ->
                    AsyncImage(url, null, Modifier.fillMaxWidth().heightIn(min = 200.dp).background(Panel), contentScale = ContentScale.FillWidth)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    if (index + 1 < rows.size) Button({ index++ }) { Text(t("Next chapter")) } else TextButton(close) { Text(t("Close")) }
                }
            }
        }
    }
}
