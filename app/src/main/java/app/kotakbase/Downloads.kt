package app.kotakbase

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File

/**
 * Downloads for watching offline (desktop downloads.js): one entry per torrent holding the episodes picked from it,
 * kept until deleted (unlike streams). A torrent stops once its episodes are complete; unfinished ones carry on when
 * the app starts again. While anything downloads, a foreground service (a notification) keeps Android from stopping it.
 * The list: { id (info hash), mediaId, title, cover, magnet, status: active | paused | done | error | waiting, error,
 * files: [{ index, episode, name, path, length, have }] } in downloads.json.
 */
object Downloads {
    lateinit var dir: File
        private set
    private lateinit var store: File
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val handles = HashMap<String, TorrentHandle>()
    private val speed = HashMap<String, Pair<Int, Int>>() // id → (bytes/s, peers)
    private var ticker: Job? = null

    /** The list as screens show it, with live progress. */
    var items by mutableStateOf(emptyList<JSONObject>())
        private set

    fun init(context: Context) {
        if (::dir.isInitialized) return
        app = context.applicationContext
        // the app's internal storage, like streams: libtorrent's memory-mapped files crashed Android's shared-storage
        // service (FUSE) under Android/data. shortcut: not visible in file managers and removed with the app; a folder
        // picker like desktop's "Change folder" needs the storage access framework, which libtorrent can't write to
        dir = File(context.filesDir, "downloads").apply { mkdirs() }
        store = File(context.filesDir, "downloads.json")
        items = runCatching { JSONObject(store.readText()).getJSONArray("items").let { a -> List(a.length()) { a.getJSONObject(it) } } }.getOrDefault(emptyList())
        // unfinished ones carry on (a waiting one waited for a player that's gone now)
        scope.launch { items.filter { it.optString("status") !in setOf("paused", "done", "error") }.forEach { start(it) } }
    }

    private fun save() {
        val tmp = File(store.path + ".tmp")
        tmp.writeText(JSONObject().put("items", JSONArray(items.map { JSONObject(it.toString()) })).toString())
        tmp.renameTo(store)
        items = items.toList() // a new list (the same entries, which the download loop keeps updating), so screens redraw
    }
    private fun item(id: String) = items.firstOrNull { it.getString("id") == id }
    fun handle(id: String) = synchronized(handles) { handles[id] }
    private fun files(it: JSONObject) = it.getJSONArray("files").let { a -> List(a.length()) { i -> a.getJSONObject(i) } }

    /** A finished download of this episode, to play from the phone; null if there's none. */
    fun local(mediaId: Int, episode: Int): String? = items.asSequence().filter { it.optInt("mediaId") == mediaId }
        .flatMap { files(it).asSequence() }.firstOrNull { it.optInt("episode") == episode && it.optLong("have") >= it.optLong("length") }
        ?.let { File(dir, it.getString("path")) }?.takeIf { it.exists() }?.path

    /** The torrent a downloaded episode came from, and its file in it: (magnet, file index). */
    fun source(mediaId: Int, episode: Int): Pair<String, Int>? = items.firstNotNullOfOrNull { it ->
        if (it.optInt("mediaId") != mediaId) null else files(it).firstOrNull { f -> f.optInt("episode") == episode }?.let { f -> it.getString("magnet") to f.getInt("index") }
    }

    /** episodes: (episode, numbering); fileIndex: one file picked by hand. Returns how many episodes were added, or -1: waiting for the player. */
    suspend fun add(mediaId: Int, title: String, cover: String?, link: String, episodes: List<Pair<Int, JSONObject>>, fileIndex: Int? = null): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            val info = Torrent.info(link)
            val id = info.infoHash().toHex()
            val all = (0 until info.numFiles()).map { JSONObject().put("name", info.files().filePath(it)).put("index", it) }
            if (Shared.call("api.refuse(${lit(JSONArray(all).toString())})") == "risky")
                throw Exception(t("This torrent contains program files, a common sign of a fake or malware, so it won't be downloaded. Pick another source."))
            val eps = JSONArray(episodes.map { (e, nb) -> JSONObject().put("episode", e).put("numbering", nb) })
            val picked = Shared.call("api.chooseFiles(${lit(JSONArray(all).toString())}, ${fileIndex ?: "null"}, ${lit(eps.toString())})") as JSONArray
            if (picked.length() == 0) throw Exception(if (episodes.size > 1) t("None of this show's episodes were found in this torrent. Pick another source.") else t("There's no video for this episode in this torrent. Pick another source."))
            val it = item(id) ?: JSONObject().put("id", id).put("mediaId", mediaId).put("title", title).put("cover", cover).put("magnet", link)
                .put("files", JSONArray()).also { n -> items = items + n }
            val have = files(it).map { f -> f.getInt("index") }.toSet()
            var added = 0
            for (i in 0 until picked.length()) {
                val p = picked.getJSONObject(i)
                val index = p.getInt("index")
                if (index in have) continue
                it.getJSONArray("files").put(JSONObject().put("index", index).put("episode", p.opt("episode")).put("name", info.files().fileName(index))
                    .put("path", info.files().filePath(index)).put("length", info.files().fileSize(index)).put("have", 0))
                added++
            }
            // the player has this torrent open in its throwaway folder: start once it lets go
            if (Torrent.current?.hash == id && Torrent.current?.owned == false) { it.put("status", "waiting"); save(); return@withContext -1 }
            save()
            start(it, info)
            added
        }
    }

    private suspend fun start(it: JSONObject, known: TorrentInfo? = null) {
        val id = it.getString("id")
        it.put("status", "active").remove("error")
        save()
        runCatching {
            val info = known ?: Torrent.info(it.getString("magnet"))
            val wanted = files(it).map { f -> f.getInt("index") }.toSet()
            val existing = Torrent.session.find(info.infoHash())
            if (existing != null) existing.prioritizeFiles(Array(info.numFiles()) { i -> if (i in wanted) Priority.DEFAULT else Priority.IGNORE })
            else Torrent.session.download(info, dir, null, Array(info.numFiles()) { i -> if (i in wanted) Priority.DEFAULT else Priority.IGNORE }, null, org.libtorrent4j.swig.torrent_flags_t()) // no flags (null crashes)
            var h: TorrentHandle? = null
            while (h == null) { h = Torrent.session.find(info.infoHash()); if (h == null) delay(50) }
            h.resume()
            synchronized(handles) { handles[id] = h }
        }.onFailure { e -> it.put("status", "error").put("error", e.message); save() }
        tick()
    }

    /** Progress every second while anything downloads; a torrent whose episodes are all there stops. */
    private fun tick() {
        DownloadService.update(app)
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val active = synchronized(handles) { handles.toMap() }
                if (active.isEmpty()) break
                for ((id, h) in active) {
                    val it = item(id) ?: continue
                    val progress = runCatching { h.fileProgress() }.getOrNull() ?: continue
                    files(it).forEach { f -> f.put("have", progress.getOrElse(f.getInt("index")) { 0 }) }
                    speed[id] = runCatching { h.status().let { s -> s.downloadRate() to s.numPeers() } }.getOrDefault(0 to 0)
                    if (files(it).all { f -> f.optLong("have") >= f.optLong("length") }) { it.put("status", "done"); release(id) }
                }
                save()
                DownloadService.update(app)
                delay(1000)
            }
            DownloadService.update(app)
        }
    }

    /** Done or paused: the torrent goes (its files stay), unless the player is reading from it right now. */
    private fun release(id: String) {
        if (Torrent.current?.hash == id) return
        val h = synchronized(handles) { handles.remove(id) } ?: return
        runCatching { Torrent.session.remove(h, SessionHandle.DELETE_PARTFILE) } // the pieces shared with unwanted files
    }

    fun rate(id: String) = speed[id]

    fun pause(id: String) = scope.launch { item(id)?.put("status", "paused"); release(id); save(); DownloadService.update(app) }
    fun resume(id: String) = scope.launch { item(id)?.let { start(it) } }

    /** Deletes a whole download, or (index) one episode's file of it. */
    fun remove(id: String, index: Int? = null) = scope.launch {
        val it = item(id) ?: return@launch
        val gone = files(it).filter { f -> index == null || f.getInt("index") == index }
        val left = files(it) - gone.toSet()
        if (left.isEmpty()) { release(id); synchronized(handles) { handles.remove(id) }?.let { h -> runCatching { Torrent.session.remove(h, SessionHandle.DELETE_PARTFILE) } } }
        gone.forEach { f -> File(dir, f.getString("path")).takeIf { p -> p.canonicalPath.startsWith(dir.canonicalPath + File.separator) }?.delete() } // names come from torrents
        if (left.isEmpty()) items = items.filter { o -> o !== it } else it.put("files", JSONArray(left))
        save()
        DownloadService.update(app)
    }

    /** The player let go of a torrent: downloads that waited for it start. */
    fun streamStopped() = scope.launch {
        items.filter { it.optString("status") == "waiting" }.forEach { start(it) }
        items.filter { it.optString("status") == "done" && handle(it.getString("id")) != null }.forEach { release(it.getString("id")) }
    }

    fun downloading() = synchronized(handles) { handles.isNotEmpty() }
}

/** Keeps downloads going in the background, with a notification saying how far they are (dataSync service). */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "downloads"
        fun update(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Downloads.downloading()) runCatching { context.startForegroundService(intent) } else context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, t("Downloads"), NotificationManager.IMPORTANCE_LOW))
        val files = Downloads.items.filter { it.optString("status") == "active" }.flatMap { it.getJSONArray("files").let { a -> List(a.length()) { i -> a.getJSONObject(i) } } }
        val total = files.sumOf { it.optLong("length") }.coerceAtLeast(1)
        val percent = files.sumOf { it.optLong("have") } * 100 / total
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(t("Downloads"))
            .setContentText(t("Downloading: {percent}", "percent" to "$percent%")).setProgress(100, percent.toInt(), false).setOngoing(true)
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        if (!Downloads.downloading()) stopSelf()
        return START_NOT_STICKY
    }
}
