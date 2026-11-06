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

    /**
     * Called when someone submits the "Add to Guide" page (http://<phone>:<port>/add) from another
     * device on the Wi‑Fi. Return true if the link was accepted.
     */
    @Volatile var onAddLink: ((url: String, title: String?) -> Boolean)? = null

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
        if (path == "/add" || path == "/") {
            var message: String? = null
            if (method == "POST") {
                val len = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 16_384)
                val body = ByteArray(len)
                var read = 0
                while (read < len) { val r = input.read(body, read, len - read); if (r < 0) break; read += r }
                val form = parseForm(String(body, 0, read, Charsets.UTF_8))
                val url = form["url"]?.trim().orEmpty()
                val ok = url.isNotEmpty() && (onAddLink?.invoke(url, form["title"]?.trim()?.ifEmpty { null }) == true)
                message = if (ok) "Added to your Guide ✓" else "That doesn't look like a link — try again."
            }
            val html = addPage(message).toByteArray(Charsets.UTF_8)
            writeHead(out, "200 OK", "text/html; charset=utf-8", html.size.toLong(), null)
            out.write(html)
            out.flush()
            return
        }
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

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }.toMap()

    private fun addPage(message: String?): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Add to TV Hub Guide</title>
        <style>
          body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#0A0B10;color:#F4F4F8;display:flex;justify-content:center}
          main{max-width:520px;width:100%;padding:32px 20px}
          h1{font-size:28px;margin:0 0 6px;background:linear-gradient(90deg,#8B6CFF,#FF5FA2,#FFA26B);-webkit-background-clip:text;color:transparent}
          p{color:#9A9CB0;line-height:1.5}
          label{display:block;margin:18px 0 6px;font-weight:600}
          input{width:100%;box-sizing:border-box;padding:14px 16px;border-radius:14px;border:1px solid #2A2E40;background:#1D2030;color:#F4F4F8;font-size:16px}
          button{margin-top:22px;width:100%;padding:15px;border:0;border-radius:999px;font-size:17px;font-weight:700;color:#fff;background:linear-gradient(90deg,#8B6CFF,#FF5FA2,#FFA26B)}
          .msg{margin-top:18px;padding:12px 16px;border-radius:14px;background:#1D2030}
        </style></head><body><main>
        <h1>Add to TV Hub</h1>
        <p>Paste a link to a show, movie, YouTube video or channel. In most streaming apps, tap <b>Share → Copy link</b>. It appears in your Guide, and tapping it on TV Hub jumps straight to it on the TV.</p>
        <form method="post" action="/add">
          <label for="u">Link</label><input id="u" name="url" type="url" placeholder="https://www.netflix.com/title/…" required autofocus>
          <label for="t">Name (optional)</label><input id="t" name="title" placeholder="Filled in automatically">
          <button type="submit">Add to Guide</button>
        </form>
        ${if (message != null) "<div class=\"msg\">${message}</div>" else ""}
        </main></body></html>
    """.trimIndent()

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
