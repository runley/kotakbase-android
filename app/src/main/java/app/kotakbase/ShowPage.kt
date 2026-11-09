package app.kotakbase

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** A show's (or a manga's) page: what AniList knows, your list, progress and rating, and its related and similar shows. */
@Composable
fun ShowPage(nav: Nav, id: Int) {
    val result by loading(id) { AniList.media(id) }
    val m = result?.getOrNull()
    if (m == null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { result?.exceptionOrNull()?.let { ErrorText(it) } ?: MutedText(t("Loading…")) }
        return
    }
    Store.version // redraw when your list, progress or rating changes
    val mg = m.isManga
    val p = Store.progress(mg)[id]
    Extensions.version.intValue // redraw when extensions are added or switched
    val reads = mg && Extensions.hasManga()
    val tabs = if (mg) listOfNotNull(if (reads) "Chapters" else null, "Related", "Similar") else listOf("Episodes", "Related", "Similar")
    var tab by rememberSaveable(id) { mutableStateOf(0) }
    if (tab >= tabs.size) tab = 0
    var reading by remember { mutableStateOf<Triple<Reading.Series, List<org.json.JSONObject>, Int>?>(null) }
    val scope = rememberCoroutineScope()
    var readError by remember { mutableStateOf<String?>(null) }

    reading?.let { (s, rows, i) -> Reader(m, s, rows, i) { reading = null } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Head(m, p) }
        item {
            Actions(m, p, watch = { nav.go(Page.Sources(m.id, p?.episode ?: 1)) }, read = if (!reads) null else { n ->
                // "Read chapter n": the first chapter numbered n there is, or the one after it
                scope.launch {
                    runCatching {
                        val s = Reading.seriesFor(m) ?: throw Exception(t("None of your manga extensions has this manga."))
                        val rows = Reading.chapters(s)
                        val i = rows.indexOfFirst { !it.isNull("number") && it.getDouble("number") >= n }
                        if (i < 0) throw Exception(t("Nothing here yet."))
                        reading = Triple(s, rows, i)
                    }.onFailure { readError = it.message }
                }
            })
        }
        readError?.let { item { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) } }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.genres.forEach { g -> AssistChip({ nav.go(Page.Search(genre = g)) }, { Text(t(g)) }) }
                if (!mg) m.tags.take(10).forEach { tag -> AssistChip({ nav.go(Page.Search(tag = tag)) }, { Text(tag) }) }
            }
        }
        item {
            PrimaryTabRow(tab, Modifier.padding(top = 8.dp), containerColor = Bg) {
                tabs.forEachIndexed { i, name -> Tab(tab == i, { tab = i }, text = { Text(t(name)) }, selectedContentColor = Accent, unselectedContentColor = Muted) }
            }
        }
        when (tabs[tab]) {
            "Episodes" -> items((1..minOf(m.epCount, 2000)).toList(), key = { "ep$it" }) { EpisodeRow(m, p, it) }
            "Chapters" -> item(key = "chapters") { ChapterList(m, p) { s, rows, i -> reading = Triple(s, rows, i) } }
            else -> item(key = tabs[tab]) { Shows(nav, m, tabs[tab]) }
        }
    }
}

@Composable
private fun Head(m: Media, p: Store.Progress?) {
    Box(Modifier.fillMaxWidth().height(150.dp).background(Panel2)) {
        AsyncImage(m.banner ?: m.coverXl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.6f)
    }
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AsyncImage(m.coverXl, null, Modifier.width(104.dp).aspectRatio(230f / 325f).clip(RoundedCornerShape(10.dp)).background(Panel2), contentScale = ContentScale.Crop)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            m.native?.let { MutedText(it) }
            Text(m.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val total = m.epCount
            val pills = if (m.isManga) {
                val read = chaptersRead(p)
                listOfNotNull("${t("Chapters read")}: " + if (total < Int.MAX_VALUE) "$read / $total" else "$read", fmt(m.format),
                    STATUS[m.status]?.let { t(it) }, m.startYear?.toString(), m.averageScore?.let { "$it%" })
            } else {
                listOfNotNull(
                    if (p != null) "${minOf(p.episode, total)} / ${t("{n} episodes", "n" to total)}" else t("{n} episodes", "n" to total),
                    fmt(m.format), STATUS[m.status]?.let { t(it) },
                    m.season?.let { s -> "${SEASON[s]?.let { t(it) } ?: s} ${m.seasonYear ?: ""}".trim() }, m.averageScore?.let { "$it%" })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pills.filter { it.isNotEmpty() }.forEach { Text(it, Modifier.clip(RoundedCornerShape(50)).background(Panel2).padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
    if (m.description.isNotEmpty()) {
        var more by remember { mutableStateOf(false) }
        Text(m.description, Modifier.padding(horizontal = 16.dp).clickable { more = !more }, color = Muted,
            style = MaterialTheme.typography.bodyMedium, maxLines = if (more) Int.MAX_VALUE else 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun Actions(m: Media, p: Store.Progress?, watch: () -> Unit, read: ((Int) -> Unit)?) {
    val mg = m.isManga
    FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        val done = chaptersRead(p)
        // with a manga extension the button reads the chapter; without one it marks it read
        if (mg && done < m.epCount) Button({ if (read != null) read(done + 1) else Store.markWatched(m, done + 1) }) {
            Text(if (read != null) t("Read chapter {n}", "n" to done + 1) else t("Mark chapter {n} read", "n" to done + 1))
        }
        if (!mg && m.status != "NOT_YET_RELEASED") Button(watch) { Text("▶ " + if (p != null) t("Continue · Ep {n}", "n" to p.episode) else t("Watch")) }
        val read = done
        val inList = m.id in Store.myList(mg)
        FilledIconToggleButton(inList, { Store.toggleList(m.id, mg) }) { Icon(Icons.bookmark, t("Save to Your List")) }
        if (!mg) {
            // seen elsewhere: counts as finished; an airing show is only caught up to what's out
            if (!isFinished(m, p) && m.status != "NOT_YET_RELEASED" && !((p?.episode ?: 0) > m.aired))
                FilledTonalIconButton({ Store.markWatched(m, m.aired) }) { Icon(Icons.check, t("Already watched")) }
            if (inContinue(m, p)) FilledTonalIconButton({ Store.setHidden(m, true) }) { Icon(Icons.close, t("Remove from Continue Watching")) }
            if (p?.hidden == true && !isFinished(m, p)) FilledTonalIconButton({ Store.setHidden(m, false) }) { Icon(Icons.undo, t("Put back in Continue Watching")) }
        } else {
            NumberField(t("Chapters read"), read) { n -> if (n <= 0) Store.markUnwatched(m, 1) else Store.markWatched(m, minOf(n, m.epCount)) }
        }
        if (p != null) {
            val score = Store.scores(mg)[m.id]
            val label = if (score != null) t("Your rating") else if (isFinished(m, p)) t("Finished! How was it?") else t("Rate it")
            NumberField("$label (1–100)", score ?: 0) { Store.setScore(m, it) }
        }
    }
}

/** A number typed and confirmed with the keyboard's Done (or by leaving the field empty and confirming). */
@Composable
private fun NumberField(label: String, value: Int, save: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value > 0) value.toString() else "") }
    OutlinedTextField(text, { text = it.filter(Char::isDigit).take(5) }, Modifier.width(170.dp), label = { Text(label, maxLines = 1) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save(text.toIntOrNull() ?: 0) }))
}

/** An episode: whether it's watched, and (tap or hold) marking it and the ones before watched, or it and the ones after unwatched. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(m: Media, p: Store.Progress?, n: Int) {
    val watched = p != null && (n < p.episode || isFinished(m, p))
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { menu = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(t("Episode {n}", "n" to n), Modifier.weight(1f), color = if (n > m.aired) Muted else TextColor)
            if (watched) Icon(Icons.check, null, Modifier.size(18.dp), tint = Accent)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(t("Mark watched up to episode {n}", "n" to n)) }, { Store.markWatched(m, n); menu = false })
            DropdownMenuItem({ Text(t("Mark unwatched from episode {n}", "n" to n)) }, { Store.markUnwatched(m, n); menu = false })
        }
    }
}

@Composable
private fun Shows(nav: Nav, m: Media, which: String) {
    val result by loading(m.id, which) { if (which == "Related") AniList.related(m) else AniList.similar(m.id) }
    val progress = remember(Store.version) { Store.progress(m.isManga) }
    Column(Modifier.padding(16.dp)) {
        when {
            result == null -> MutedText(t("Loading…"))
            result!!.isFailure -> ErrorText(result!!.exceptionOrNull()!!)
            result!!.getOrNull()!!.isEmpty() -> MutedText(
                if (m.isManga) t("Nothing here yet.") else if (which == "Related") t("No related anime found.") else t("No similar anime found."))
            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                result!!.getOrNull()!!.forEach { MediaCard(it, progress[it.id], nav::open, width = 108.dp) }
            }
        }
    }
}
