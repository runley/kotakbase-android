package app.kotakbase

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.floor

private val MODES = listOf("vertical" to "Vertical", "rtl" to "Right to left", "ltr" to "Left to right")

/** The reading mode picked for a manga, else: Korean and Chinese comics are mostly webtoons (one long strip), Japanese manga reads right to left. */
private fun modeOf(m: Media) = Store.string("readerMode")?.let { runCatching { JSONObject(it).optString(m.id.toString()) }.getOrNull() }?.ifEmpty { null }
    ?: if (m.json.optString("countryOfOrigin") in setOf("KR", "CN", "TW")) "vertical" else "rtl"

/**
 * The manga reader (desktop ui/reader.js): vertical (one strip) or paged, right to left or left to right; unfolded,
 * paged mode shows two pages side by side like a printed book. Tap the sides to turn pages, the middle for the bar.
 * Reaching a chapter's end counts it read (never lowering your progress: 10.5 counts as 10).
 */
@Composable
fun Reader(m: Media, series: Reading.Series, rows: List<JSONObject>, start: Int, close: () -> Unit) {
    var index by remember { mutableIntStateOf(start) }
    var mode by remember { mutableStateOf(modeOf(m)) }
    var bar by remember { mutableStateOf(true) }
    val row = rows[index]
    val pages by loading(series.ext, row.optString("id")) { Extensions.mangaPages(series.ext, row.getString("id")) }
    val list = pages?.getOrNull()
    fun reachedEnd() {
        val number = row.optDouble("number")
        if (!number.isNaN() && floor(number).toInt() > chaptersRead(Store.progress(true)[m.id])) Store.markWatched(m, floor(number).toInt())
    }
    val nextChapter = @Composable {
        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
            if (index + 1 < rows.size) Button({ index++ }) { Text(t("Next chapter")) } else TextButton(close) { Text(t("Close")) }
        }
    }

    Dialog(close, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            if (bar) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${m.title} · ${Reading.label(row)}", Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1)
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton({ menu = true }) { Text(t(MODES.first { it.first == mode }.second)) }
                    DropdownMenu(menu, { menu = false }) {
                        MODES.forEach { (k, label) ->
                            DropdownMenuItem({ Text(t(label)) }, {
                                mode = k; menu = false
                                Store.putString("readerMode", (Store.string("readerMode")?.let(::JSONObject) ?: JSONObject()).put(m.id.toString(), k).toString())
                            })
                        }
                    }
                }
                TextButton(close) { Text(t("Close")) }
            }
            when {
                pages == null -> MutedText(t("Loading…"), Modifier.padding(16.dp))
                pages!!.isFailure -> Box(Modifier.padding(16.dp)) { ErrorText(pages!!.exceptionOrNull()!!) }
                list!!.isEmpty() -> { MutedText(t("Nothing here yet."), Modifier.padding(16.dp)); nextChapter() }
                mode == "vertical" -> {
                    val state = rememberLazyListState()
                    LaunchedEffect(index) { state.scrollToItem(0) }
                    LaunchedEffect(index, list.size) { snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= list.size - 1) reachedEnd() } }
                    LazyColumn(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { bar = !bar } }, state = state) {
                        itemsIndexed(list, key = { i, _ -> "$index:$i" }) { _, url ->
                            AsyncImage(url, null, Modifier.fillMaxWidth().heightIn(min = 200.dp).background(Panel), contentScale = ContentScale.FillWidth)
                        }
                        item { nextChapter() }
                    }
                }
                else -> Paged(list, mode == "rtl", index, ::reachedEnd, toggleBar = { bar = !bar }, end = nextChapter)
            }
        }
    }
}

@Composable
private fun Paged(pages: List<String>, rtl: Boolean, chapter: Int, reachedEnd: () -> Unit, toggleBar: () -> Unit, end: @Composable () -> Unit) {
    val c = LocalConfiguration.current
    // unfolded (wider than tall): two pages side by side, like a printed book
    val perView = if (c.screenWidthDp >= 600 && c.screenWidthDp > c.screenHeightDp) 2 else 1
    val views = pages.chunked(perView)
    val pager = rememberPagerState(pageCount = { views.size + 1 }) // the last one: the chapter's end
    val scope = rememberCoroutineScope()
    LaunchedEffect(chapter) { pager.scrollToPage(0) }
    LaunchedEffect(chapter, views.size) { snapshotFlow { pager.currentPage }.collect { if (it >= views.size - 1) reachedEnd() } }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.weight(1f).pointerInput(rtl) {
            detectTapGestures { o ->
                val third = size.width / 3
                // the left side turns forward when reading right to left, back when left to right
                val forward = when { o.x < third -> rtl; o.x > size.width - third -> !rtl; else -> null }
                if (forward == null) toggleBar() else scope.launch { pager.animateScrollToPage((pager.currentPage + if (forward) 1 else -1).coerceIn(0, views.size)) }
            }
        }, reverseLayout = rtl, key = { "$chapter:$it" }) { i ->
            if (i == views.size) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { end() }
            else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                // right to left, a spread's first page is on the right
                (if (rtl) views[i].reversed() else views[i]).forEach { url ->
                    AsyncImage(url, null, Modifier.weight(1f).fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
        }
        MutedText(t("Page {n} / {total}", "n" to minOf(pager.currentPage * perView + 1, pages.size), "total" to pages.size), Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(6.dp))
    }
}
