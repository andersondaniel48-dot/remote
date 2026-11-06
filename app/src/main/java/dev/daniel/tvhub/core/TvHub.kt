package dev.daniel.tvhub.core

import android.content.Context
import dev.daniel.tvhub.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    fun key(id: String, code: Int) = act(id) { it.key(code) }

    fun text(id: String, s: String) {
        if (s.isEmpty()) return
        act(id, refreshAfter = false) { it.shell("input text ${TvParsers.inputText(s)}") }
    }

    fun power(id: String) {
        val off = state(id)?.power == Power.OFF
        act(id) { it.key(if (off) Keys.WAKEUP else Keys.SLEEP) }
    }

    // ---------- apps ----------

    fun loadApps(id: String) {
        val c = conn(id) ?: return
        _apps.update { it + (id to (it[id] ?: AppsState()).copy(loading = true, error = null)) }
        scope.launch {
            try {
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
                val apps = found
                    .filterKeys { it != BuildConfig.APPLICATION_ID && !AppNames.isHome(it) }
                    .map { (pkg, comp) -> TvApp(pkg, comp, AppNames.of(pkg)) }
                    .sortedBy { it.name.lowercase() }
                _apps.update { it + (id to AppsState(loading = false, apps = apps)) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _apps.update { it + (id to (it[id] ?: AppsState()).copy(loading = false, error = friendly(t))) }
            }
        }
    }

    fun launchApp(id: String, app: TvApp) = act(id) { c ->
        val viaComponent = app.component?.let { c.shell("am start -n ${TvParsers.q(it)}") }
        if (viaComponent == null || TvParsers.amFailed(viaComponent)) {
            val out = c.shell("monkey -p ${TvParsers.q(app.packageName)} -c android.intent.category.LEANBACK_LAUNCHER 1")
            if (out.contains("No activities found")) {
                c.shell("monkey -p ${TvParsers.q(app.packageName)} -c android.intent.category.LAUNCHER 1")
            }
        }
        say("Opening ${app.name} on ${state(id)?.config?.name}")
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
    const val PLAY_PAUSE = 85
    const val REWIND = 89
    const val FAST_FORWARD = 90
    const val MUTE = 164
    const val SETTINGS = 176
    const val SLEEP = 223
    const val WAKEUP = 224
}
