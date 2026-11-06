package dev.daniel.tvhub.core

/** Parses text printed by `dumpsys` and `cmd package` on Fire TV / Google TV. Pure Kotlin so it can be unit-tested. */
object TvParsers {

    const val SEP = "@@TVHUB@@"

    /** One shell call that gathers everything the status card needs. */
    val STATUS_CMD = listOf(
        "dumpsys power | grep -E 'mWakefulness='",
        "echo '$SEP'",
        "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'",
        "echo '$SEP'",
        "dumpsys media_session",
        "echo '$SEP'",
        "pidof tvhub-keys",
    ).joinToString("; ")

    /** Starts the always-on key injector (see KeyServer.kt) from the TV Hub APK installed on the TV. */
    const val KEY_SERVER_CMD =
        "pkill tvhub-keys 2>/dev/null; " +
            "CLASSPATH=\$(pm path dev.daniel.tvhub | head -n 1 | cut -d: -f2) " +
            "exec app_process /system/bin --nice-name=tvhub-keys dev.daniel.tvhub.tv.KeyServer 2>/dev/null"

    /** True/false if the status output says whether the key server is running, null if unknown. */
    fun keyServerRunning(out: String): Boolean? {
        val parts = out.split(SEP)
        if (parts.size < 4) return null
        return parts[3].trim().split(Regex("\\s+")).any { it.toIntOrNull() != null }
    }

    const val RESUMED_CMD = "dumpsys activity activities | grep -E 'ResumedActivity'"

    fun parsePower(out: String): Power? {
        val m = Regex("mWakefulness=(\\w+)").find(out) ?: return null
        return when (m.groupValues[1].lowercase()) {
            "awake" -> Power.ON
            "dreaming" -> Power.SCREENSAVER
            "asleep", "dozing" -> Power.OFF
            else -> null
        }
    }

    private val focusPatterns = listOf(
        Regex("mCurrentFocus=Window\\{[^}]*?\\s([A-Za-z0-9_.]+)/"),
        Regex("mFocusedApp=.*?\\s([A-Za-z0-9_.]+)/"),
        Regex("ResumedActivity[:=].*?\\s([A-Za-z0-9_.]+)/"),
    )

    fun parseForegroundPackage(out: String): String? {
        for (p in focusPatterns) {
            for (line in out.lineSequence()) {
                val m = p.find(line) ?: continue
                val pkg = m.groupValues[1]
                if (pkg.contains('.')) return pkg
            }
        }
        return null
    }

    data class Session(val pkg: String, var state: Int = 0, var title: String? = null, var active: Boolean = false)

    fun parseMediaSessions(out: String): List<Session> {
        val sessions = mutableListOf<Session>()
        var cur: Session? = null
        val pkgRe = Regex("^\\s*package=([A-Za-z0-9_.]+)")
        val stateRe = Regex("state=PlaybackState \\{state=(\\d+)")
        val descRe = Regex("description=(.*)$")
        val activeRe = Regex("^\\s*active=(true|false)")
        for (line in out.lineSequence()) {
            pkgRe.find(line)?.let { cur = Session(it.groupValues[1]).also(sessions::add); return@let }
            val s = cur ?: continue
            activeRe.find(line)?.let { s.active = it.groupValues[1] == "true" }
            stateRe.find(line)?.let { s.state = it.groupValues[1].toIntOrNull() ?: 0 }
            if (line.contains("metadata:")) {
                descRe.find(line)?.let { s.title = cleanTitle(it.groupValues[1]) }
            }
        }
        return sessions
    }

    fun cleanTitle(raw: String): String? {
        val parts = raw.split(", ").map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
        if (parts.isEmpty()) return null
        return parts.take(2).joinToString(" · ")
    }

    fun playbackLabel(state: Int): String? = when (state) {
        3 -> "Playing"
        2 -> "Paused"
        6, 8 -> "Buffering"
        1 -> "Stopped"
        else -> null
    }

    /** Builds what the card shows from the combined STATUS_CMD output. */
    fun parseStatus(out: String, fallbackFocus: String? = null): Pair<Power?, NowPlaying?> {
        val parts = out.split(SEP)
        val power = parsePower(parts.getOrElse(0) { "" })
        val pkg = parseForegroundPackage(parts.getOrElse(1) { "" })
            ?: fallbackFocus?.let { parseForegroundPackage(it) }
        val sessions = parseMediaSessions(parts.getOrElse(2) { "" })
        val session = sessions.firstOrNull { it.pkg == pkg && it.state in setOf(2, 3, 6, 8) }
            ?: sessions.firstOrNull { it.state == 3 }
        val now = if (pkg == null && session == null) null else NowPlaying(
            packageName = pkg ?: session?.pkg,
            appName = (pkg ?: session?.pkg)?.let(AppNames::of),
            title = session?.title,
            playback = session?.let { playbackLabel(it.state) },
        )
        return power to now
    }

    /**
     * Parses `cmd package query-activities` output. Handles the `--brief` form
     * ("  com.foo/.Main") and the long form ("name=com.foo.Main" + "packageName=com.foo").
     */
    fun parseLaunchables(out: String): List<Pair<String, String?>> {
        val result = linkedMapOf<String, String?>()
        val brief = Regex("^\\s*([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\\s*$")
        var pendingName: String? = null
        for (line in out.lineSequence()) {
            brief.find(line)?.let {
                val pkg = it.groupValues[1]
                if (pkg.contains('.') && pkg !in result) result[pkg] = "$pkg/${it.groupValues[2]}"
                return@let
            }
            val t = line.trim()
            if (t.startsWith("name=")) pendingName = t.removePrefix("name=").substringBefore(' ')
            if (t.startsWith("packageName=")) {
                val pkg = t.removePrefix("packageName=").substringBefore(' ')
                if (pkg !in result) result[pkg] = pendingName?.let { "$pkg/$it" }
                pendingName = null
            }
        }
        return result.map { it.key to it.value }
    }

    /** Parses `pm list packages` output. */
    fun parsePackageList(out: String): List<String> =
        out.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toList()

    fun parseVersionCode(out: String): Int? =
        Regex("versionCode=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()

    private val profileHints = Regex(
        "who.?s watching|who is watching|choose (a |your )?profile|select (a |your )?profile|switch profiles?|add profile|manage profiles|edit profiles",
        RegexOption.IGNORE_CASE,
    )

    /** Visible text and descriptions from a `uiautomator dump`. */
    fun screenStrings(xml: String): List<String> =
        Regex("(?:text|content-desc)=\"([^\"]*)\"").findAll(xml).map { unescapeXml(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()

    fun isProfilePicker(xml: String): Boolean = screenStrings(xml).any { profileHints.containsMatchIn(it) }

    fun screenShowsQuery(xml: String, q: String): Boolean = screenStrings(xml).any { it.contains(q.trim(), ignoreCase = true) }

    /** Text currently in the first visible text box, or null if there's no text box on screen. */
    fun searchFieldText(xml: String): String? {
        val node = Regex("<node[^>]*class=\"android\\.widget\\.EditText\"[^>]*>").find(xml)?.value ?: return null
        val text = Regex("\\btext=\"([^\"]*)\"").find(node)?.groupValues?.get(1)?.let(::unescapeXml) ?: return ""
        // A hint like "Search" shows as the text of an empty box.
        val hint = Regex("\\bhint=\"([^\"]*)\"").find(node)?.groupValues?.get(1)
        return if (text == hint || text.equals("search", true)) "" else text
    }

    private fun unescapeXml(s: String) =
        s.replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** Single-quotes a string for the Android shell. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Text for `input text`: spaces must be %s, and the whole thing is quoted. */
    fun inputText(s: String): String = q(s.replace("%", "\\%").replace(" ", "%s"))

    /** `am start` reports failures on stdout with exit code 0, so look at the words. */
    fun amFailed(out: String): Boolean {
        val o = out.lowercase()
        return o.contains("error:") || o.contains("unable to resolve") || o.contains("exception") ||
            o.contains("does not exist") || o.contains("no activities found")
    }
}
