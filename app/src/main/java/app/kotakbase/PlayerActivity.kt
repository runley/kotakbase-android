package app.kotakbase

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Asks the main screen to open a show's source picker (the player's Previous/Next when the episode isn't in its torrent). */
object PlayerRequests { var sources by mutableStateOf<Page.Sources?>(null) }

/**
 * The player: an episode streamed from its torrent into libmpv, with progress saved as on desktop, Skip Intro/Credits,
 * the next episode straight from a season pack, audio and subtitle tracks by Settings' languages, picture-in-picture
 * and, half-folded, the video on the top half and the controls on the bottom one.
 */
class PlayerActivity : ComponentActivity() {
    class Play(val id: Int, val episode: Int, val link: String, val fileIndex: Int = -1)
    var play by mutableStateOf<Play?>(null)
    var pip by mutableStateOf(false)
    lateinit var mpv: Mpv

    companion object {
        fun start(context: Context, id: Int, episode: Int, link: String, fileIndex: Int = -1) = context.startActivity(
            Intent(context, PlayerActivity::class.java).putExtra("id", id).putExtra("episode", episode).putExtra("link", link).putExtra("file", fileIndex)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) // its own task (picture-in-picture); also from Watch Together, outside an activity
    }

    private fun read(i: Intent) = Play(i.getIntExtra("id", 0), i.getIntExtra("episode", 1), i.getStringExtra("link").orEmpty(), i.getIntExtra("file", -1))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); I18n.init(this); Shared.init(this); Extensions.init(this); Torrent.init(this); Auth.init(this); Downloads.init(this); Room.init(this)
        mpv = Mpv(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        play = read(intent)
        setContent { androidx.compose.material3.MaterialTheme(colorScheme = colors) { PlayerScreen(this) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); play = read(intent) }

    // in the background there's nothing to show, so nothing plays; picture-in-picture keeps playing
    override fun onStop() { super.onStop(); if (!isInPictureInPictureMode) mpv.pause(true) }

    override fun onDestroy() {
        mpv.destroy()
        if (isFinishing) CoroutineScope(Dispatchers.IO).launch { Torrent.stop() }
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(isIn: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isIn, newConfig)
        pip = isIn
    }

    // before Android 12 picture-in-picture is entered by hand when leaving; from 12 on, automatically (pipParams)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !mpv.paused) runCatching { enterPictureInPictureMode(pipParams(true)) }
    }

    fun pipParams(playing: Boolean): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational((mpv.aspect.coerceIn(0.42, 2.39) * 1000).toInt(), 1000))
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(playing) }
        .build()

    fun enterPip() = runCatching { enterPictureInPictureMode(pipParams(true)) }

    /** Episode `n` isn't in this torrent: back to the app, on its source picker. */
    fun openSources(id: Int, n: Int) {
        PlayerRequests.sources = Page.Sources(id, n)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }
}

/** An opening or ending to offer skipping. */
private class Skip(val kind: String, val start: Double, val end: Double)

// chapter names releases use for the opening and the ending/credits (desktop chapters.js)
private val OP = Regex("(?i)\\b(op|opening|intro)\\b")
private val ED = Regex("(?i)\\b(ed|ending|credits|outro)\\b")

private fun clock(s: Double): String {
    val t = s.toLong().coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

@Composable
fun PlayerScreen(a: PlayerActivity) {
    val play = a.play ?: return
    val mpv = a.mpv
    val media by loading(play.id) { AniList.media(play.id) }
    val m = media?.getOrNull()
    var episode by remember(play) { mutableIntStateOf(play.episode) }
    var fileIndex by remember(play) { mutableIntStateOf(play.fileIndex) }
    var stream by remember(play) { mutableStateOf<Torrent.Loaded?>(null) }
    var local by remember(play) { mutableStateOf<String?>(null) } // a downloaded episode's file, playing instead of a stream
    var numbering by remember(play) { mutableStateOf(JSONObject()) }
    var next by remember(play) { mutableStateOf<Int?>(null) } // the next episode's file in this torrent
    var error by remember(play) { mutableStateOf<String?>(null) }
    var skips by remember(play, episode) { mutableStateOf(emptyList<Skip>()) }
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }

    // start (or move to) the episode: load the torrent, pick the episode's file, resume where you left off
    LaunchedEffect(play, episode, m != null) {
        if (m == null) return@LaunchedEffect
        error = null
        local = null
        // a downloaded episode: straight from the phone (this one, or the next one Next moved to)
        (if (episode == play.episode && play.link.startsWith("/")) play.link else Downloads.local(m.id, episode))?.let { path ->
            local = path
            // in a room as host: the others stream the torrent this download came from
            Downloads.source(m.id, episode)?.let { (link, index) -> Room.played(m.id, episode, link, m.title, index, JSONObject()) }
            val prev = Store.progress(false)[m.id]
            mpv.load(path, if (prev?.episode == episode && prev.time < prev.duration * 0.9) prev.time else 0.0)
            return@LaunchedEffect
        }
        runCatching {
            val t = Torrent.open(play.link)
            when (Shared.call("api.refuse(${lit(JSONArray(t.files).toString())})") as? String) {
                "risky" -> throw Exception(t("This torrent contains program files, a common sign of a fake or malware, so it won't be played. Pick another source."))
                "novideo" -> throw Exception(t("There's no video in this torrent. Pick another source."))
            }
            val ids = Shared.mappings(m.id, episode)
            numbering = JSONObject().put("abs", ids.opt("absoluteEpisodeNumber")).put("tvdbSeason", ids.opt("tvdbSeason")).put("tvdbSeasonEpisode", ids.opt("tvdbSeasonEpisode"))
            if (fileIndex < 0) fileIndex = (Shared.call("api.fileFor(${lit(JSONArray(t.files).toString())}, $episode, ${lit(numbering.toString())})") as Number).toInt()
            stream = t
            // in a Watch Together room as host, this is what everyone watches now (shared by info hash, not .torrent links)
            Room.played(m.id, episode, if (play.link.startsWith("http")) t.hash else play.link, m.title, fileIndex, numbering)
            val prev = Store.progress(false)[m.id]
            mpv.load(Torrent.play(t, fileIndex), if (prev?.episode == episode && prev.time < prev.duration * 0.9) prev.time else 0.0)
            // is the next episode in this torrent (a season pack)? Then Next plays it from here
            next = if (episode < m.epCount) {
                val nids = Shared.mappings(m.id, episode + 1)
                val nb = JSONObject().put("abs", nids.opt("absoluteEpisodeNumber")).put("tvdbSeason", nids.opt("tvdbSeason")).put("tvdbSeasonEpisode", nids.opt("tvdbSeasonEpisode"))
                (Shared.call("api.nextFile(${lit(JSONArray(t.files).toString())}, ${episode + 1}, ${lit(nb.toString())}, $fileIndex)") as Number).toInt().takeIf { it >= 0 }
            } else null
        }.onFailure { error = it.message ?: it.toString() }
    }

    // audio and subtitles by Settings' language lists, once the file's tracks are known
    LaunchedEffect(mpv.loaded) {
        if (mpv.loaded == 0) return@LaunchedEffect
        runCatching {
            val pick = Shared.call("api.tracks(${lit(mpv.tracks.toString())}, ${lit(JSONArray(Shared.langs("audioLangs")).toString())}, ${lit(JSONArray(Shared.langs("subLangs")).toString())})") as JSONObject
            if (!pick.isNull("aid")) mpv.setTrack("aid", pick.getInt("aid"))
            mpv.setTrack("sid", if (pick.isNull("sid")) null else pick.getInt("sid"))
        }
    }

    // the file's own opening/ending chapters, else AniSkip's times by MyAnimeList id
    LaunchedEffect(mpv.chapters, mpv.duration > 0, episode) {
        val d = mpv.duration.takeIf { it > 0 } ?: return@LaunchedEffect
        val ch = mpv.chapters
        val own = (0 until ch.length()).map { ch.getJSONObject(it) }.mapIndexedNotNull { i, c ->
            val kind = c.optString("title").let { t -> if (OP.containsMatchIn(t)) "op" else if (ED.containsMatchIn(t)) "ed" else null } ?: return@mapIndexedNotNull null
            Skip(kind, c.optDouble("time"), if (i + 1 < ch.length()) ch.getJSONObject(i + 1).optDouble("time") else d)
        }
        skips = own.ifEmpty {
            val mal = m?.json?.optInt("idMal")?.takeIf { it > 0 } ?: return@LaunchedEffect
            runCatching {
                val body = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    AniList.http.newCall(Request.Builder().url("https://api.aniskip.com/v2/skip-times/$mal/$episode?types=op&types=ed&episodeLength=${d.toLong()}").build()).execute().use { it.body?.string().orEmpty() }
                }
                val r = JSONObject(body).optJSONArray("results") ?: JSONArray()
                (0 until r.length()).map { r.getJSONObject(it) }.map { Skip(it.optString("skipType"), it.getJSONObject("interval").optDouble("startTime"), it.getJSONObject("interval").optDouble("endTime")) }.sortedBy { it.start }
            }.getOrDefault(emptyList())
        }
    }

    // progress every few seconds (and the next episode's file starts downloading near the end)
    LaunchedEffect(play, episode, stream, local) {
        val t = stream
        if (t == null && local == null) return@LaunchedEffect
        var logged = false
        var prefetched = false
        while (true) {
            delay(3000)
            val d = mpv.duration
            if (m == null || d <= 0 || mpv.time <= 0) continue
            val credits = skips.firstOrNull { it.kind == "ed" && it.start > d * 0.75 }?.start
            val watched = Store.saveProgress(m, episode, mpv.time, d, credits)
            if (watched && !logged) { logged = true; Store.logWatched(m, episode) }
            if (t != null && !prefetched && next != null && (watched || mpv.time > d * 0.85)) { prefetched = true; Torrent.play(t, fileIndex, also = next) }
        }
    }

    fun goNext() {
        val mm = m ?: return
        if (episode >= mm.epCount) return a.finish()
        val n = next
        // from the same season pack, or the next episode's download; else its source picker
        if (n != null) { fileIndex = n; next = null; episode++ }
        else if (Downloads.local(mm.id, episode + 1) != null) episode++
        else a.openSources(mm.id, episode + 1)
    }
    LaunchedEffect(mpv.ended) { if (mpv.ended && m != null && mpv.duration > 0) { Store.saveProgress(m, episode, mpv.duration, mpv.duration, null); goNext() } }

    // Watch Together: the room sees this player, and drives it when someone else is the host
    DisposableEffect(Unit) { Room.mpv = mpv; onDispose { if (Room.mpv === mpv) Room.mpv = null; Room.push(false, 0.0, 0.0, true, false) } }
    LaunchedEffect(Room.active) { while (Room.active) { Room.push(true, mpv.duration, mpv.time, mpv.paused, mpv.buffering || mpv.duration <= 0); delay(200) } }

    // the controls hide after a few seconds of playing untouched
    LaunchedEffect(controls, touched, mpv.paused) { if (controls && !mpv.paused) { delay(3500); controls = false } }
    LaunchedEffect(mpv.paused, mpv.aspect) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) a.setPictureInPictureParams(a.pipParams(!mpv.paused)) }
    BackHandler { a.finish() }

    val layout by remember { WindowInfoTracker.getOrCreate(a).windowLayoutInfo(a) }.collectAsState(initial = null)
    val tabletop = layout?.displayFeatures?.filterIsInstance<FoldingFeature>()
        ?.any { it.state == FoldingFeature.State.HALF_OPENED && it.orientation == FoldingFeature.Orientation.HORIZONTAL } == true

    val video = @Composable { modifier: Modifier ->
        Box(modifier.background(Color.Black)) {
            AndroidView({ SurfaceView(it).apply { holder.addCallback(mpv) } }, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controls = !controls; touched++ },
                    onDoubleTap = { o -> mpv.seekBy(if (o.x < size.width / 2) -10.0 else 10.0); touched++ }
                )
            })
            if ((mpv.buffering || mpv.duration <= 0) && error == null) Text(if (stream == null && local == null) t("Loading torrent…") else t("Buffering…") + "  " + Torrent.status(),
                Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(12.dp), color = TextColor)
        }
    }
    val show = m != null && !a.pip
    if (tabletop) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            video(Modifier.weight(1f).fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth().safeDrawingPadding()) {
                if (show) Controls(a, m!!, episode, error, skips, onNext = ::goNext, onPrev = { a.openSources(m.id, episode - 1) }, touch = { touched++ })
            }
        }
    } else Box(Modifier.fillMaxSize().background(Color.Black)) {
        video(Modifier.fillMaxSize())
        if (show && (controls || error != null || mpv.paused)) Box(Modifier.fillMaxSize().background(Color(0x66000000)).safeDrawingPadding()) {
            Controls(a, m!!, episode, error, skips, onNext = ::goNext, onPrev = { a.openSources(m.id, episode - 1) }, touch = { touched++ })
        }
        else skips.firstOrNull { mpv.time >= it.start && mpv.time < it.end - 1 }?.let { s -> SkipButton(a, s, m, episode, ::goNext, Modifier.align(Alignment.BottomEnd).padding(24.dp)) }
    }
}

@Composable
private fun SkipButton(a: PlayerActivity, s: Skip, m: Media?, episode: Int, next: () -> Unit, modifier: Modifier = Modifier) {
    val mpv = a.mpv
    // credits that run to the end: Next Episode instead
    val toNext = s.kind == "ed" && s.end >= mpv.duration - 5 && m != null && episode < m.epCount
    Button({ if (toNext) next() else mpv.seek(s.end) }, modifier) { Text(if (toNext) t("Next Episode") else if (s.kind == "op") t("Skip Intro") else t("Skip Credits")) }
}

@Composable
private fun BoxScope.Controls(a: PlayerActivity, m: Media, episode: Int, error: String?, skips: List<Skip>, onNext: () -> Unit, onPrev: () -> Unit, touch: () -> Unit) {
    val mpv = a.mpv
    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ a.finish() }) { Icon(Icons.close, t("Close"), tint = TextColor) }
        Column(Modifier.weight(1f)) {
            Text(m.title, fontWeight = FontWeight.Bold, color = TextColor, maxLines = 1)
            Text(t("Episode {n} / {total}", "n" to episode, "total" to m.epCount), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        TextButton({ a.enterPip() }) { Text(t("Picture in Picture"), color = TextColor) }
    }
    error?.let { Text(it, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error) }
    if (error == null) Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        val big = Modifier.size(64.dp)
        if (episode > 1) IconButton(onPrev, big) { Icon(Icons.prev, null, Modifier.size(34.dp), tint = TextColor) }
        IconButton({ mpv.pause(!mpv.paused); touch() }, big) { Icon(if (mpv.paused) Icons.play else Icons.pause, null, Modifier.size(46.dp), tint = TextColor) }
        if (episode < m.epCount) IconButton(onNext, big) { Icon(Icons.next, null, Modifier.size(34.dp), tint = TextColor) }
    }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        skips.firstOrNull { mpv.time >= it.start && mpv.time < it.end - 1 }?.let { SkipButton(a, it, m, episode, onNext, Modifier.align(Alignment.End)) }
        var dragging by remember { mutableStateOf<Float?>(null) }
        val d = mpv.duration.coerceAtLeast(1.0)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(dragging?.times(d) ?: mpv.time), color = TextColor, style = MaterialTheme.typography.bodySmall)
            Slider(dragging ?: (mpv.time / d).toFloat().coerceIn(0f, 1f), { dragging = it; touch() }, Modifier.weight(1f).padding(horizontal = 8.dp),
                onValueChangeFinished = { dragging?.let { mpv.seek(it * d) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            Text(clock(mpv.duration), color = TextColor, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrackMenu(a, "audio", t("Audio"), touch)
            TrackMenu(a, "sub", t("Subtitles"), touch)
        }
    }
}

/** Audio or subtitle tracks of the file (mpv's track-list), with Off for subtitles. */
@Composable
private fun TrackMenu(a: PlayerActivity, type: String, label: String, touch: () -> Unit) {
    val mpv = a.mpv
    var open by remember { mutableStateOf(false) }
    val list = (0 until mpv.tracks.length()).map { mpv.tracks.getJSONObject(it) }.filter { it.optString("type") == type }
    if (list.isEmpty()) return
    val prop = if (type == "audio") "aid" else "sid"
    Box {
        TextButton({ open = true; touch() }) { Text(label, color = TextColor) }
        DropdownMenu(open, { open = false }) {
            if (type == "sub") DropdownMenuItem({ Text(t("Off")) }, { mpv.setTrack(prop, null); open = false })
            list.forEach { tr ->
                val name = listOf(tr.optString("title"), tr.optString("lang")).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { t("Track {n}", "n" to tr.optInt("id")) }
                DropdownMenuItem({ Text((if (tr.optBoolean("selected")) "✓ " else "") + name) }, { mpv.setTrack(prop, tr.optInt("id")); open = false })
            }
        }
    }
}
