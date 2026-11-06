package dev.daniel.tvhub.core

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

object NetUtil {
    /** The phone's Wi‑Fi address, e.g. 192.168.1.40, plus its network prefix length. */
    fun phoneIpv4(context: Context): Pair<String, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
        return la.address.hostAddress!! to la.prefixLength
    }

    /** Looks for devices on your Wi‑Fi that have network adb (port 5555) open. */
    suspend fun scanForAdb(context: Context, port: Int = 5555): List<String> = withContext(Dispatchers.IO) {
        val (ip, _) = phoneIpv4(context) ?: return@withContext emptyList()
        val base = ip.substringBeforeLast('.')
        val gate = Semaphore(48)
        coroutineScope {
            (1..254).map { "$base.$it" }.filter { it != ip }.map { host ->
                async {
                    gate.withPermit {
                        runCatching {
                            Socket().use { s -> s.connect(InetSocketAddress(host, port), 400) }
                            host
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
