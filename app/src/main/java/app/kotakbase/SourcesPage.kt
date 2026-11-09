package app.kotakbase

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

private val RESOLUTIONS = listOf("2160", "1080", "720", "540", "480", "")
private val MANUAL = Regex("(?i)^(magnet:\\?|[0-9a-f]{40}$|https?://\\S+\\.torrent(\\?|$))")

/** What a source list search found: the arranged list, Auto Select's pick, how many were hidden, and the errors. */
private class Found(val results: List<Extensions.Source>, val auto: Int, val dead: Int)

/**
 * The source picker (desktop app.js sources()): every torrent extension is asked for this episode, the answers are
 * ranked like desktop's (your languages, the group used last time), and Auto Select takes the top one that can stream.
 */
@Composable
fun SourcesPage(nav: Nav, id: Int, start: Int) {
    val context = LocalContext.current
    val media by loading(id) { AniList.media(id) }
    val m = media?.getOrNull() ?: return Column(Modifier.padding(16.dp)) { media?.exceptionOrNull()?.let { ErrorText(it) } ?: MutedText(t("Loading…")) }
    var episode by rememberSaveable { mutableStateOf(start.coerceIn(1, m.epCount)) }
    var resolution by rememberSaveable { mutableStateOf(Store.string("resolution")?.trim('"') ?: "1080") }
    var filter by rememberSaveable { mutableStateOf("") }
    var raw by remember { mutableStateOf(emptyList<JSONObject>()) }
    var done by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var failed by remember { mutableStateOf<Throwable?>(null) }
    var ctx by remember { mutableStateOf<JSONObject?>(null) }
    var found by remember { mutableStateOf<Found?>(null) }
    val last = remember { Store.string("sources")?.let { runCatching { JSONObject(it).optJSONObject(id.toString())?.optString("group") }.getOrNull() } }

    LaunchedEffect(episode, resolution) {
        raw = emptyList(); done = false; errors = emptyList(); failed = null; found = null
        runCatching {
            coroutineScope {
                val ids = Shared.mappings(id, episode)
                val titles = Shared.call("api.titles(${lit(m.json.toString())})") as JSONArray
                val related = async { runCatching { AniList.related(m).flatMap { listOfNotNull(it.romaji, it.json.optJSONObject("title")?.optString("english")?.ifEmpty { null }) } }.getOrDefault(emptyList()) }
                val dub = Extensions.configs.let { c -> c.keys().asSequence().filter { c.getJSONObject(it).optString("media") == "dub" }.toList() }
                ctx = JSONObject().put("titles", titles).put("tvdbSeason", ids.opt("tvdbSeason")).put("prefs", Shared.prefs()).put("last", last).put("dub", JSONArray(dub))
                val query = JSONObject(ids.toString()).put("anilistId", id).put("media", m.json).put("titles", titles).put("episode", episode)
                    .put("episodeCount", m.epCount).put("resolution", resolution).put("exclusions", JSONArray())
                val single = m.epCount == 1
                val (results, errs) = Extensions.search(query, movie = m.format == "MOVIE" && !single, batch = !single && m.format != "MOVIE") { raw = it }
                ctx = JSONObject(ctx.toString()).put("related", JSONArray(related.await()))
                raw = results; errors = errs; done = true
                // brand-new shows often aren't mapped yet, and most extensions search by AniDB/TVDB ids, so say why
                if (results.isEmpty() && !ids.has("anidbAid") && !ids.has("tvdbId") && !ids.has("tmdbId"))
                    errors = listOf(t("This show isn't linked to AniDB/TVDB yet (common for brand-new releases), so most extensions can't search for it. Try again in a few days, or paste a magnet link above. Details:")) + errs
            }
        }.onFailure { failed = it; done = true }
    }
    LaunchedEffect(raw, ctx, done) {
        val c = ctx ?: return@LaunchedEffect
        val r = Shared.call("api.arrange(${lit(JSONArray(raw).toString())}, ${lit(JSONObject(c.toString()).put("done", done).toString())})") as JSONObject
        val list = r.getJSONArray("results")
        found = Found(List(list.length()) { Extensions.Source(list.getJSONObject(it)) }, r.optInt("auto", -1), r.optInt("dead"))
    }

    fun play(link: String, source: Extensions.Source?) {
        source?.group?.let { g -> Store.putString("sources", (Store.string("sources")?.let(::JSONObject) ?: JSONObject()).put(id.toString(), JSONObject().put("group", g)).toString()) }
        // shortcut: until the player (phase 3) the spike plays the torrent's largest file, not this episode's
        context.startActivity(Intent(context, SpikeActivity::class.java).putExtra("source", link))
    }

    val words = filter.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = found?.results?.withIndex()?.filter { (_, r) -> words.all { w -> r.title.lowercase().contains(w) } }.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(m.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    var typed by remember(episode) { mutableStateOf(episode.toString()) }
                    OutlinedTextField(typed, { v -> typed = v.filter(Char::isDigit).take(5); typed.toIntOrNull()?.takeIf { it in 1..m.epCount }?.let { episode = it } },
                        Modifier.width(120.dp), label = { Text(t("Episode")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    val auto = found?.takeIf { done && it.auto >= 0 }
                    Button({ auto?.let { play(it.results[it.auto].link, it.results[it.auto]) } }, Modifier.weight(1f), enabled = auto != null) {
                        Text(if (!done) t("Searching…") else if (auto != null) t("Auto Select Torrent") else t("No sources"))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MutedText(t("Resolution"))
                    RESOLUTIONS.forEach { r ->
                        FilterChip(resolution == r, { resolution = r; Store.putString("resolution", JSONObject.quote(r)) }, { Text(if (r.isEmpty()) t("Any") else "${r}p") })
                    }
                }
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(t("Filter by text, or paste a magnet link or torrent URL here to specify a torrent manually"), maxLines = 1) })
                errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                failed?.let { ErrorText(it) }
            }
        }
        if (MANUAL.containsMatchIn(filter.trim())) item {
            SourceRow(t("Play this torrent"), filter.trim(), t("Press Enter or click to stream it as episode {n}", "n" to episode)) { play(filter.trim(), null) }
        } else {
            itemsIndexed(shown, key = { _, (i, r) -> r.hash + i }) { _, (i, r) ->
                val meta = listOfNotNull(
                    t("{n} seeders", "n" to r.seeders), size(r.size), r.group?.takeIf { it.isNotEmpty() },
                    (r.audio.map { "🔊$it" } + r.subs.map { "💬$it" }).joinToString(" ").ifEmpty { null },
                    r.tags.joinToString(" · ").ifEmpty { null }, if (r.type == "batch") t("Batch") else null, if (i == found?.auto) "★" else null
                ).joinToString("  ·  ")
                SourceRow(null, r.title, meta) { play(r.link, r) }
            }
            if (shown.isEmpty()) item {
                MutedText(when {
                    found?.results?.isNotEmpty() == true -> t("No sources match the filter.")
                    !done -> t("Searching extensions…")
                    (found?.dead ?: 0) > 0 -> t("No sources with seeders found ({n} with none hidden). Try another resolution or episode.", "n" to found!!.dead)
                    else -> t("No sources found.")
                })
            }
        }
    }
}

@Composable
private fun SourceRow(head: String?, name: String, meta: String, pick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).clickable(onClick = pick).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        head?.let { Text(it, fontWeight = FontWeight.Bold) }
        Text(name, style = MaterialTheme.typography.bodyMedium)
        MutedText(meta)
    }
}

private fun size(bytes: Long) = when {
    bytes <= 0 -> null
    bytes >= 1L shl 30 -> "%.1f GiB".format(bytes / (1L shl 30).toDouble())
    else -> "%.0f MiB".format(bytes / (1L shl 20).toDouble())
}
