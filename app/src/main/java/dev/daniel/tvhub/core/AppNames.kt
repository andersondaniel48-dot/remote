package dev.daniel.tvhub.core

/** Turns Android package names into names a person recognises. */
object AppNames {
    private val known = mapOf(
        "com.netflix.ninja" to "Netflix",
        "com.netflix.mediaclient" to "Netflix",
        "com.amazon.firebat" to "Prime Video",
        "com.amazon.avod.thirdpartyclient" to "Prime Video",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.google.android.youtube.tv" to "YouTube",
        "com.amazon.firetv.youtube" to "YouTube",
        "com.google.android.youtube.tvkids" to "YouTube Kids",
        "com.amazon.firetv.youtube.kids" to "YouTube Kids",
        "com.google.android.youtube.tvmusic" to "YouTube Music",
        "com.google.android.youtube.tvunplugged" to "YouTube TV",
        "com.amazon.firetv.youtube.tv" to "YouTube TV",
        "com.disney.disneyplus" to "Disney+",
        "com.hulu.plus" to "Hulu",
        "com.hulu.livingroomplus" to "Hulu",
        "com.wbd.stream" to "Max",
        "com.hbo.hbonow" to "Max",
        "com.spotify.tv.android" to "Spotify",
        "com.plexapp.android" to "Plex",
        "com.peacocktv.peacockandroid" to "Peacock",
        "com.cbs.ott" to "Paramount+",
        "com.apple.atve.amazon.appletv" to "Apple TV",
        "com.apple.atve.androidtv.appletv" to "Apple TV",
        "tv.pluto.android" to "Pluto TV",
        "com.sling" to "Sling TV",
        "tv.twitch.android.app" to "Twitch",
        "tv.twitch.android.viewer" to "Twitch",
        "com.crunchyroll.crunchyroid" to "Crunchyroll",
        "com.philo.philo" to "Philo",
        "org.videolan.vlc" to "VLC",
        "org.xbmc.kodi" to "Kodi",
        "com.amazon.tv.launcher" to "Fire TV Home",
        "com.google.android.apps.tv.launcherx" to "Google TV Home",
        "com.google.android.tvlauncher" to "Android TV Home",
        "com.android.tv.settings" to "Settings",
        "com.amazon.tv.settings.v2" to "Settings",
        "com.amazon.tv.settings" to "Settings",
        "com.amazon.cloud9" to "Silk Browser",
        "com.amazon.venezia" to "Appstore",
        "com.android.vending" to "Play Store",
        "com.google.android.katniss" to "Google Assistant",
        "com.amazon.vizzini" to "Alexa",
        "com.amazon.tv.screensaver" to "Screensaver",
        "com.amazon.bueller.photos" to "Amazon Photos",
        "com.google.android.backdrop" to "Ambient mode",
        "dev.daniel.tvhub" to "TV Hub (casting)",
    )

    private val generic = setOf(
        "com", "org", "net", "tv", "android", "app", "apps", "mobile", "amazon", "google", "firetv",
        "ott", "androidtv", "livingroom", "client", "player", "main", "atv", "media", "leanback", "www", "us",
    )

    fun of(packageName: String): String {
        known[packageName]?.let { return it }
        val parts = packageName.split('.').filter { it.isNotBlank() }
        val pick = parts.drop(1).firstOrNull { it.lowercase() !in generic }
            ?: parts.lastOrNull() ?: packageName
        return pick.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private val popular = listOf(
        "com.netflix.ninja", "com.netflix.mediaclient",
        "com.google.android.youtube.tv", "com.amazon.firetv.youtube",
        "com.amazon.firebat", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient",
        "com.disney.disneyplus", "com.wbd.stream", "com.hbo.hbonow", "com.hulu.livingroomplus", "com.hulu.plus",
        "com.google.android.youtube.tvunplugged", "com.amazon.firetv.youtube.tv",
        "com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv", "com.peacocktv.peacockandroid", "com.cbs.ott",
        "com.google.android.youtube.tvkids", "com.amazon.firetv.youtube.kids", "com.spotify.tv.android",
        "com.plexapp.android", "tv.pluto.android", "tv.twitch.android.app", "com.crunchyroll.crunchyroid",
    )
    private val utility = setOf(
        "com.android.tv.settings", "com.amazon.tv.settings.v2", "com.amazon.tv.settings", "com.amazon.venezia",
        "com.android.vending", "com.google.android.katniss", "com.amazon.vizzini", "com.android.settings",
        "com.google.android.tv.remote.service", "com.android.documentsui", "com.amazon.tv.screensaver",
    )

    /** Lower = earlier in the grid when you haven't used or pinned anything yet. */
    fun rank(packageName: String): Int {
        val i = popular.indexOf(packageName)
        return when {
            i >= 0 -> i
            packageName in utility -> 10_000
            else -> 1_000
        }
    }

    fun isUtility(packageName: String) = packageName in utility

    fun isHome(packageName: String?) = packageName in setOf(
        "com.amazon.tv.launcher", "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
    )
}
