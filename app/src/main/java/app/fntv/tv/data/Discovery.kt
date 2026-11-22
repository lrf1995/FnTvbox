package app.fntv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 局域网自动发现飞牛 NAS：扫本机所在 /24 网段的 5666/5667/8000/8001。
 * 电视上用遥控器打 IP 太痛苦，这一步能省掉整串数字。
 */
object Discovery {

    private val PORTS = listOf(5666, 8000, 5667, 8001)

    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun reachable(host: String, port: Int, timeoutMs: Int = 260): Boolean = runCatching {
        Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)

    /** 返回形如 `192.168.31.100:5666` 的候选，最多 8 个。 */
    suspend fun discoverNas(timeoutMs: Int = 260): List<String> = withContext(Dispatchers.IO) {
        val ip = localIpv4() ?: return@withContext emptyList()
        val prefix = ip.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return@withContext emptyList()

        val sem = Semaphore(64)
        val found = mutableListOf<String>()
        coroutineScope {
            (1..254).map { n ->
                async {
                    sem.withPermit {
                        val host = "$prefix.$n"
                        for (port in PORTS) {
                            if (reachable(host, port, timeoutMs)) {
                                synchronized(found) {
                                    if (found.size < 8) found.add("$host:$port")
                                }
                                break
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        found.sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
    }
}