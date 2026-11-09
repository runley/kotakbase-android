package app.kotakbase

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.util.UUID

/**
 * Watch Together (desktop room.js / room-net.js, unchanged, in an invisible WebView: it has the WebRTC they need, so
 * rooms work across phone and desktop). This side starts and leaves rooms, shows their state, and connects the room to
 * the player: its state goes in a few times a second, the host's pause/play/seek come back, and what the host
 * starts opens here.
 */
object Room {
    private lateinit var app: Context
    private var web: WebView? = null
    private var loaded = false
    private val pending = mutableListOf<String>() // calls made before the page was ready
    private val main = Handler(Looper.getMainLooper())

    /** The room as room.js last described it (null: not in one). */
    var state by mutableStateOf<JSONObject?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    /** The player open here, for the host's pause/play/seek. */
    @Volatile var mpv: Mpv? = null

    val active get() = state != null

    fun init(context: Context) { if (!::app.isInitialized) app = context.applicationContext }

    fun deviceId(): String = Store.string("deviceId")?.trim('"') ?: UUID.randomUUID().toString().also { Store.putString("deviceId", JSONObject.quote(it)) }
    fun name(): String = Store.string("roomName")?.trim('"')?.ifEmpty { null } ?: "${t("Guest")} ${(100..999).random()}"

    @SuppressLint("SetJavaScriptEnabled")
    private fun page(): WebView = web ?: WebView(app).also { w ->
        web = w
        w.settings.javaScriptEnabled = true
        // the app's assets at https://appassets.androidplatform.net: a secure origin, which WebRTC and crypto.subtle need
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app)).build()
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
        }
        w.addJavascriptInterface(Bridge, "Native")
        w.loadUrl("https://appassets.androidplatform.net/assets/js/room.html")
    }

    private fun js(code: String) = main.post { if (loaded) page().evaluateJavascript(code, null) else { pending += code; page() } }

    /** Creates a room (no code) or joins one by its code; your name is kept for next time. */
    fun open(code: String?, name: String) {
        error = null
        Store.putString("roomName", JSONObject.quote(name))
        js("__room.open(${JSONObject.quote(code ?: "")}, ${JSONObject.quote(name)}, ${JSONObject.quote(deviceId())})")
    }
    fun leave() = js("__room.leave()")
    fun say(text: String) = js("__room.say(${JSONObject.quote(text)})")
    fun rename(name: String) { Store.putString("roomName", JSONObject.quote(name)); js("__room.rename(${JSONObject.quote(name)})") }
    fun makeHost(id: String) = js("__room.makeHost(${JSONObject.quote(id)})")
    fun kick(id: String, ban: Boolean) = js("__room.kick(${JSONObject.quote(id)}, $ban)")

    /** This device started an episode: as host, that's what the room watches now (desktop room.played). */
    fun played(mediaId: Int, episode: Int, link: String, title: String, fileIndex: Int, numbering: JSONObject) {
        if (!active) return
        js("__room.played(${JSONObject.quote(JSONObject().put("mediaId", mediaId).put("episode", episode).put("link", link).put("title", title)
            .put("fileIndex", fileIndex).put("numbering", numbering).toString())})")
    }

    /** The player's state for room.js, a few times a second while in a room (open: false when no player is up). */
    fun push(open: Boolean, duration: Double, time: Double, paused: Boolean, buffering: Boolean) {
        if (!active) return
        js("__player({open: $open, duration: ${if (duration > 0) duration else "NaN"}, time: $time, paused: $paused, buffering: $buffering})")
    }

    // a bare info hash (what rooms share instead of .torrent links) → a magnet libtorrent can open, with public trackers
    private fun magnet(link: String) = if (link.startsWith("magnet:")) link else "magnet:?xt=urn:btih:$link" +
        listOf("udp://tracker.opentrackr.org:1337/announce", "udp://open.stealth.si:80/announce", "http://nyaa.tracker.wf:7777/announce").joinToString("") { "&tr=" + android.net.Uri.encode(it) }

    private object Bridge {
        @JavascriptInterface fun ready() = main.post { loaded = true; pending.forEach { page().evaluateJavascript(it, null) }; pending.clear() }.let { }
        @JavascriptInterface fun changed(json: String) { main.post { state = runCatching { JSONObject(json) }.getOrNull() } }
        @JavascriptInterface fun error(message: String) { main.post { error = t("Couldn't start Watch Together: {error}", "error" to message) } }
        @JavascriptInterface fun removed(banned: Boolean) { main.post { error = if (banned) t("The host banned you from this room.") else t("The host removed you from the room.") } }
        // the host started something: play it here (the host's player is the clock, so it starts wherever they are)
        @JavascriptInterface fun load(json: String) {
            val l = JSONObject(json)
            main.post { PlayerActivity.start(app, l.getInt("mediaId"), l.getInt("episode"), magnet(l.getString("link")), l.optInt("fileIndex", -1)) }
        }
        @JavascriptInterface fun seek(time: Double) { main.post { mpv?.seek(time) } }
        @JavascriptInterface fun play() { main.post { mpv?.pause(false) } }
        @JavascriptInterface fun pause() { main.post { mpv?.pause(true) } }
        @JavascriptInterface fun rate(rate: Double) { main.post { mpv?.speed(rate) } }
        @JavascriptInterface fun t(text: String, vars: String): String = I18n.t(text, JSONObject(vars).let { o -> o.keys().asSequence().associateWith { o.opt(it) } })
    }
}

/** Invite links (kotakbase://join/CODE) that opened the app; the main screen takes them to Watch Together. */
object Invites {
    var code by mutableStateOf<String?>(null)
    fun take(intent: android.content.Intent?) { intent?.data?.toString()?.let(::parseCode)?.let { code = it } }
}
