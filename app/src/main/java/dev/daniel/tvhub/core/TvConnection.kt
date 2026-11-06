package dev.daniel.tvhub.core

import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One network-adb connection to one TV. Commands run one at a time, in order,
 * so button presses never get reordered.
 */
class TvConnection(val config: TvConfig, private val keys: () -> AdbKeyPair) {
    private val mutex = Mutex()
    @Volatile private var dadb: Dadb? = null

    val isConnected get() = dadb != null

    suspend fun <T> use(block: (Dadb) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val d = dadb ?: Dadb.create(
                config.host, config.port, keys(),
                connectTimeout = 3_000,
                // Long enough for you to walk over and press "Allow" on the TV the first time.
                socketTimeout = 45_000,
            ).also { dadb = it }
            try {
                block(d)
            } catch (t: Throwable) {
                runCatching { d.close() }
                dadb = null
                throw t
            }
        }
    }

    suspend fun shell(cmd: String): String = use { it.shell(cmd).allOutput }

    suspend fun key(code: Int) { shell("input keyevent $code") }

    suspend fun install(apk: File) = use { it.install(apk, "-r") }

    fun close() {
        runCatching { dadb?.close() }
        dadb = null
    }
}
