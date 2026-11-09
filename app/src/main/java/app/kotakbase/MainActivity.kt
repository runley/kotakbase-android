package app.kotakbase

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface Page {
    data object Home : Page
    data class Search(val genre: String? = null, val tag: String? = null) : Page
    data object MyList : Page
    data object Settings : Page
    data class More(val kind: String) : Page
    data class Show(val id: Int) : Page
    data class Sources(val id: Int, val episode: Int) : Page
}

/**
 * Where you are: a stack of pages, plus (unfolded) the show open beside them. Folded, a show opens as its own page;
 * unfolded, beside the page you opened it from, so browsing and reading about a show happen side by side.
 */
class Nav {
    val stack = mutableStateListOf<Page>(Page.Home)
    var side by mutableStateOf<Int?>(null)
    var wide = false

    val top get() = stack.last()
    fun open(id: Int) { if (wide) side = id else stack += Page.Show(id) }
    fun go(page: Page) { if (page is Page.Show) open(page.id) else stack += page }
    fun tab(page: Page) { stack.clear(); stack += page; side = null }
    fun back() = if (wide && side != null) side = null else stack.removeAt(stack.lastIndex).let { }
    val canGoBack get() = stack.size > 1 || (wide && side != null)

    /** Folding moves an open show from beside the page onto its own page, and unfolding moves it back. */
    fun resize(to: Boolean) {
        wide = to
        if (!to) side?.let { stack += Page.Show(it); side = null }
        else (top as? Page.Show)?.let { if (stack.size > 1) { stack.removeAt(stack.lastIndex); side = it.id } }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        I18n.init(this)
        Shared.init(this)
        Extensions.init(this)
        Torrent.init(this)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = colors) { Surface(Modifier.fillMaxSize(), color = Bg) { App() } } }
    }
}

private val TABS = listOf(Page.Home, Page.Search(), Page.MyList, Page.Settings)

@Composable
fun App() {
    val nav = remember { Nav() }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    LaunchedEffect(wide) { nav.resize(wide) }
    nav.wide = wide
    BackHandler(nav.canGoBack) { nav.back() }
    // the player's Previous/Next for an episode that isn't in its torrent: that episode's source picker
    LaunchedEffect(PlayerRequests.sources) {
        val p = PlayerRequests.sources ?: return@LaunchedEffect
        if (nav.top is Page.Sources) nav.stack[nav.stack.lastIndex] = p else nav.go(p)
        PlayerRequests.sources = null
    }
    val holder = rememberSaveableStateHolder() // keeps each page's scroll position while it's under another

    @Composable
    fun page(p: Page, key: String) = holder.SaveableStateProvider(key) {
        when (p) {
            Page.Home -> HomePage(nav)
            is Page.Search -> SearchPage(nav, p)
            Page.MyList -> MorePage(nav, "list")
            Page.Settings -> SettingsPage()
            is Page.More -> MorePage(nav, p.kind)
            is Page.Show -> ShowPage(nav, p.id)
            is Page.Sources -> SourcesPage(nav, p.id, p.episode)
        }
    }

    val tabLabels = listOf(t("Home"), if (Store.manga) t("Search manga") else t("Search anime"), t("Your List"), t("Settings"))
    val tabIcons = listOf(Icons.home, Icons.search, Icons.bookmark, Icons.settings)
    val current = TABS.indexOfFirst { it::class == nav.stack.first()::class }

    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (wide) NavigationRail(containerColor = Bg, header = { ModeSwitch(nav, compact = true) }) {
            TABS.forEachIndexed { i, tab ->
                NavigationRailItem(i == current, { nav.tab(tab) }, { Icon(tabIcons[i], tabLabels[i]) }, label = { Text(tabLabels[i]) })
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (!wide) ModeSwitch(nav, compact = false)
            Row(Modifier.weight(1f)) {
                // unfolded: the page takes the whole width until a show is opened beside it
                val side = nav.side.takeIf { wide }
                Box(Modifier.weight(if (side != null) 0.45f else 1f).fillMaxHeight()) { page(nav.top, "${nav.stack.size}:${nav.top}") }
                if (side != null) {
                    VerticalDivider(color = Line)
                    Box(Modifier.weight(0.55f).fillMaxHeight()) { page(Page.Show(side), "side:$side") }
                }
            }
            if (!wide) NavigationBar(containerColor = Panel) {
                TABS.forEachIndexed { i, tab ->
                    NavigationBarItem(i == current, { nav.tab(tab) }, { Icon(tabIcons[i], tabLabels[i]) }, label = { Text(tabLabels[i], maxLines = 1) })
                }
            }
        }
    }
}

/** The logo box switches anime ⇄ manga, as on desktop. */
@Composable
fun ModeSwitch(nav: Nav, compact: Boolean) {
    val label = if (Store.manga) t("Switch to anime") else t("Switch to manga")
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(8.dp))
            .clickable { Store.setMode(!Store.manga); nav.tab(Page.Home) }.semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(Accent), contentAlignment = Alignment.Center) {
            Text(if (Store.manga) "m" else "k", color = Bg, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        if (!compact) Text((if (Store.manga) "mangal" else "kotak") + "base", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}
