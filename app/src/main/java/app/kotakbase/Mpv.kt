package app.kotakbase

import android.content.Context
import android.os.Build
import android.view.SurfaceHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.mpv.MPVLib
import org.json.JSONArray
import java.io.File

/**
 * libmpv in a SurfaceView, with what the controls show as Compose state. Folding swaps the surface; while there's none
 * (background) mpv has no screen to decode for, so the activity pauses it, and hardware decoding is switched back on
 * when a surface comes back (mpv falls back to software decoding without one).
 */
class Mpv(context: Context) : SurfaceHolder.Callback, MPVLib.EventObserver {
    private val mpv = MPVLib.create(context) ?: error("libmpv didn't start")
    // the emulator's video decoder deadlocks on surface swaps (and has nothing to gain): software there
    private val hwdec = if (Build.FINGERPRINT.contains("generic") || Build.HARDWARE in setOf("goldfish", "ranchu")) "no" else "mediacodec"

    var time by mutableDoubleStateOf(0.0)
    var duration by mutableDoubleStateOf(0.0)
    var paused by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var ended by mutableStateOf(false)
    var tracks by mutableStateOf(JSONArray())
    var chapters by mutableStateOf(JSONArray())
    var loaded by mutableStateOf(0) // bumped when a file has loaded
    var aspect by mutableDoubleStateOf(16.0 / 9)

    init {
        // libass has no system font lookup on Android: Roboto for subtitles without fonts of their own (MKVs bring theirs)
        val fonts = File(context.filesDir, "fonts").apply { mkdirs() }
        File("/system/fonts/Roboto-Regular.ttf").takeIf { it.exists() && !File(fonts, it.name).exists() }?.let { it.copyTo(File(fonts, it.name)) }
        listOf(
            "vo" to "gpu", "gpu-context" to "android", "opengl-es" to "yes", "hwdec" to hwdec, "ao" to "audiotrack,opensles",
            "keep-open" to "yes", "force-window" to "no", "ytdl" to "no", "save-position-on-quit" to "no",
            // the torrent on disk is the buffer; mpv's own network cache (150 MiB by default) would only duplicate it in RAM
            "demuxer-max-bytes" to "32MiB", "demuxer-max-back-bytes" to "8MiB",
            // a phone screen doesn't need mpv's high-quality scaling passes and 16-bit buffers, which cost ~100 MB of GPU
            // memory at the inner screen's size (and battery)
            "profile" to "fast", "fbo-format" to "rgba8",
            "sub-fonts-dir" to fonts.path, "sub-font" to "Roboto", "embeddedfonts" to "yes",
            "slang" to "", "alang" to "" // tracks are picked by Settings' language lists, as on desktop
        ).forEach { (k, v) -> mpv.setOptionString(k, v) }
        mpv.init()
        mpv.addObserver(this)
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        mpv.observeProperty("chapter-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        mpv.observeProperty("video-params/aspect", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
    }

    fun load(url: String, start: Double) {
        time = start; duration = 0.0; ended = false; buffering = true
        mpv.setOptionString("start", if (start > 0) start.toString() else "none") // applies to the next file loaded
        mpv.command(arrayOf("loadfile", url, "replace"))
        mpv.setPropertyBoolean("pause", false)
    }
    fun seek(to: Double) = mpv.command(arrayOf("seek", to.coerceAtLeast(0.0).toString(), "absolute"))
    fun seekBy(by: Double) = mpv.command(arrayOf("seek", by.toString(), "relative"))
    fun pause(on: Boolean) = mpv.setPropertyBoolean("pause", on)
    fun setTrack(kind: String, id: Int?) = mpv.setPropertyString(kind, id?.toString() ?: "no")
    fun selected(kind: String): Int? = mpv.getPropertyString(kind)?.toIntOrNull()
    fun destroy() { mpv.removeObserver(this); mpv.destroy() }

    override fun surfaceCreated(holder: SurfaceHolder) {
        mpv.attachSurface(holder.surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
        mpv.setPropertyString("hwdec", hwdec) // back from the software decoding it fell to without a surface
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = mpv.setPropertyString("android-surface-size", "${width}x$height")
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    override fun event(eventId: Int) { if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) loaded++ }
    override fun eventProperty(property: String) {
        val list = { name: String -> mpv.getPropertyString(name)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray() }
        when (property) { "track-list" -> tracks = list(property); "chapter-list" -> chapters = list(property) }
    }
    override fun eventProperty(property: String, value: Double) {
        when (property) { "time-pos" -> time = value; "duration" -> duration = value; "video-params/aspect" -> if (value > 0) aspect = value }
    }
    override fun eventProperty(property: String, value: Boolean) {
        when (property) { "pause" -> paused = value; "paused-for-cache" -> buffering = value; "eof-reached" -> ended = value }
    }
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: String) {}
}
