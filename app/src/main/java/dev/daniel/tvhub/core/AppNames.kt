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

    fun isHome(packageName: String?) = packageName in setOf(
        "com.amazon.tv.launcher", "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
    )
}
