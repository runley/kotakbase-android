package app.kotakbase

import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * Streams one torrent: downloads it in order with libtorrent and serves its files at http://127.0.0.1:<port>/<file index>,
 * with Range support, so mpv can play and seek while it downloads. A request for bytes not downloaded yet waits for them.
 */
class TorrentStream(private val dir: File) {
    private val session = SessionManager()
    private var server: ServerSocket? = null
    private var handle: TorrentHandle? = null
    private lateinit var info: TorrentInfo

    class Ready(val video: String, val subs: List<String>, val name: String)

    /** source: a magnet link or a .torrent URL. Blocks until the torrent's metadata is known. */
    fun start(source: String): Ready {
        session.start()
        val bytes = if (source.startsWith("magnet:")) session.fetchMagnet(source, 120, dir) ?: error("No metadata for the magnet")
        else URL(source).readBytes()
        info = TorrentInfo(bytes)
        val fs = info.files()
        val files = 0 until fs.numFiles()
        val video = files.maxBy { fs.fileSize(it) }
        val subs = files.filter { fs.fileName(it).lowercase().let { n -> n.endsWith(".srt") || n.endsWith(".ass") } }
        val priorities = Array(fs.numFiles()) { if (it == video || it in subs) Priority.DEFAULT else Priority.IGNORE }
        session.download(info, dir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        while (handle == null) { handle = session.find(info.infoHash()); if (handle == null) Thread.sleep(50) }

        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) // not getLoopbackAddress(): that can be ::1
        server = s
        thread(isDaemon = true) {
            while (!s.isClosed) { val c = runCatching { s.accept() }.getOrNull() ?: break; thread(isDaemon = true) { serve(c) } }
        }
        val url = { i: Int -> "http://127.0.0.1:${s.localPort}/$i" }
        return Ready(url(video), subs.map(url), fs.fileName(video))
    }

    fun status(): String = handle?.status()?.let { "${it.numPeers()} peers · ${it.downloadRate() / 1024} KB/s · ${"%.1f".format(it.progress() * 100)}%" } ?: "starting…"

    fun stop() {
        runCatching { server?.close() }
        session.stop()
    }

    private fun serve(socket: Socket) = socket.use {
        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine() ?: return
        val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
        val index = request.split(" ").getOrNull(1)?.trim('/')?.toIntOrNull()
        val out = it.getOutputStream()
        val fs = info.files()
        if (index == null || index !in 0 until fs.numFiles()) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }

        val size = fs.fileSize(index)
        val range = headers.firstOrNull { h -> h.startsWith("range:", true) }
            ?.substringAfter("=")?.split("-")?.let { (a, b) -> a.toLong() to (b.toLongOrNull() ?: (size - 1)) }
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
                val req = info.mapFile(index, pos, 1)
                awaitPiece(req.piece())
                val f = file ?: RandomAccessFile(File(dir, fs.filePath(index)), "r").also { r -> file = r }
                val pieceLeft = info.pieceLength() - req.start() // bytes of this piece from pos on
                val n = minOf(buf.size.toLong(), pieceLeft.toLong(), end - pos + 1).toInt()
                f.seek(pos)
                f.readFully(buf, 0, n)
                out.write(buf, 0, n) // throws once mpv hangs up (a seek), which ends this request
                pos += n
            }
        } catch (_: Exception) {
        } finally { file?.close() }
    }

    // shortcut: a request mpv dropped while waiting here keeps waiting until its piece arrives; fine for the spike,
    // the real stream should cancel waits (and their deadlines) when the socket closes.
    private fun awaitPiece(piece: Int) {
        val h = handle ?: return
        if (h.havePiece(piece)) return
        val last = info.numPieces() - 1
        for (i in 0..5) if (piece + i <= last) h.setPieceDeadline(piece + i, i * 200) // this piece first, then the next few
        while (!h.havePiece(piece)) Thread.sleep(100)
    }
}
