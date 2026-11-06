package dev.daniel.tvhub.core

enum class TvKind(val label: String) { FIRE_TV("Fire TV"), GOOGLE_TV("Google TV") }

data class TvConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 5555,
    val kind: TvKind,
)

enum class Conn { CONNECTING, ONLINE, OFFLINE }

/** "On", "Screensaver", "Off" or null when unknown. */
enum class Power(val label: String) { ON("On"), SCREENSAVER("Screensaver"), OFF("Off") }

data class NowPlaying(
    val packageName: String?,
    val appName: String?,
    val title: String?,
    val playback: String?, // "Playing", "Paused", "Buffering", "Stopped" or null
)

data class TvState(
    val config: TvConfig,
    val conn: Conn = Conn.CONNECTING,
    val power: Power? = null,
    val now: NowPlaying? = null,
    val error: String? = null,
    val model: String? = null,
    /** Version code of TV Hub installed on the TV (for casting). -1 = not installed, null = unknown. */
    val receiverVersion: Int? = null,
    /** Text of a long-running job on this TV, e.g. "Installing…". */
    val busy: String? = null,
    val firstConnectStartedAt: Long = 0L,
)

data class TvApp(
    val packageName: String,
    val component: String?,
    val name: String,
    /** Local file paths of the TV's own artwork for this app (from the TV Hub helper), if any. */
    val banner: String? = null,
    val icon: String? = null,
)

data class AppsState(val loading: Boolean = false, val apps: List<TvApp> = emptyList(), val error: String? = null, val hasArtwork: Boolean = false)
