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

/** What the phone's web page (http://<phone>:8765/) can do. Implemented by TvHub. */
interface HubWeb {
    fun tvs(): List<Pair<String, String>>
    fun addLink(url: String, title: String?): Boolean
    fun playLink(tvId: String, url: String): Boolean
    fun castUpload(tvId: String, file: java.io.File, mime: String, name: String): Boolean
    val uploadDir: java.io.File
}

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

    /** Handles the web page other devices on the Wi‑Fi can open to cast, play links or add to the Guide. */
    @Volatile var web: HubWeb? = null

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
        if (path == "/" || path == "/add" || path == "/play" || path == "/cast") {
            var message: String? = null
            val w = web
            if (method == "POST" && w != null) {
                val form = parseForm(readBody(input, headers, 16_384))
                val url = form["url"]?.trim().orEmpty()
                val tv = form["tv"].orEmpty()
                message = when {
                    url.isEmpty() -> "Paste a link first."
                    path == "/play" && w.playLink(tv, url) -> "Playing on ${w.tvs().firstOrNull { it.first == tv }?.second ?: "the TV"} ✓"
                    path == "/play" -> "That doesn't look like a link — try again."
                    w.addLink(url, form["title"]?.trim()?.ifEmpty { null }) -> "Added to your Guide ✓"
                    else -> "That doesn't look like a link — try again."
                }
            }
            val html = webPage(w?.tvs() ?: emptyList(), message).toByteArray(Charsets.UTF_8)
            writeHead(out, "200 OK", "text/html; charset=utf-8", html.size.toLong(), null)
            out.write(html)
            out.flush()
            return
        }
        if (path == "/upload" && method == "POST") {
            val w = web
            val q = parseForm(parts.getOrNull(1)?.substringAfter('?', "").orEmpty())
            val len = headers["content-length"]?.toLongOrNull() ?: -1L
            val ok = if (w != null && len > 0) {
                w.uploadDir.mkdirs()
                w.uploadDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
                val name = q["name"]?.ifEmpty { null } ?: "upload"
                val ext = name.substringAfterLast('.', "").take(5).filter { it.isLetterOrDigit() }
                val f = java.io.File(w.uploadDir, UUID.randomUUID().toString() + if (ext.isNotEmpty()) ".$ext" else "")
                f.outputStream().use { fo ->
                    val buf = ByteArray(64 * 1024)
                    var left = len
                    while (left > 0) {
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) break
                        fo.write(buf, 0, r)
                        left -= r
                    }
                }
                w.castUpload(q["tv"].orEmpty(), f, q["type"]?.ifEmpty { null } ?: guessMime(name), name)
            } else false
            val body = (if (ok) "{\"ok\":true}" else "{\"ok\":false}").toByteArray()
            writeHead(out, if (ok) "200 OK" else "400 Bad Request", "application/json", body.size.toLong(), null)
            out.write(body)
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

    private fun readBody(input: InputStream, headers: Map<String, String>, max: Int): String {
        val len = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, max)
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val r = input.read(body, read, len - read); if (r < 0) break; read += r }
        return String(body, 0, read, Charsets.UTF_8)
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "heic" -> "image/heic"
        "mp4", "m4v" -> "video/mp4"; "mov" -> "video/quicktime"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun webPage(tvs: List<Pair<String, String>>, message: String?): String {
        val buttons = tvs.joinToString("") { (id, name) -> "<button type=\"button\" class=\"tv\" data-id=\"" + esc(id) + "\">" + esc(name) + "</button>" }
        val msg = if (message != null) "<div class=\"msg\">" + esc(message) + "</div>" else ""
        return """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>TV Hub</title>
        <style>
          body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#0A0B10;color:#F4F4F8;display:flex;justify-content:center}
          main{max-width:540px;width:100%;padding:28px 20px 48px}
          h1{font-size:30px;margin:0 0 4px;background:linear-gradient(90deg,#8B6CFF,#FF5FA2,#FFA26B);-webkit-background-clip:text;background-clip:text;color:transparent}
          h2{font-size:18px;margin:28px 0 8px}
          p,small{color:#9A9CB0;line-height:1.5}
          .tvs{display:flex;gap:8px;flex-wrap:wrap;margin-top:14px}
          .tv{padding:11px 18px;border-radius:999px;border:1px solid #2A2E40;background:#14161F;color:#F4F4F8;font-size:15px;font-weight:600}
          .tv.on{background:linear-gradient(90deg,#8B6CFF,#FF5FA2,#FFA26B);border-color:transparent}
          .card{background:#14161F;border:1px solid #2A2E40;border-radius:22px;padding:18px;margin-top:10px}
          input[type=url],input[type=text]{width:100%;box-sizing:border-box;padding:14px 16px;border-radius:14px;border:1px solid #2A2E40;background:#1D2030;color:#F4F4F8;font-size:16px}
          input[type=file]{width:100%;color:#9A9CB0;margin:4px 0 12px}
          .go{margin-top:12px;width:100%;padding:15px;border:0;border-radius:999px;font-size:17px;font-weight:700;color:#fff;background:linear-gradient(90deg,#8B6CFF,#FF5FA2,#FFA26B)}
          .alt{margin-top:10px;width:100%;padding:13px;border-radius:999px;font-size:15px;font-weight:600;color:#F4F4F8;background:#1D2030;border:1px solid #2A2E40}
          .msg{margin-top:16px;padding:12px 16px;border-radius:14px;background:#1D2030}
          progress{width:100%;height:10px;margin-top:12px}
        </style></head><body><main>
        <h1>TV Hub</h1>
        <p>Send something from this device to a TV.</p>
        $msg
        <div class="tvs" id="tvs">$buttons</div>

        <h2>Photo or video</h2>
        <div class="card">
          <input type="file" id="file" accept="image/*,video/*,audio/*">
          <button class="go" type="button" id="send">Show on TV</button>
          <progress id="prog" max="100" value="0" hidden></progress>
          <div id="status"></div>
        </div>

        <h2>A show, movie or video</h2>
        <div class="card">
          <form method="post" action="/play" id="linkform">
            <input type="hidden" name="tv" id="tvfield">
            <input type="url" name="url" placeholder="Paste a Netflix, YouTube, Disney+… link" required>
            <button class="go" type="submit">Play on TV</button>
            <button class="alt" type="submit" formaction="/add">Save to Guide instead</button>
          </form>
          <small>In most streaming apps: Share → Copy link.</small>
        </div>
        </main>
        <script>
          var tv = localStorage.getItem('tv');
          var btns = document.querySelectorAll('.tv');
          function pick(id){ tv = id; localStorage.setItem('tv', id); document.getElementById('tvfield').value = id;
            btns.forEach(function(b){ b.classList.toggle('on', b.dataset.id === id); }); }
          btns.forEach(function(b){ b.onclick = function(){ pick(b.dataset.id); }; });
          var ok = false; btns.forEach(function(b){ if (b.dataset.id === tv) ok = true; });
          if (btns.length) pick(ok ? tv : btns[0].dataset.id);
          document.getElementById('send').onclick = function(){
            var f = document.getElementById('file').files[0];
            var st = document.getElementById('status'), pr = document.getElementById('prog');
            if (!f) { st.textContent = 'Choose a photo or video first.'; return; }
            var x = new XMLHttpRequest();
            x.open('POST', '/upload?tv=' + encodeURIComponent(tv) + '&name=' + encodeURIComponent(f.name) + '&type=' + encodeURIComponent(f.type));
            pr.hidden = false; pr.value = 0; st.textContent = 'Sending…';
            x.upload.onprogress = function(e){ if (e.lengthComputable) pr.value = Math.round(e.loaded * 100 / e.total); };
            x.onload = function(){ pr.hidden = true; st.textContent = x.status === 200 ? 'On the TV now ✓' : 'Couldn\'t send that. Is the TV on?'; };
            x.onerror = function(){ pr.hidden = true; st.textContent = 'Lost connection to TV Hub.'; };
            x.send(f);
          };
        </script>
        </body></html>
        """.trimIndent()
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
