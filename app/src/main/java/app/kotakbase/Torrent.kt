package app.kotakbase

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Streams torrents to the player (desktop main.js startPlay/serve): one torrent at a time, downloading only the file
 * being watched (plus the next episode's near the end), in order, served at http://127.0.0.1:<port>/<hash>/<index>
 * with Range support so mpv can seek. A request for bytes not downloaded yet waits for them, and gives up as soon as
 * a newer request for the same file comes (mpv seeking), so stale waits don't hold the download back.
 */
object Torrent {
    private lateinit var dir: File
    /** One libtorrent session for the stream and the downloads. */
    val session by lazy { SessionManager().apply { start() } }
    private val lock = Mutex()
    private lateinit var server: ServerSocket
    private val requests = ConcurrentHashMap<Int, AtomicInteger>() // file index → latest request, to drop stale ones

    /** owned: a download's torrent the player reads from, whose files and other episodes are left alone. */
    class Loaded(val link: String, val info: TorrentInfo, val handle: TorrentHandle, val dir: File, val owned: Boolean = false) {
        val hash: String = info.infoHash().toHex()
        /** The torrent's files as episode.js reads them. */
        val files = (0 until info.numFiles()).map { org.json.JSONObject().put("name", info.files().filePath(it)).put("index", it) }
        fun name(i: Int): String = info.files().fileName(i)
    }
    @Volatile var current: Loaded? = null
        private set

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.cacheDir, "torrents").apply { deleteRecursively(); mkdirs() } // the last session's stream isn't kept
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) // not getLoopbackAddress(): that can be ::1
        thread(isDaemon = true, name = "torrent-http") {
            while (true) { val c = runCatching { server.accept() }.getOrNull() ?: break; thread(isDaemon = true) { serve(c) } }
        }
    }

    /** Loads `link` (a magnet or a .torrent URL), replacing the torrent before it unless it's the same one. */
    suspend fun open(link: String): Loaded = lock.withLock {
        current?.takeIf { it.link == link }?.let { return it }
        withContext(Dispatchers.IO) {
            val info = info(link)
            current?.takeIf { it.hash == info.infoHash().toHex() }?.let { return@withContext it }
            drop()
            Downloads.handle(info.infoHash().toHex())?.let { return@withContext Loaded(link, info, it, Downloads.dir, owned = true).also { l -> current = l } }
            // nothing downloads until a file is picked
            session.download(info, dir, null, Array(info.numFiles()) { Priority.IGNORE }, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            var handle: TorrentHandle? = null
            while (handle == null) { handle = session.find(info.infoHash()); if (handle == null) Thread.sleep(50) }
            Loaded(link, info, handle, dir).also { current = it }
        }
    }

    /**
     * Downloads file `index` (and `also`, the next episode, when given), the start and end of it first: mpv reads a
     * file's index before playing, which MP4 keeps at the end and MKV's seek index too. Returns the URL to play.
     */
    fun play(t: Loaded, index: Int, also: Int? = null): String {
        if (t.owned) listOfNotNull(index, also).forEach { t.handle.filePriority(it, Priority.TOP_PRIORITY) } // the download's other episodes carry on
        else t.handle.prioritizeFiles(Array(t.info.numFiles()) { if (it == index || it == also) Priority.DEFAULT else Priority.IGNORE })
        val first = t.info.mapFile(index, 0, 1).piece()
        val last = t.info.mapFile(index, (t.info.files().fileSize(index) - 1).coerceAtLeast(0), 1).piece()
        for (i in 0..2) if (first + i <= last) t.handle.setPieceDeadline(first + i, i * 100)
        for (i in 0..1) if (last - i >= first) t.handle.setPieceDeadline(last - i, 300 + i * 100)
        return "http://127.0.0.1:${server.localPort}/${t.hash}/$index"
    }

    /** A torrent's metadata: from its .torrent URL, or from peers for a magnet (blocking; call off the main thread). */
    fun info(link: String): TorrentInfo = TorrentInfo(
        if (link.startsWith("magnet:")) session.fetchMagnet(link, 120, dir) ?: throw Exception("No peers sent this torrent's file list")
        else AniList.http.newCall(Request.Builder().url(link).build()).execute().use { it.body?.bytes() ?: throw Exception("HTTP ${it.code}") })

    fun status(): String = current?.handle?.status()?.let { "${it.numPeers()} · ${it.downloadRate() / 1024} KB/s" } ?: ""

    /** The stream's done with: its torrent and files go (downloads, phase 4, will keep theirs). */
    suspend fun stop() { lock.withLock { withContext(Dispatchers.IO) { drop() } }; Downloads.streamStopped() }

    private fun drop() {
        current?.takeIf { !it.owned }?.let { runCatching { session.remove(it.handle, SessionHandle.DELETE_FILES) } }
        current = null
        requests.clear()
    }

    private fun serve(socket: Socket) = socket.use {
        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine() ?: return
        val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
        val out = it.getOutputStream()
        val (hash, idx) = request.split(" ").getOrNull(1)?.trim('/')?.split("/").let { p -> p?.getOrNull(0) to p?.getOrNull(1)?.toIntOrNull() }
        val t = current
        if (t == null || hash != t.hash || idx == null || idx !in 0 until t.info.numFiles()) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
        val generation = requests.getOrPut(idx) { AtomicInteger() }.incrementAndGet()

        val size = t.info.files().fileSize(idx)
        val range = headers.firstOrNull { h -> h.startsWith("range:", true) }?.substringAfter("=")?.split("-")
            ?.let { (a, b) -> (a.toLongOrNull() ?: 0L) to (b.toLongOrNull() ?: (size - 1)) }
        val (from, to) = range ?: (0L to size - 1)
        if (from >= size) { out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
        val end = minOf(to, size - 1)
        val head = if (range != null) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$end/$size\r\n" else "HTTP/1.1 200 OK\r\n"
        out.write("${head}Accept-Ranges: bytes\r\nContent-Length: ${end - from + 1}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
        if (request.startsWith("HEAD")) return

        val buf = ByteArray(64 * 1024)
        var pos = from
        var file: RandomAccessFile? = null
        try {
            while (pos <= end) {
                val req = t.info.mapFile(idx, pos, 1)
                if (!awaitPiece(t, req.piece(), idx, generation)) return
                val f = file ?: RandomAccessFile(File(t.dir, t.info.files().filePath(idx)), "r").also { r -> file = r }
                val n = minOf(buf.size.toLong(), (t.info.pieceLength() - req.start()).toLong(), end - pos + 1).toInt()
                f.seek(pos)
                f.readFully(buf, 0, n)
                out.write(buf, 0, n) // throws once mpv hangs up, which ends this request
                pos += n
            }
        } catch (_: Exception) {
        } finally { file?.close() }
    }

    /** Waits for `piece`, asking for it (and the few after it) first; false when a newer request took over. */
    private fun awaitPiece(t: Loaded, piece: Int, idx: Int, generation: Int): Boolean {
        if (t.handle.havePiece(piece)) return true
        val last = t.info.numPieces() - 1
        for (i in 0..5) if (piece + i <= last) t.handle.setPieceDeadline(piece + i, i * 200)
        while (!t.handle.havePiece(piece)) {
            if (current !== t || requests[idx]?.get() != generation) return false
            Thread.sleep(100)
        }
        return true
    }

    // ---- seeder counts for results an extension sent without them (desktop main.js scrape) ----

    // Nyaa's tracker: anime torrents announce there (big public trackers report 0 for them)
    private const val SCRAPE_URL = "http://nyaa.tracker.wf:7777/scrape"

    /** hash → (seeders, leechers, downloads); empty on any failure. Asked in groups of 20, as the tracker wants. */
    suspend fun scrape(hashes: List<String>): Map<String, Triple<Long, Long, Long>> = withContext(Dispatchers.IO) {
        hashes.filter { it.matches(Regex("[0-9a-f]{40}")) }.chunked(20).flatMap { chunk ->
            runCatching {
                val query = chunk.joinToString("&") { h -> "info_hash=" + h.chunked(2).joinToString("") { "%$it" } }
                val body = AniList.http.newCall(Request.Builder().url("$SCRAPE_URL?$query").build()).execute().use { it.body?.bytes() ?: ByteArray(0) }
                @Suppress("UNCHECKED_CAST")
                val files = (Bencode(body).read() as? Map<String, Any?>)?.get("files") as? Map<String, Any?> ?: emptyMap()
                files.map { (key, v) ->
                    val c = v as? Map<*, *> ?: emptyMap<String, Any?>()
                    val hex = if (key.length == 20) key.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it) } else key.lowercase()
                    hex to Triple(c["complete"] as? Long ?: 0, c["incomplete"] as? Long ?: 0, c["downloaded"] as? Long ?: 0)
                }
            }.getOrDefault(emptyList())
        }.toMap()
    }
}

/** Just enough bencode to read a tracker's scrape answer; byte strings come out as ISO-8859-1 text. */
class Bencode(private val b: ByteArray) {
    private var i = 0
    fun read(): Any? = when (b[i].toInt().toChar()) {
        'i' -> { val e = indexOf('e'); String(b, i + 1, e - i - 1).toLong().also { i = e + 1 } }
        'l' -> { i++; buildList { while (b[i].toInt().toChar() != 'e') add(read()) }.also { i++ } }
        'd' -> { i++; buildMap { while (b[i].toInt().toChar() != 'e') { val k = read() as String; put(k, read()) } }.also { i++ } }
        else -> { val c = indexOf(':'); val n = String(b, i, c - i).toInt(); String(b, c + 1, n, Charsets.ISO_8859_1).also { i = c + 1 + n } }
    }
    private fun indexOf(ch: Char): Int { var j = i; while (b[j].toInt().toChar() != ch) j++; return j }
}
