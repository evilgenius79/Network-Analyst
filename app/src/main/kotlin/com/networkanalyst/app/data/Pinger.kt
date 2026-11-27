package com.networkanalyst.app.data

import android.net.Network
import com.networkanalyst.core.model.PathHop
import com.networkanalyst.core.model.PingMethod
import com.networkanalyst.core.model.PingResult
import com.networkanalyst.core.ping.PingOutputParser
import com.networkanalyst.core.util.IpUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Measures latency to a host, choosing the best method the device allows:
 *  1. ICMP echo through a ping socket (most precise);
 *  2. the system's `ping` command, if sockets are refused;
 *  3. timing TCP connection attempts, if the host does not answer ICMP at all.
 */
class Pinger(private val network: Network?) {
    private val icmp = IcmpPinger(network)

    /** True once Android has refused traffic to a local address for lack of permission. */
    @Volatile var localAccessDenied = false
        private set

    /**
     * @param tcpPorts ports to time connection attempts against if ICMP gets no answer
     */
    suspend fun ping(
        target: String,
        label: String,
        count: Int = 20,
        intervalMs: Long = 200,
        tcpPorts: List<Int> = listOf(443, 80),
    ): PingResult {
        val address = parseIpv4(target)
            ?: return PingResult(target, label, PingMethod.ICMP, 0, emptyList(), error = "Not an IPv4 address")

        val viaIcmp: PingResult? = try {
            val rtts = icmp.ping(address, count, intervalMs)
            PingResult(target, label, PingMethod.ICMP, count, rtts.filterNotNull())
        } catch (e: IcmpUnavailableException) {
            if (e.permissionDenied && isLocal(target)) {
                localAccessDenied = true
                return PingResult(target, label, PingMethod.ICMP, count, emptyList(), error = "Local network access is not allowed")
            }
            null
        }
        if (viaIcmp != null && viaIcmp.reachable) return viaIcmp

        if (viaIcmp == null) {
            // ICMP sockets are unavailable on this device: try the ping program instead.
            shellPing(target, label, count, intervalMs)?.takeIf { it.reachable }?.let { return it }
        }
        // The host ignores ICMP (or nothing worked): time TCP handshakes instead.
        val viaTcp = tcpPing(address, target, label, count.coerceAtMost(10), tcpPorts)
        return if (viaTcp.reachable) viaTcp else viaIcmp ?: viaTcp
    }

    /** Runs the system ping binary. Returns null if it is missing or printed nothing usable. */
    private suspend fun shellPing(target: String, label: String, count: Int, intervalMs: Long): PingResult? {
        // The ping program refuses intervals under 0.2 s to ordinary users, so send fewer
        // probes rather than take twice as long.
        val interval = String.format(java.util.Locale.US, "%.1f", (intervalMs / 1000.0).coerceAtLeast(0.2))
        val probes = count.coerceAtMost(20)
        val output = runPing(listOf("-c", probes.toString(), "-i", interval, "-W", "1", target), timeoutMs = probes * 1_300L + 3_000) ?: return null
        val parsed = PingOutputParser.parse(output)
        if (parsed.transmitted == null && parsed.replies.isEmpty()) return null
        return PingResult(target, label, PingMethod.SYSTEM_PING, parsed.transmitted ?: probes, parsed.replies.sortedBy { it.sequence }.map { it.timeMs })
    }

    private suspend fun runPing(arguments: List<String>, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        val binary = PING_BINARIES.firstOrNull { File(it).canExecute() } ?: return@withContext null
        var process: Process? = null
        try {
            process = ProcessBuilder(listOf(binary) + arguments).redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            // Reading drains the pipe; the process ends on its own after `count` probes.
            val text = StringBuilder()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (true) {
                coroutineContext.ensureActive()
                if (System.nanoTime() > deadline) break
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    text.appendLine(line)
                } else if (!process.isAlive) {
                    reader.readText().let(text::append)
                    break
                } else {
                    Thread.sleep(15)
                }
            }
            text.toString()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            process?.destroy()
            process?.waitFor(200, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Times TCP handshakes. A refused connection completes just as fast as an accepted one,
     * so either gives a round-trip time; only silence counts as loss.
     */
    private suspend fun tcpPing(address: Inet4Address, target: String, label: String, count: Int, ports: List<Int>): PingResult =
        withContext(Dispatchers.IO) {
            val rtts = ArrayList<Double>()
            var attempts = 0
            var port = ports.firstOrNull() ?: 443
            var portSettled = false
            repeat(count) { index ->
                coroutineContext.ensureActive()
                attempts++
                var rtt = connectTime(address, port)
                if (rtt == null && !portSettled) {
                    // Find a port that answers before committing to one.
                    for (candidate in ports.drop(1)) {
                        rtt = connectTime(address, candidate)
                        if (rtt != null) {
                            port = candidate
                            break
                        }
                    }
                }
                if (rtt != null) {
                    portSettled = true
                    rtts += rtt
                }
                if (index < count - 1) delay(150)
            }
            PingResult(target, label, PingMethod.TCP, attempts, rtts)
        }

    private fun connectTime(address: Inet4Address, port: Int): Double? {
        val socket: Socket = try {
            network?.socketFactory?.createSocket() ?: Socket()
        } catch (_: IOException) {
            return null
        }
        val start = System.nanoTime()
        return try {
            socket.connect(InetSocketAddress(address, port), 1_000)
            (System.nanoTime() - start) / 1_000_000.0
        } catch (_: ConnectException) {
            // Refused: the reset came straight back, which is a valid round trip. A slow
            // "refusal" is really a routing failure, not an answer from the host.
            ((System.nanoTime() - start) / 1_000_000.0).takeIf { it < 900 }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Finds the first routers on the way to [target] by sending probes with a small hop limit
     * and noting who reports them expired. Needs the system ping program, since ping sockets
     * do not deliver those reports to apps; returns an empty list when it is not usable.
     */
    suspend fun firstHops(target: String, maxHops: Int = 6): List<PathHop> = coroutineScope {
        if (PING_BINARIES.none { File(it).canExecute() }) return@coroutineScope emptyList()
        val hops = (1..maxHops).map { ttl ->
            async {
                val output = runPing(listOf("-c", "1", "-t", ttl.toString(), "-W", "1", target), timeoutMs = 2_500)
                    ?: return@async PathHop(ttl)
                val parsed = PingOutputParser.parse(output)
                val expired = parsed.errors.firstOrNull { it.isTtlExceeded }
                val reply = parsed.replies.firstOrNull()
                when {
                    expired != null -> PathHop(ttl, expired.from.takeIf(IpUtils::isIpv4))
                    reply != null -> PathHop(ttl, reply.from.takeIf(IpUtils::isIpv4), reply.timeMs)
                    else -> PathHop(ttl)
                }
            }
        }.awaitAll()
        // Stop at the destination itself; later probes would just repeat it.
        val reached = hops.indexOfFirst { it.address == target }
        val trimmed = if (reached >= 0) hops.take(reached + 1) else hops
        if (trimmed.all { it.address == null }) emptyList() else trimmed
    }

    /** ICMP sweep of a subnet for the device scan; empty when ICMP is not usable. */
    suspend fun sweep(addresses: List<String>, timeoutMs: Long): Map<String, Double> {
        val parsed = addresses.mapNotNull { text -> parseIpv4(text)?.let { it to text } }.toMap()
        return try {
            icmp.sweep(parsed.keys.toList(), timeoutMs).mapNotNull { (address, rtt) -> parsed[address]?.let { it to rtt } }.toMap()
        } catch (e: IcmpUnavailableException) {
            if (e.permissionDenied) localAccessDenied = true
            emptyMap()
        }
    }

    private fun isLocal(target: String): Boolean = IpUtils.scopeOfIpv4(target).let {
        it == com.networkanalyst.core.util.AddressScope.PRIVATE || it == com.networkanalyst.core.util.AddressScope.LINK_LOCAL
    }

    private fun parseIpv4(text: String): Inet4Address? {
        if (!IpUtils.isIpv4(text)) return null
        // A literal address never triggers a DNS lookup.
        return InetAddress.getByName(text) as? Inet4Address
    }

    companion object {
        private val PING_BINARIES = listOf("/system/bin/ping", "/system/xbin/ping", "/bin/ping", "/usr/bin/ping")
    }
}
