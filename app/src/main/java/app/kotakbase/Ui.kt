package app.kotakbase

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

// The desktop app's colours (ui/style.css :root)
val Bg = Color(0xFF0F0F10)
val Panel = Color(0xFF1A1A1C)
val Panel2 = Color(0xFF232326)
val Line = Color(0xFF2A2A2D)
val TextColor = Color(0xFFF2F1EC)
val Muted = Color(0xFF9A998F)
val Accent = Color(0xFFC8F04A)

val colors = darkColorScheme(
    primary = Accent, onPrimary = Bg, secondaryContainer = Panel2, onSecondaryContainer = TextColor,
    background = Bg, onBackground = TextColor, surface = Bg, onSurface = TextColor, surfaceVariant = Panel2, onSurfaceVariant = Muted,
    surfaceContainer = Panel, surfaceContainerHigh = Panel2, surfaceContainerLow = Panel, outline = Line, outlineVariant = Line
)

/** An outline icon from SVG path data (24×24), like the desktop app's inline SVGs. */
fun icon(d: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.White), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    .build()

object Icons {
    val home = icon("M4 11l8-6.5 8 6.5v9h-5v-6h-6v6H4z")
    val search = icon("M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13zM20 20l-4.6-4.6")
    val bookmark = icon("M6 3.5h12v17l-6-4.5-6 4.5z") // the desktop's "Save to Your List"
    val settings = icon("M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM12 3v2.5M12 18.5V21M3 12h2.5M18.5 12H21M5.6 5.6l1.8 1.8M16.6 16.6l1.8 1.8M5.6 18.4l1.8-1.8M16.6 7.4l1.8-1.8")
    val check = icon("m5 12.5 4.5 4.5L19 7.5")
    val close = icon("M6 6l12 12M18 6 6 18")
    val undo = icon("M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4")
}

val FORMAT = linkedMapOf("TV" to "TV Series", "TV_SHORT" to "TV Short", "MOVIE" to "Movie", "OVA" to "OVA", "ONA" to "ONA", "SPECIAL" to "Special")
val MANGA_FORMAT = linkedMapOf("MANGA" to "Manga", "NOVEL" to "Light Novel", "ONE_SHOT" to "One Shot")
val STATUS = mapOf("RELEASING" to "Releasing", "FINISHED" to "Finished", "NOT_YET_RELEASED" to "Not yet released", "CANCELLED" to "Cancelled", "HIATUS" to "On hiatus")
val SEASON = linkedMapOf("WINTER" to "Winter", "SPRING" to "Spring", "SUMMER" to "Summer", "FALL" to "Fall")
fun fmt(f: String?) = (FORMAT[f] ?: MANGA_FORMAT[f])?.let { t(it) } ?: f.orEmpty()

/** Done: its final episode is (nearly) all watched and it has finished airing. */
fun isFinished(m: Media, p: Store.Progress?) = p != null && (p.finished || (m.status == "FINISHED" && p.episode >= m.epCount && p.time >= p.duration * 0.9))
fun inContinue(m: Media, p: Store.Progress?) = p != null && !p.hidden && !isFinished(m, p)
/** Chapters done: the one you're on isn't, unless it's read to the end. */
fun chaptersRead(p: Store.Progress?) = p?.let { it.episode - if (it.time >= it.duration * 0.9) 0 else 1 } ?: 0

/** Loads once per change of `keys`; null while loading. */
@Composable
fun <T> loading(vararg keys: Any?, fetch: suspend () -> T): State<Result<T>?> = produceState<Result<T>?>(null, *keys) { value = runCatching { fetch() } }

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) = Text(text, modifier, color = Muted, style = MaterialTheme.typography.bodyMedium)

@Composable
fun ErrorText(e: Throwable) = Text(e.message ?: e.toString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

/** A cover card: the poster, your progress on it, and its title by its colour dot. */
@Composable
fun MediaCard(m: Media, p: Store.Progress?, open: (Int) -> Unit, width: Dp? = 120.dp) {
    Column(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clip(RoundedCornerShape(10.dp)).clickable { open(m.id) },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(230f / 325f).clip(RoundedCornerShape(10.dp)).background(Panel2)) {
            AsyncImage(m.cover, null, Modifier.fillMaxWidth().aspectRatio(230f / 325f), contentScale = ContentScale.Crop)
            if (p != null && m.epCount < Int.MAX_VALUE) {
                val done = ((p.episode - 1 + p.time / p.duration.coerceAtLeast(1e-9)) / m.epCount).toFloat().coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(done).height(3.dp).background(Accent))
            }
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val dot = m.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Muted
            Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape).background(dot))
            Text(m.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
