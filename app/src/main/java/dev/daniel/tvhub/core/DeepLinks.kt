package dev.daniel.tvhub.core

/**
 * Turns a share link (Netflix, YouTube, Disney+, Prime Video, Max…) into the `am start` commands
 * that open that exact show or video on a TV. Commands are tried in order until one works.
 */
object DeepLinks {
    data class Service(val name: String, val hosts: List<String>, val packages: List<String>)

    val services = listOf(
        Service("YouTube", listOf("youtube.com", "youtu.be", "m.youtube.com"),
            listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube")),
        Service("Netflix", listOf("netflix.com"), listOf("com.netflix.ninja", "com.netflix.mediaclient")),
        Service("Prime Video", listOf("primevideo.com", "amazon.com", "watch.amazon.com"),
            listOf("com.amazon.firebat", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")),
        Service("Disney+", listOf("disneyplus.com"), listOf("com.disney.disneyplus")),
        Service("Max", listOf("max.com", "play.max.com", "hbomax.com"), listOf("com.wbd.stream", "com.hbo.hbonow")),
        Service("Hulu", listOf("hulu.com"), listOf("com.hulu.livingroomplus", "com.hulu.plus")),
        Service("Apple TV", listOf("tv.apple.com"), listOf("com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv")),
        Service("Peacock", listOf("peacocktv.com"), listOf("com.peacocktv.peacockandroid")),
        Service("Paramount+", listOf("paramountplus.com"), listOf("com.cbs.ott")),
        Service("Spotify", listOf("open.spotify.com", "spotify.com"), listOf("com.spotify.tv.android")),
        Service("Twitch", listOf("twitch.tv"), listOf("tv.twitch.android.app", "tv.twitch.android.viewer")),
        Service("Pluto TV", listOf("pluto.tv"), listOf("tv.pluto.android")),
        Service("Crunchyroll", listOf("crunchyroll.com"), listOf("com.crunchyroll.crunchyroid")),
        Service("Plex", listOf("app.plex.tv", "watch.plex.tv"), listOf("com.plexapp.android")),
    )

    fun host(url: String): String =
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#:]+)").find(url)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.") ?: ""

    fun serviceFor(url: String): Service? {
        val h = host(url)
        return services.firstOrNull { s -> s.hosts.any { h == it || h.endsWith(".$it") } }
    }

    fun youtubeId(url: String): String? {
        val patterns = listOf(
            Regex("youtu\\.be/([A-Za-z0-9_-]{6,})"),
            Regex("[?&]v=([A-Za-z0-9_-]{6,})"),
            Regex("youtube\\.com/(?:shorts|live|embed)/([A-Za-z0-9_-]{6,})"),
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

    fun netflixId(url: String): String? =
        Regex("netflix\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:title|watch)/(\\d+)").find(url)?.groupValues?.get(1)

    /** Cleans up a link before saving it (YouTube short links → full watch links, etc). */
    fun canonical(url: String): String {
        val t = url.trim()
        youtubeId(t)?.let { id ->
            if (t.contains("youtu.be") || t.contains("/shorts/") || t.contains("/live/")) return "https://www.youtube.com/watch?v=$id"
        }
        return t
    }

    /** A link for what an app is playing, from the media id / uri it reports (null if we can't tell). */
    fun fromMedia(pkg: String, mediaId: String?, mediaUri: String?): String? {
        mediaUri?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { return canonical(it) }
        val id = mediaId?.trim().orEmpty()
        val svc = services.firstOrNull { pkg in it.packages }?.name
        return when {
            svc == "YouTube" && Regex("^[A-Za-z0-9_-]{11}$").matches(id) -> "https://www.youtube.com/watch?v=$id"
            svc == "Netflix" && Regex("^\\d{6,10}$").matches(id) -> "https://www.netflix.com/watch/$id"
            id.startsWith("http://") || id.startsWith("https://") -> canonical(id)
            else -> null
        }
    }

    /** A Guide "link" that means: open [pkg] and search it for [query]. */
    fun searchLink(pkg: String, query: String): String =
        "tvhub://search?pkg=" + java.net.URLEncoder.encode(pkg, "UTF-8") + "&q=" + java.net.URLEncoder.encode(query, "UTF-8")

    /** (package, query) if [url] is a [searchLink]. */
    fun parseSearchLink(url: String): Pair<String, String>? {
        if (!url.startsWith("tvhub://search?")) return null
        val params = url.substringAfter('?').split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        val pkg = params["pkg"] ?: return null
        return pkg to (params["q"] ?: return null)
    }

    fun commands(url: String, installed: Set<String>): List<String> {
        val q = TvParsers::q
        val svc = serviceFor(url)
        val pkgs = svc?.packages?.filter { it in installed }.orEmpty()
        val out = mutableListOf<String>()
        when (svc?.name) {
            "Netflix" -> {
                val id = netflixId(url)
                for (p in pkgs) {
                    if (id != null) {
                        out += "am start -n $p/.MainActivity -a android.intent.action.VIEW " +
                            "-d ${q("https://www.netflix.com/watch/$id")} -f 0x10808000 -e source 30"
                        out += "am start -a android.intent.action.VIEW -d ${q("https://www.netflix.com/title/$id")} -p $p"
                    }
                }
            }
            "YouTube" -> {
                val id = youtubeId(url)
                val target = if (id != null) "https://www.youtube.com/watch?v=$id" else url
                for (p in pkgs) out += "am start -a android.intent.action.VIEW -d ${q(target)} -p $p"
            }
            else -> for (p in pkgs) out += "am start -a android.intent.action.VIEW -d ${q(url)} -p $p"
        }
        // Let the TV pick whichever app claims the link.
        out += "am start -a android.intent.action.VIEW -d ${q(url)}"
        return out
    }
}
