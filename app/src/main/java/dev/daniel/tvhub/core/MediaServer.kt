package dev.daniel.tvhub.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors

/** Something the phone can stream to a TV. */
interface MediaSource {
    val mime: String
    val size: Long

    /** Opens the content positioned at [offset]. */
    fun open(offset: Long): InputStream
}

/**
 * A tiny HTTP server on the phone. The TV's viewer downloads photos/videos from it.
 * Only files that were explicitly shared get a random, unguessable URL; nothing else is served.
 */
class MediaServer(private val preferredPort: Int = 8765) {
    private val entries: MutableMap<String, MediaSource> = Collections.synchronizedMap(
        object : LinkedHashMap<String, MediaSource>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaSource>?) = size > 30
        },
    )
    private val pool = Executors.newCachedThreadPool()

    @Volatile private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: -1

    @Synchronized
    fun start() {
        if (server?.isClosed == false) return
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) }
        } catch (e: Exception) {
            ServerSocket(0)
        }
        server = ss
        Thread({
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                pool.execute { runCatching { handle(s) }; runCatching { s.close() } }
            }
        }, "tvhub-media-server").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    fun register(source: MediaSource): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        entries[token] = source
        return "/m/$token"
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)?.uppercase() ?: return
        val path = parts.getOrNull(1)?.substringBefore('?') ?: return
        val token = path.removePrefix("/m/")
        val src = if (path.startsWith("/m/")) entries[token] else null
        if (src == null || (method != "GET" && method != "HEAD")) {
            writeHead(out, if (src == null) "404 Not Found" else "405 Method Not Allowed", "text/plain", 0, null)
            out.flush(); return
        }

        val total = src.size
        var start = 0L
        var end = total - 1
        var partial = false
        headers["range"]?.let { r ->
            val m = Regex("bytes=(\\d*)-(\\d*)").find(r) ?: return@let
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            when {
                a.isEmpty() && b.isNotEmpty() -> { start = maxOf(0, total - b.toLong()); end = total - 1 }
                a.isNotEmpty() -> { start = a.toLong(); end = if (b.isNotEmpty()) minOf(b.toLong(), total - 1) else total - 1 }
            }
            partial = true
        }
        if (total <= 0 || start > end || start >= total) {
            writeHead(out, "416 Range Not Satisfiable", "text/plain", 0, "bytes */$total")
            out.flush(); return
        }
        val length = end - start + 1
        writeHead(
            out, if (partial) "206 Partial Content" else "200 OK", src.mime, length,
            if (partial) "bytes $start-$end/$total" else null,
        )
        if (method == "HEAD") { out.flush(); return }
        src.open(start).use { inp ->
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    private fun writeHead(out: BufferedOutputStream, status: String, mime: String, length: Long, contentRange: String?) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (contentRange != null) sb.append("Content-Range: ").append(contentRange).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }
}

/** Skips exactly [n] bytes or until EOF. */
fun InputStream.skipFully(n: Long) {
    var left = n
    val scratch = ByteArray(8192)
    while (left > 0) {
        val s = skip(left)
        if (s > 0) { left -= s; continue }
        val r = read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
        if (r < 0) return
        left -= r
    }
}
