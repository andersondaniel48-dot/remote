package dev.daniel.tvhub.core

import dadb.AdbKeyPair
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One network-adb connection to one TV. Commands run one at a time, in order.
 *
 * Key presses and typing go through a small always-on "key server" on the TV when the TV Hub
 * helper is installed, which makes them near-instant; otherwise they fall back to `input`.
 */
class TvConnection(val config: TvConfig, private val keyPair: () -> AdbKeyPair) {
    private val mutex = Mutex()
    @Volatile private var dadb: Dadb? = null
    @Volatile private var keyStream: AdbShellStream? = null
    @Volatile private var keyServerFailedAt = 0L

    val isConnected get() = dadb != null
    val hasKeyServer get() = keyStream != null

    suspend fun <T> use(block: (Dadb) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val d = dadb ?: Dadb.create(
                config.host, config.port, keyPair(),
                connectTimeout = 3_000,
                // Long enough for you to walk over and press "Allow" on the TV the first time.
                socketTimeout = 45_000,
            ).also { dadb = it }
            try {
                block(d)
            } catch (t: Throwable) {
                dropAll(d)
                throw t
            }
        }
    }

    private fun dropAll(d: Dadb? = dadb) {
        runCatching { keyStream?.close() }
        keyStream = null
        runCatching { d?.close() }
        dadb = null
    }

    suspend fun shell(cmd: String): String = use { it.shell(cmd).allOutput }

    suspend fun key(code: Int) { keys(listOf(code)) }

    /** Presses keys, through the key server if possible. */
    suspend fun keys(codes: List<Int>) {
        if (codes.isEmpty()) return
        if (!sendFast("K " + codes.joinToString(" "))) shell("input keyevent " + codes.joinToString(" "))
    }

    suspend fun type(text: String) {
        val clean = text.replace('\n', ' ')
        if (clean.isEmpty()) return
        if (!sendFast("T $clean")) shell("input text ${TvParsers.inputText(clean)}")
    }

    /** Call when the TV reports the key server isn't running, so the next press restarts it. */
    fun keyServerGone() {
        runCatching { keyStream?.close() }
        keyStream = null
    }

    private suspend fun sendFast(line: String): Boolean {
        val s = keyStream ?: startKeyServer() ?: return false
        return try {
            withContext(Dispatchers.IO) { s.write(line + "\n") }
            true
        } catch (t: Throwable) {
            keyServerGone()
            false
        }
    }

    var keyServerAllowed = false

    private suspend fun startKeyServer(): AdbShellStream? {
        if (!keyServerAllowed) return null
        if (System.currentTimeMillis() - keyServerFailedAt < 60_000) return null
        return try {
            use { d ->
                keyStream?.let { return@use it }
                val s = d.openShell(TvParsers.KEY_SERVER_CMD)
                val first = s.read()
                if (first is AdbShellPacket.StdOut && String(first.payload).contains("READY")) {
                    keyStream = s
                    s
                } else {
                    runCatching { s.close() }
                    keyServerFailedAt = System.currentTimeMillis()
                    null
                }
            }
        } catch (t: Throwable) {
            keyServerFailedAt = System.currentTimeMillis()
            null
        }
    }

    suspend fun install(apk: File) = use { it.install(apk, "-r") }

    fun close() {
        dropAll()
    }
}
