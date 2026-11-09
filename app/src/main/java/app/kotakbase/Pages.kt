package app.kotakbase

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate

private fun continueIds() = Store.progress().entries.sortedByDescending { it.value.at }.map { it.key }

/** The More page's lists (and the home rows' "View More"): title and up to 100 shows. */
private fun more(kind: String): Pair<String, suspend () -> List<Media>> = when (kind) {
    "continue" -> (if (Store.manga) "Continue Reading" else "Continue Watching") to suspend {
        val p = Store.progress()
        AniList.byIds(continueIds()).filter { inContinue(it, p[it.id]) }
    }
    "list" -> "Your List" to suspend { AniList.byIds(Store.myList()) }
    "season" -> popularThis() to AniList.seasonal(100)
    "popular" -> "All Time Popular" to AniList.popular(100)
    "upcoming" -> "Fuel the Hype" to AniList.upcoming(100)
    else -> "Trending Now" to AniList.trending(100)
}

@Composable
fun HomePage(nav: Nav) {
    val local = Store.version // your own rows redraw when your list or progress changes
    // manga has no seasons, so no seasonal rows
    val rows = if (Store.manga) listOf("continue", "list", "trending", "popular")
    else listOf("continue", "season", "list", "trending", "popular", "upcoming")
    // loaded here, not inside the list: a row scrolled out of view would forget its cards, and coming back to it
    // would show "Loading…" (a shorter row) and fetch again, making the page jump
    val loaded = rows.associateWith { kind ->
        androidx.compose.runtime.key(kind) {
            val (name, fetch) = more(kind)
            val rowFetch: suspend () -> List<Media> = when (kind) {
                "continue", "list" -> suspend { fetch().take(AniList.ROW_LIMIT) }
                "season" -> AniList.seasonal()
                "popular" -> AniList.popular()
                "upcoming" -> AniList.upcoming()
                else -> AniList.trending()
            }
            val mine = kind == "continue" || kind == "list"
            name to loading(*(if (mine) arrayOf<Any>(Store.manga, local) else arrayOf<Any>(Store.manga))) { rowFetch() }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(rows, key = { it }) { kind ->
            val (name, result) = loaded.getValue(kind)
            HomeRow(kind, t(name), nav, result.value)
        }
    }
}

/** A home row: its title, "View More", and its cards; one that turns out empty isn't shown. */
@Composable
private fun HomeRow(kind: String, name: String, nav: Nav, result: Result<List<Media>>?) {
    val list = result?.getOrNull()
    if (result != null && list?.isEmpty() == true) return
    Column(Modifier.padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (list != null) TextButton({ nav.go(Page.More(kind)) }) { Text(t("View More")) }
        }
        when {
            result == null -> MutedText(t("Loading…"), Modifier.padding(16.dp))
            list == null -> Box(Modifier.padding(16.dp)) { ErrorText(result!!.exceptionOrNull()!!) }
            else -> {
                val progress = remember(Store.version, Store.manga) { Store.progress() }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.id }) { MediaCard(it, progress[it.id], nav::open) }
                }
            }
        }
    }
}

@Composable
private fun MediaGrid(list: List<Media>, nav: Nav, header: @Composable () -> Unit) {
    val progress = remember(Store.version, Store.manga) { Store.progress() }
    LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(list, key = { it.id }) { MediaCard(it, progress[it.id], nav::open, width = null) }
    }
}

@Composable
fun MorePage(nav: Nav, kind: String) {
    val (name, fetch) = more(kind)
    val keys = if (kind == "continue" || kind == "list") listOf<Any>(Store.manga, Store.version) else listOf<Any>(Store.manga)
    val result by loading(*keys.toTypedArray()) { fetch() }
    MediaGrid(result?.getOrNull().orEmpty(), nav) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t(name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            when {
                result == null -> MutedText(t("Loading…"))
                result!!.isFailure -> ErrorText(result!!.exceptionOrNull()!!)
                result!!.getOrNull()!!.isEmpty() -> MutedText(t("Nothing here yet."))
            }
        }
    }
}

private val GENRES = listOf("Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music", "Mystery",
    "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller")

/** Search filters: key, label, options as value → label (labels translated, except years). */
private fun filters(): List<Triple<String, String, List<Pair<String, String>>>> {
    val year = LocalDate.now().year
    return listOfNotNull(
        Triple("genre", "Genre", GENRES.map { it to t(it) }),
        Triple("year", "Year", (year + 1 downTo 1960).map { "$it%" to "$it" }),
        if (Store.manga) null else Triple("season", "Season", SEASON.map { (k, v) -> k to t(v) }),
        Triple("format", "Format", (if (Store.manga) MANGA_FORMAT else FORMAT).map { (k, v) -> k to t(v) }),
        Triple("status", "Status", listOf("RELEASING" to t("Airing"), "FINISHED" to t("Finished"), "NOT_YET_RELEASED" to t("Not yet aired"))),
        Triple("sort", "Sort", listOf("POPULARITY_DESC" to "Popularity", "SCORE_DESC" to "Score", "TRENDING_DESC" to "Trending", "START_DATE_DESC" to "Newest", "TITLE_ROMAJI" to "Title").map { (k, v) -> k to t(v) })
    )
}

@Composable
fun SearchPage(nav: Nav, start: Page.Search) {
    var q by rememberSaveable { mutableStateOf("") }
    val picked = remember { mutableStateMapOf<String, String>().apply { start.genre?.let { put("genre", it) }; start.tag?.let { put("tag", it) } } }
    var typed by remember { mutableStateOf(false) }
    val snapshot = picked.toMap()
    val result by loading(q, snapshot, Store.manga) {
        if (typed) delay(350) // wait for typing to pause
        AniList.search(q, snapshot)
    }
    MediaGrid(result?.getOrNull().orEmpty(), nav) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(q, { q = it; typed = true }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(if (Store.manga) t("Search manga…") else t("Search anime…")) })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                picked["tag"]?.let { tag -> FilterChip(true, { picked.remove("tag"); typed = false }, { Text(t("Tag: {tag}", "tag" to tag) + " ✕") }) }
                for ((key, label, options) in filters()) FilterMenu(t(label), if (key == "sort") t("Best match") else t("Any"), options, picked[key]) {
                    if (it == null) picked.remove(key) else picked[key] = it
                    typed = false
                }
            }
            when {
                result == null -> MutedText(t("Loading…"))
                result!!.isFailure -> ErrorText(result!!.exceptionOrNull()!!)
                result!!.getOrNull()!!.isEmpty() -> MutedText(if (picked.isEmpty()) t("Nothing found.") else t("Nothing found with these filters."))
            }
        }
    }
}

@Composable
private fun FilterMenu(label: String, none: String, options: List<Pair<String, String>>, value: String?, pick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(value != null, { open = true }, { Text(options.firstOrNull { it.first == value }?.second ?: label) })
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(none) }, { pick(null); open = false })
            for ((v, l) in options) DropdownMenuItem({ Text(l) }, { pick(v); open = false })
        }
    }
}

@Composable
fun SettingsPage() {
    val saved = Store.language.also { Store.version }
    val system = I18n.LANGUAGES.getValue(I18n.pick(null, android.os.LocaleList.getDefault().let { ls -> List(ls.size()) { ls[it] } }))
    val choices = listOf<String?>(null) + I18n.LANGUAGES.keys
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { Text(t("Settings"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { Text(t("Language"), Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium) }
        items(choices) { code ->
            Row(
                Modifier.fillMaxWidth().selectable(saved == code) {
                    Store.language = code
                    I18n.set(I18n.pick(code, android.os.LocaleList.getDefault().let { ls -> List(ls.size()) { ls[it] } }))
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(saved == code, null)
                Text(if (code == null) t("System ({language})", "language" to system) else I18n.LANGUAGES.getValue(code), Modifier.padding(start = 8.dp))
            }
        }
        item { AniListSection() }
        item { ExtensionsSection() }
        item { UpdatesSection() }
    }
}

/** Settings → Updates (desktop wording): the version, Check for updates, and installing a downloaded one. */
@Composable
fun UpdatesSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.padding(top = 16.dp)) {
        Text(t("Updates"), style = MaterialTheme.typography.titleMedium)
        Text(t("You have version {version}.", "version" to BuildConfig.VERSION_NAME), Modifier.padding(vertical = 4.dp))
        when (Updates.status) {
            "checking" -> Text(t("Checking for updates…"))
            "downloading" -> Text(t("Downloading version {version}…", "version" to Updates.version))
            "latest" -> Text(t("You have the latest version."))
            "error" -> Text(t("Couldn't check for updates: {error}", "error" to Updates.error), color = MaterialTheme.colorScheme.error)
            "ready" -> Text(t("Version {version} is downloaded.", "version" to Updates.version))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (Updates.status == "ready") androidx.compose.material3.Button({ Updates.install(context) }) { Text(t("Restart to update")) }
            androidx.compose.material3.OutlinedButton({ Updates.check() }, enabled = Updates.status != "checking" && Updates.status != "downloading") { Text(t("Check for updates")) }
        }
    }
}

/** A newer version finished downloading (desktop's "ready" prompt): install now, or later from Settings. */
@Composable
fun UpdateReadyDialog() {
    if (Updates.status != "ready" || Updates.dismissed) return
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { Updates.dismissed = true },
        title = { Text(t("Kotakbase {version} is ready", "version" to Updates.version)) },
        confirmButton = { TextButton({ Updates.dismissed = true; Updates.install(context) }) { Text(t("Restart to update")) } },
        dismissButton = { TextButton({ Updates.dismissed = true }) { Text(t("Cancel")) } },
    )
}
