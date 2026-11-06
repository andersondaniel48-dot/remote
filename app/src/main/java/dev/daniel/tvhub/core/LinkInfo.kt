package dev.daniel.tvhub.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Looks up a title and picture for a saved link, using public page info (oEmbed / Open Graph). */
object LinkInfo {
    data class Info(val title: String?, val image: String?)

    fun fetch(url: String): Info {
        if (DeepLinks.serviceFor(url)?.name == "YouTube") {
            runCatching {
                val body = get("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"))
                val o = JSONObject(body)
                val id = DeepLinks.youtubeId(url)
                return Info(
                    o.optString("title").ifEmpty { null },
                    id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" } ?: o.optString("thumbnail_url").ifEmpty { null },
                )
            }
        }
        return runCatching { fromHtml(get(url, limit = 600_000)) }.getOrDefault(Info(null, null))
    }

    fun fromHtml(html: String): Info {
        fun meta(prop: String): String? {
            val a = Regex("<meta[^>]+(?:property|name)=[\"']$prop[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
            val b = Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"']$prop[\"']", RegexOption.IGNORE_CASE).find(html)
            return (a ?: b)?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifEmpty { null }
        }
        val title = meta("og:title") ?: meta("twitter:title")
            ?: Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()
        val image = meta("og:image") ?: meta("twitter:image")
        return Info(title?.let(::tidyTitle), image)
    }

    /** "Stranger Things | Netflix Official Site" → "Stranger Things" */
    fun tidyTitle(t: String): String {
        var s = t
        for (sep in listOf(" | ", " - Netflix", " - Watch on", " - YouTube")) {
            val i = s.indexOf(sep)
            if (i > 2) s = s.substring(0, i)
        }
        return s.removePrefix("Watch ").trim()
    }

    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private fun get(url: String, limit: Int = 200_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        return c.inputStream.use { inp ->
            val buf = ByteArray(limit)
            var n = 0
            while (n < limit) {
                val r = inp.read(buf, n, limit - n)
                if (r < 0) break
                n += r
            }
            String(buf, 0, n, Charsets.UTF_8)
        }
    }
}
