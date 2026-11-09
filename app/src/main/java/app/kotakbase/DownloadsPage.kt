package app.kotakbase

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.json.JSONObject
import java.io.File

/** Downloads (desktop downloadsPage): each torrent's episodes with their progress; play, pause, resume, delete. */
@Composable
fun DownloadsPage(nav: Nav) {
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<Pair<JSONObject, JSONObject?>?>(null) } // (download, one episode's file or all)
    val list = Downloads.items
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(t("Downloads"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            MutedText(t("Episodes you download play from your computer, without internet."))
            if (list.isEmpty()) MutedText(t("Nothing downloaded yet. On a show's page, use the download button next to Watch for a whole season, or the one on an episode for just that episode."), Modifier.padding(top = 12.dp))
        }
        items(list, key = { it.getString("id") }) { d ->
            val id = d.getString("id")
            val files = d.getJSONArray("files").let { a -> List(a.length()) { a.getJSONObject(it) } }
            val status = d.optString("status")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(d.optString("cover"), null, Modifier.width(56.dp).aspectRatio(230f / 325f).clip(RoundedCornerShape(6.dp)).clickable { nav.open(d.optInt("mediaId")) }, contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f)) {
                        Text(d.optString("title"), fontWeight = FontWeight.Bold)
                        val left = files.count { it.optLong("have") < it.optLong("length") }
                        MutedText(listOfNotNull(
                            when (status) {
                                "done" -> t("Downloaded"); "paused" -> t("Paused"); "waiting" -> t("Starts when you close the player")
                                "error" -> t("Stopped: {error}", "error" to d.optString("error")); else -> t("Downloading")
                            },
                            t("{n} episodes", "n" to files.size),
                            if (status == "active" && left > 0) t("{n} to go", "n" to left) else null,
                            Downloads.rate(id)?.takeIf { status == "active" }?.let { (bps, peers) -> "${bps / 1024} KB/s · ${t("{n} peers", "n" to peers)}" }
                        ).joinToString(" · "))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (status == "active") TextButton({ Downloads.pause(id) }) { Text(t("Pause")) }
                    if (status == "paused" || status == "error") TextButton({ Downloads.resume(id) }) { Text(t("Resume")) }
                    TextButton({ deleting = d to null }) { Text(t("Delete")) }
                }
                files.forEach { f ->
                    val have = f.optLong("have")
                    val length = f.optLong("length").coerceAtLeast(1)
                    val done = have >= length
                    val ep = f.opt("episode") as? Int
                    Row(Modifier.fillMaxWidth().clickable(enabled = done) {
                        PlayerActivity.start(context, d.optInt("mediaId"), ep ?: 1, File(Downloads.dir, f.getString("path")).path)
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if (done && ep != null) t("Play episode {n}", "n" to ep) else ep?.let { t("Episode {n}", "n" to it) } ?: f.optString("name"), maxLines = 1)
                            if (!done) {
                                LinearProgressIndicator({ (have.toDouble() / length).toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp), color = Accent)
                                MutedText(t("{have} of {total}", "have" to mb(have), "total" to mb(length)))
                            }
                        }
                        TextButton({ deleting = d to f }) { Text(t("Delete this episode")) }
                    }
                }
            }
        }
    }
    deleting?.let { (d, f) ->
        AlertDialog({ deleting = null },
            confirmButton = { TextButton({ Downloads.remove(d.getString("id"), f?.getInt("index")); deleting = null }) { Text(t("Delete")) } },
            dismissButton = { TextButton({ deleting = null }) { Text(t("Cancel")) } },
            title = { Text(t("Delete download?")) },
            text = {
                Text(if (f != null) t("The video file for {what} will be deleted from your computer.", "what" to ((f.opt("episode") as? Int)?.let { t("episode {n}", "n" to it) } ?: f.optString("name")))
                else t("The video files of {title} ({episodes}) will be deleted from your computer.", "title" to d.optString("title"), "episodes" to t("{n} episodes", "n" to d.getJSONArray("files").length())))
            })
    }
}

private fun mb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GiB".format(bytes / (1L shl 30).toDouble()) else "%.0f MiB".format(bytes / (1L shl 20).toDouble())
