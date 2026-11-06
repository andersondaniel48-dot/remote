package dev.daniel.tvhub.core

import android.content.Context
import dev.daniel.tvhub.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Everything the app does to the TVs goes through here. */
class TvHub(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = TvStore(ctx)
    private val keyPair by lazy { AdbKeys.load(ctx) }
    private val conns = ConcurrentHashMap<String, TvConnection>()
    private val pollers = ConcurrentHashMap<String, Job>()
    private val pokes = ConcurrentHashMap<String, MutableSharedFlow<Unit>>()

    private val _tvs = MutableStateFlow(store.loadTvs().map { TvState(it) })
    val tvs: StateFlow<List<TvState>> = _tvs.asStateFlow()

    private val _apps = MutableStateFlow<Map<String, AppsState>>(emptyMap())
    val apps: StateFlow<Map<String, AppsState>> = _apps.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    private val _recentLinks = MutableStateFlow(store.recentLinks())
    val recentLinks: StateFlow<List<String>> = _recentLinks.asStateFlow()

    val server = MediaServer()

    @Volatile private var running = false

    // ---------- lifecycle ----------

    fun start() {
        if (running) return
        running = true
        server.start()
        _tvs.value.forEach { startPoller(it.config.id) }
    }

    fun stop() {
        running = false
        pollers.values.forEach { it.cancel() }
        pollers.clear()
    }

    private fun startPoller(id: String) {
        if (!running || pollers[id]?.isActive == true) return
        val poke = pokes.getOrPut(id) { MutableSharedFlow(extraBufferCapacity = 1) }
        pollers[id] = scope.launch {
            while (isActive) {
                poll(id)
                // Wait 5 s, or less if an action asked for a quick refresh.
                val wait = launch { delay(5_000) }
                val listener = launch { poke.collect { wait.cancel() } }
                wait.join()
                listener.cancel()
            }
        }
    }

    /** Ask for a status refresh soon (after a button press, etc). */
    fun refresh(id: String, afterMs: Long = 700) {
        scope.launch { delay(afterMs); pokes[id]?.tryEmit(Unit) }
    }

    // ---------- TV list ----------

    fun addTv(name: String, host: String, port: Int, kind: TvKind): String {
        val cfg = TvConfig(UUID.randomUUID().toString(), name.trim().ifEmpty { kind.label }, host.trim(), port, kind)
        _tvs.update { it + TvState(cfg, firstConnectStartedAt = System.currentTimeMillis()) }
        persist()
        startPoller(cfg.id)
        return cfg.id
    }

    fun updateTv(cfg: TvConfig) {
        val old = state(cfg.id)?.config
        _tvs.update { list -> list.map { if (it.config.id == cfg.id) TvState(cfg, firstConnectStartedAt = System.currentTimeMillis()) else it } }
        if (old != null && (old.host != cfg.host || old.port != cfg.port)) {
            conns.remove(cfg.id)?.close()
        }
        persist()
        refresh(cfg.id, 0)
    }

    fun removeTv(id: String) {
        store.forgetTv(id)
        runCatching { File(ctx.filesDir, "art/$id").deleteRecursively() }
        pollers.remove(id)?.cancel()
        conns.remove(id)?.close()
        _tvs.update { list -> list.filterNot { it.config.id == id } }
        _apps.update { it - id }
        persist()
    }

    fun moveTv(id: String, delta: Int) {
        _tvs.update { list ->
            val i = list.indexOfFirst { it.config.id == id }
            val j = i + delta
            if (i < 0 || j !in list.indices) list
            else list.toMutableList().apply { add(j, removeAt(i)) }
        }
        persist()
    }

    private fun persist() = store.saveTvs(_tvs.value.map { it.config })

    fun state(id: String): TvState? = _tvs.value.firstOrNull { it.config.id == id }

    private fun set(id: String, f: (TvState) -> TvState) {
        _tvs.update { list -> list.map { if (it.config.id == id) f(it) else it } }
    }

    private fun conn(id: String): TvConnection? {
        val cfg = state(id)?.config ?: return null
        val existing = conns[id]
        if (existing != null && existing.config.host == cfg.host && existing.config.port == cfg.port) return existing
        existing?.close()
        return TvConnection(cfg) { keyPair }.also { conns[id] = it }
    }

    // ---------- status ----------

    private suspend fun poll(id: String) {
        val c = conn(id) ?: return
        val wasOnline = state(id)?.conn == Conn.ONLINE
        if (!c.isConnected && !wasOnline) set(id) { it.copy(conn = Conn.CONNECTING) }
        try {
            val out = c.shell(TvParsers.STATUS_CMD)
            var (power, now) = TvParsers.parseStatus(out)
            if (now?.packageName == null) {
                val resumed = runCatching { c.shell(TvParsers.RESUMED_CMD) }.getOrNull()
                if (resumed != null) {
                    val (_, n2) = TvParsers.parseStatus(TvParsers.SEP + resumed + TvParsers.SEP + (out.split(TvParsers.SEP).getOrNull(2) ?: ""))
                    if (n2 != null) now = n2
                }
            }
            set(id) { it.copy(conn = Conn.ONLINE, power = power, now = now, error = null) }
            if (!wasOnline || state(id)?.receiverVersion == null) fetchDetails(id, c)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            set(id) { it.copy(conn = Conn.OFFLINE, power = null, now = null, error = friendly(t)) }
        }
    }

    private suspend fun fetchDetails(id: String, c: TvConnection) {
        runCatching {
            val model = c.shell("settings get global device_name; getprop ro.product.model")
                .lines().map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.distinct().joinToString(" · ")
            val ver = TvParsers.parseVersionCode(c.shell("dumpsys package ${BuildConfig.APPLICATION_ID} | grep versionCode")) ?: -1
            set(id) { it.copy(model = model.ifEmpty { null }, receiverVersion = ver) }
        }
    }

    private fun friendly(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.toList()
        return when {
            chain.any { it is ConnectException || it is NoRouteToHostException } ->
                "Can't reach the TV. Is it on Wi‑Fi with ADB debugging turned on?"
            chain.any { it is SocketTimeoutException } ->
                "The TV didn't answer. If it's showing \"Allow USB debugging?\", pick Always allow → OK."
            else -> t.message?.take(140) ?: t.javaClass.simpleName
        }
    }

    /** Runs a command on the TV and reports problems as a snackbar message. */
    private fun act(id: String, refreshAfter: Boolean = true, block: suspend (TvConnection) -> Unit) {
        val c = conn(id) ?: return
        scope.launch {
            try {
                block(c)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _messages.tryEmit("${state(id)?.config?.name ?: "TV"}: ${friendly(t)}")
                if (t is java.io.IOException && t.message?.startsWith("Install failed") != true) {
                    set(id) { it.copy(conn = Conn.OFFLINE, error = friendly(t)) }
                }
            }
            if (refreshAfter) refresh(id)
        }
    }

    private fun say(msg: String) { _messages.tryEmit(msg) }

    // ---------- remote ----------

    private val keyQueues = ConcurrentHashMap<String, Channel<Int>>()

    /**
     * Sends a key press. Presses that arrive close together (fast touchpad swipes) are batched
     * into one `input keyevent a b c` call, which is much quicker than one call per press.
     */
    fun key(id: String, code: Int) {
        val ch = keyQueues.getOrPut(id) { Channel<Int>(Channel.UNLIMITED).also { startKeyPump(id, it) } }
        ch.trySend(code)
    }

    private fun startKeyPump(id: String, ch: Channel<Int>) = scope.launch {
        for (first in ch) {
            val batch = mutableListOf(first)
            delay(35)
            while (batch.size < 12) batch += ch.tryReceive().getOrNull() ?: break
            val c = conn(id) ?: continue
            try {
                c.shell("input keyevent " + batch.joinToString(" "))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _messages.tryEmit("${state(id)?.config?.name ?: "TV"}: ${friendly(t)}")
            }
            refresh(id)
        }
    }

    fun text(id: String, s: String) {
        if (s.isEmpty()) return
        act(id, refreshAfter = false) { it.shell("input text ${TvParsers.inputText(s)}") }
    }

    fun power(id: String) {
        val off = state(id)?.power == Power.OFF
        act(id) { it.key(if (off) Keys.WAKEUP else Keys.SLEEP) }
    }

    // ---------- apps ----------

    private val _pins = MutableStateFlow(_tvs.value.associate { it.config.id to store.pins(it.config.id) })
    val pins: StateFlow<Map<String, Set<String>>> = _pins.asStateFlow()

    private val _usage = MutableStateFlow(_tvs.value.associate { it.config.id to store.usage(it.config.id) })
    val usageByTv: StateFlow<Map<String, Map<String, Int>>> = _usage.asStateFlow()

    private val _recentSearches = MutableStateFlow(store.recentSearches())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    init {
        // Show cached app tiles instantly on start.
        _apps.value = _tvs.value.associate { t ->
            val id = t.config.id
            id to AppsState(apps = store.loadCatalog(id), hasArtwork = store.catalogHasArtwork(id))
        }.filterValues { it.apps.isNotEmpty() }
    }

    fun togglePin(id: String, pkg: String) {
        val cur = _pins.value[id] ?: emptySet()
        val next = if (pkg in cur) cur - pkg else cur + pkg
        store.setPins(id, next)
        _pins.update { it + (id to next) }
    }

    /** Pinned first, then most used, then popular streaming apps, then A–Z. Settings-type apps last. */
    fun ordered(id: String, apps: List<TvApp>, pins: Set<String>, usage: Map<String, Int>): List<TvApp> =
        apps.sortedWith(
            compareBy<TvApp>(
                { if (it.packageName in pins) 0 else 1 },
                { if (AppNames.isUtility(it.packageName)) 1 else 0 },
                { -(usage[it.packageName] ?: 0) },
                { AppNames.rank(it.packageName) },
                { it.name.lowercase() },
            ),
        )

    /** Refreshes the app list if it's missing, stale (a day old), or the helper was just installed. */
    fun ensureApps(id: String) {
        val st = _apps.value[id]
        val stale = store.catalogAge(id) > 24 * 3600_000L
        val upgrade = st != null && !st.hasArtwork && receiverReady(id)
        if (st == null || stale || upgrade) loadApps(id)
    }

    fun loadApps(id: String) {
        val c = conn(id) ?: return
        if (_apps.value[id]?.loading == true) return
        _apps.update { it + (id to (it[id] ?: AppsState()).copy(loading = true, error = null)) }
        scope.launch {
            try {
                val withArt = if (receiverReady(id)) runCatching { fetchCatalog(id, c) }.getOrNull() else null
                val apps = withArt ?: listFromShell(c)
                val filtered = apps.filter { it.packageName != BuildConfig.APPLICATION_ID && !AppNames.isHome(it.packageName) }
                store.saveCatalog(id, filtered, withArt != null)
                _apps.update { it + (id to AppsState(loading = false, apps = filtered, hasArtwork = withArt != null)) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _apps.update { it + (id to (it[id] ?: AppsState()).copy(loading = false, error = friendly(t))) }
            }
        }
    }

    private suspend fun listFromShell(c: TvConnection): List<TvApp> {
        val found = linkedMapOf<String, String?>()
        for (cat in listOf("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER")) {
            val out = runCatching {
                c.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c $cat")
            }.getOrDefault("")
            var parsed = TvParsers.parseLaunchables(out)
            if (parsed.isEmpty()) {
                parsed = TvParsers.parseLaunchables(c.shell("cmd package query-activities -a android.intent.action.MAIN -c $cat"))
            }
            parsed.forEach { (pkg, comp) -> if (pkg !in found) found[pkg] = comp }
        }
        if (found.isEmpty()) {
            TvParsers.parsePackageList(c.shell("pm list packages -3")).forEach { found[it] = null }
        }
        return found.map { (pkg, comp) -> TvApp(pkg, comp, AppNames.of(pkg)) }
    }

    /** Asks the TV Hub helper on the TV for real app names + artwork, then saves the images on the phone. */
    private suspend fun fetchCatalog(id: String, c: TvConnection): List<TvApp>? {
        val pkg = BuildConfig.APPLICATION_ID
        val ext = "/sdcard/Android/data/$pkg/files/catalog.json"
        c.shell("rm -f $ext; run-as $pkg rm -f files/catalog.json 2>/dev/null; " +
            "am broadcast -n $pkg/$pkg.tv.AppCatalogReceiver -f 0x10000020")
        repeat(30) {
            delay(600)
            val t = c.shell("cat $ext 2>/dev/null || run-as $pkg cat files/catalog.json 2>/dev/null").trim()
            if (t.startsWith("[") && t.endsWith("]")) return saveArtwork(id, t)
        }
        return null
    }

    private fun saveArtwork(id: String, json: String): List<TvApp> {
        val dir = File(ctx.filesDir, "art/$id")
        dir.deleteRecursively()
        dir.mkdirs()
        val stamp = System.currentTimeMillis()
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val pkg = o.getString("pkg")
            val safe = pkg.replace(Regex("[^A-Za-z0-9._]"), "_")
            fun img(key: String, suffix: String): String? {
                val b64 = o.optString(key).takeIf { it.isNotEmpty() && it != "null" } ?: return null
                val f = File(dir, "${safe}_${stamp}_$suffix.webp")
                return runCatching { f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)); f.absolutePath }.getOrNull()
            }
            TvApp(
                packageName = pkg,
                component = o.optString("component").ifEmpty { null },
                name = o.optString("label").ifEmpty { AppNames.of(pkg) },
                banner = img("banner", "b"),
                icon = img("icon", "i"),
            )
        }
    }

    fun launchApp(id: String, app: TvApp) {
        val next = store.bumpUsage(id, app.packageName)
        _usage.update { it + (id to next) }
        act(id) { c ->
            startApp(c, app)
            say("Opening ${app.name} on ${state(id)?.config?.name}")
        }
    }

    private suspend fun startApp(c: TvConnection, app: TvApp) {
        val viaComponent = app.component?.let { c.shell("am start -n ${TvParsers.q(it)}") }
        if (viaComponent == null || TvParsers.amFailed(viaComponent)) {
            val out = c.shell("monkey -p ${TvParsers.q(app.packageName)} -c android.intent.category.LEANBACK_LAUNCHER 1")
            if (out.contains("No activities found")) {
                c.shell("monkey -p ${TvParsers.q(app.packageName)} -c android.intent.category.LAUNCHER 1")
            }
        }
    }

    // ---------- search ----------

    fun clearSearches() { store.clearRecentSearches(); _recentSearches.value = emptyList() }

    fun looksLikeLink(s: String) = normalizeUrl(s) != null && (s.contains("://") || Regex("^[\\w-]+(\\.[\\w-]+)+(/\\S*)?$").matches(s.trim()))

    /**
     * Searches on the TV. With no [app], uses the TV's own search (Google TV shows results for the
     * query directly). With an app, opens it, presses the Search key and types the query.
     */
    fun search(id: String, query: String, app: TvApp?) {
        val q = query.trim()
        if (q.isEmpty()) return
        store.addRecentSearch(q)
        _recentSearches.value = store.recentSearches()
        val name = state(id)?.config?.name ?: "TV"
        app?.let { _usage.update { u -> u + (id to store.bumpUsage(id, it.packageName)) } }
        act(id) { c ->
            if (app == null) {
                val out = c.shell("am start -a android.search.action.GLOBAL_SEARCH --es query ${TvParsers.q(q)}")
                if (!TvParsers.amFailed(out)) { say("Searching “$q” on $name"); return@act }
                c.key(Keys.SEARCH)
                delay(1500)
            } else {
                val fg = TvParsers.parseForegroundPackage(c.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"))
                if (fg != app.packageName) {
                    startApp(c, app)
                    // Wait for the app to come to the front, then give it a moment to finish loading.
                    for (i in 0 until 14) {
                        delay(600)
                        val now = TvParsers.parseForegroundPackage(c.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"))
                        if (now == app.packageName) break
                    }
                    delay(1800)
                }
                c.key(Keys.SEARCH)
                delay(1300)
            }
            c.shell("input text ${TvParsers.inputText(q)}")
            delay(250)
            c.key(Keys.ENTER)
            say(if (app == null) "Searching “$q” on $name" else "Searching ${app.name} for “$q”")
        }
    }

    // ---------- links ----------

    fun openLink(id: String, rawUrl: String) {
        val url = normalizeUrl(rawUrl) ?: run { say("That doesn't look like a link"); return }
        store.addRecentLink(url)
        _recentLinks.value = store.recentLinks()
        val kind = state(id)?.config?.kind
        act(id) { c ->
            val out = c.shell("am start -a android.intent.action.VIEW -d ${TvParsers.q(url)}")
            if (!TvParsers.amFailed(out)) {
                say("Sent to ${state(id)?.config?.name}")
                return@act
            }
            // Fire TV: fall back to the Silk browser.
            if (kind == TvKind.FIRE_TV) {
                val silk = c.shell("am start -a android.intent.action.VIEW -d ${TvParsers.q(url)} -p com.amazon.cloud9")
                if (!TvParsers.amFailed(silk)) { say("Opened in Silk on ${state(id)?.config?.name}"); return@act }
            }
            say("${state(id)?.config?.name} has no app that opens this kind of link. Install a browser on it, or send a YouTube link.")
        }
    }

    fun forgetLink(url: String) {
        store.removeRecentLink(url)
        _recentLinks.value = store.recentLinks()
    }

    private fun normalizeUrl(s: String): String? {
        val t = s.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        return when {
            Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t) -> t
            t.contains('.') -> "https://$t"
            else -> null
        }
    }

    // ---------- casting phone media ----------

    private val apkFile: File get() = File(ctx.applicationInfo.sourceDir)

    fun receiverReady(id: String): Boolean = (state(id)?.receiverVersion ?: -1) >= BuildConfig.VERSION_CODE

    fun installReceiver(id: String, then: (suspend (TvConnection) -> Unit)? = null) {
        val name = state(id)?.config?.name ?: "TV"
        set(id) { it.copy(busy = "Setting up casting…") }
        act(id) { c ->
            try {
                say("Installing TV Hub's player on $name (one time, about 30 s)…")
                c.install(apkFile)
                val ver = TvParsers.parseVersionCode(c.shell("dumpsys package ${BuildConfig.APPLICATION_ID} | grep versionCode")) ?: -1
                set(id) { it.copy(receiverVersion = ver) }
                if (ver < BuildConfig.VERSION_CODE) error("Install didn't finish on $name")
                say("Casting is ready on $name")
                then?.invoke(c)
            } finally {
                set(id) { it.copy(busy = null) }
            }
        }
    }

    fun cast(id: String, item: PhoneMediaItem) {
        val (ip, _) = NetUtil.phoneIpv4(ctx) ?: run { say("The phone isn't on Wi‑Fi"); return }
        server.start()
        val path = server.register(PhoneMedia.asSource(ctx, item))
        val url = "http://$ip:${server.port}$path"
        val send: suspend (TvConnection) -> Unit = { c ->
            val out = c.shell(
                "am start -n ${BuildConfig.APPLICATION_ID}/${BuildConfig.APPLICATION_ID}.ViewerActivity " +
                    "-a android.intent.action.VIEW -d ${TvParsers.q(url)} -t ${TvParsers.q(item.mime)} " +
                    "--es title ${TvParsers.q(item.name)}",
            )
            if (TvParsers.amFailed(out)) error(out.lines().firstOrNull { it.contains("Error", true) } ?: "Couldn't start the player")
            say("Showing ${item.name} on ${state(id)?.config?.name}")
        }
        if (receiverReady(id)) act(id, block = send) else installReceiver(id, send)
    }

    fun stopCast(id: String) = act(id) { it.shell("am force-stop ${BuildConfig.APPLICATION_ID}") }

    suspend fun scan(): List<String> = NetUtil.scanForAdb(ctx)
}

object Keys {
    const val HOME = 3
    const val BACK = 4
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val CENTER = 23
    const val VOL_UP = 24
    const val VOL_DOWN = 25
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val SEARCH = 84
    const val PLAY_PAUSE = 85
    const val REWIND = 89
    const val FAST_FORWARD = 90
    const val MUTE = 164
    const val SETTINGS = 176
    const val SLEEP = 223
    const val WAKEUP = 224
}
