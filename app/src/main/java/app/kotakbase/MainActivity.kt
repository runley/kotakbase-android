package app.kotakbase

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import dev.jdtech.mpv.MPVLib
import java.io.File
import kotlin.concurrent.thread

// Spike: plays a torrent while it downloads (libtorrent → local HTTP → libmpv), muted, with its subtitles.
// Another source: adb shell am start -n app.kotakbase/.MainActivity --es source "magnet:?..."
private const val SINTEL = "https://webtorrent.io/torrents/sintel.torrent" // Blender's open film; has a web seed
private const val TAG = "kotakbase"

class MainActivity : Activity(), SurfaceHolder.Callback, MPVLib.EventObserver {
    private lateinit var mpv: MPVLib
    private lateinit var label: TextView
    private lateinit var stream: TorrentStream
    private var subs = emptyList<String>()
    private var name = ""
    @Volatile private var time = 0.0
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        creations++
        Log.i(TAG, "onCreate #$creations")
        val surface = SurfaceView(this).apply { holder.addCallback(this@MainActivity) }
        label = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt()); setPadding(24, 16, 24, 16) }
        setContentView(FrameLayout(this).apply { addView(surface); addView(label, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) })

        mpv = MPVLib.create(this) ?: error("libmpv didn't start")
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        // shortcut: software decoding. On the emulator, both mediacodec modes deadlock in detachSurface() when folding swaps
        // the surface; retry hardware decoding on the real Fold before settling on this (it costs battery).
        mpv.setOptionString("hwdec", intent.getStringExtra("hwdec") ?: "no") // --es hwdec mediacodec to try it
        // the torrent on disk is the buffer; mpv's own network cache (150 MiB by default) would only duplicate it in RAM
        mpv.setOptionString("demuxer-max-bytes", "32MiB")
        mpv.setOptionString("demuxer-max-back-bytes", "8MiB")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("mute", "yes") // tests stay silent
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("ytdl", "no")
        mpv.init()
        mpv.addObserver(this)
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)

        stream = TorrentStream(File(cacheDir, "torrents").apply { mkdirs() })
        val source = intent.getStringExtra("source") ?: SINTEL
        thread {
            try {
                val ready = stream.start(source)
                subs = ready.subs
                name = ready.name
                Log.i(TAG, "playing ${ready.video} (${ready.name}), subs ${ready.subs}")
                mpv.command(arrayOf("loadfile", ready.video))
            } catch (e: Exception) {
                Log.e(TAG, "stream failed", e)
                ui.post { label.text = "Failed: ${e.message}" }
            }
        }
        tick()
    }

    private fun tick() {
        val c = resources.configuration
        label.text = "$name\n${stream.status()}\n${"%.1f".format(time)} s · window ${c.screenWidthDp}×${c.screenHeightDp} dp · created $creations×"
        ui.postDelayed(::tick, 1000)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        mpv.removeObserver(this)
        mpv.destroy()
        thread { stream.stop() }
        super.onDestroy()
    }

    // the surface comes and goes (backgrounding); mpv draws into whichever one is there
    override fun surfaceCreated(holder: SurfaceHolder) {
        mpv.attachSurface(holder.surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mpv.setPropertyString("android-surface-size", "${width}x$height")
        Log.i(TAG, "surface ${width}x$height")
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    override fun event(eventId: Int) {
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
            subs.forEach { mpv.command(arrayOf("sub-add", it, "auto")) }
            Log.i(TAG, "file loaded, ${subs.size} subtitle tracks added")
        }
    }
    override fun eventProperty(property: String, value: Double) { if (property == "time-pos") time = value }
    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) {}
    override fun eventProperty(property: String, value: String) {}

    companion object { var creations = 0 } // stays 1 across fold/unfold if the activity isn't recreated
}
