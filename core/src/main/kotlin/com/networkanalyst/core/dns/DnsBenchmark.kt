package com.networkanalyst.core.dns

import com.networkanalyst.core.model.DnsServerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

data class DnsServer(val name: String, val address: String, val isSystem: Boolean = false)

/**
 * Times how quickly DNS resolvers answer by sending real queries over UDP port 53 and
 * measuring the round trip. Slow name lookups delay the start of every page load, so a slow
 * resolver makes a fast line feel sluggish.
 */
class DnsBenchmark(
    /** Lets the app hand in sockets bound to a specific network. */
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** Measures every server concurrently; queries to one server are sent one at a time. */
    suspend fun run(
        servers: List<DnsServer>,
        domains: List<String> = DEFAULT_DOMAINS,
        timeoutMs: Int = 1500,
    ): List<DnsServerResult> = coroutineScope {
        servers.map { server -> async { measure(server, domains, timeoutMs) } }.awaitAll()
    }

    suspend fun measure(server: DnsServer, domains: List<String>, timeoutMs: Int = 1500): DnsServerResult =
        withContext(Dispatchers.IO) {
            val times = ArrayList<Double>()
            var failures = 0
            var queries = 0
            val socket = try {
                socketFactory()
            } catch (_: Exception) {
                return@withContext DnsServerResult(server.name, server.address, server.isSystem, domains.size, domains.size, emptyList())
            }
            socket.use {
                val destination = try {
                    InetSocketAddress(InetAddress.getByName(server.address), 53)
                } catch (_: Exception) {
                    return@withContext DnsServerResult(server.name, server.address, server.isSystem, domains.size, domains.size, emptyList())
                }
                for (domain in domains) {
                    coroutineContext.ensureActive()
                    queries++
                    val elapsed = queryOnce(socket, destination, domain, timeoutMs)
                    if (elapsed == null) failures++ else times += elapsed
                }
            }
            DnsServerResult(server.name, server.address, server.isSystem, queries, failures, times)
        }

    /** Returns the round-trip time in ms, or null on timeout, error or a server-side failure. */
    private fun queryOnce(socket: DatagramSocket, destination: InetSocketAddress, domain: String, timeoutMs: Int): Double? {
        val id = Random.nextInt(0, 0x10000)
        val query = DnsMessage.buildQuery(id, domain, DnsMessage.TYPE_A)
        val buffer = ByteArray(1500)
        try {
            val start = nanoTime()
            socket.send(DatagramPacket(query, query.size, destination))
            val deadline = start + timeoutMs * 1_000_000L
            var remainingMs = timeoutMs
            while (remainingMs > 0) {
                socket.soTimeout = remainingMs
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val end = nanoTime()
                remainingMs = ((deadline - end) / 1_000_000L).toInt()
                // Ignore late answers to earlier queries and anything not from this server.
                if (packet.address != destination.address) continue
                val response = DnsMessage.parse(buffer, packet.length) ?: continue
                if (!response.isResponse || response.id != id) continue
                // NXDOMAIN is still a prompt, valid answer; SERVFAIL/REFUSED mean the resolver did not do its job.
                return if (response.rcode == DnsMessage.RCODE_NOERROR || response.rcode == DnsMessage.RCODE_NXDOMAIN) {
                    (end - start) / 1_000_000.0
                } else null
            }
        } catch (_: SocketTimeoutException) {
            // No answer within the time limit.
        } catch (_: Exception) {
            // Network unreachable, permission denied, socket closed by cancellation, ...
        }
        return null
    }

    companion object {
        /** Well-known public resolvers used as the comparison baseline. */
        val PUBLIC_SERVERS = listOf(
            DnsServer("Cloudflare", "1.1.1.1"),
            DnsServer("Google", "8.8.8.8"),
            DnsServer("Quad9", "9.9.9.9"),
            DnsServer("OpenDNS", "208.67.222.222"),
        )

        val DEFAULT_DOMAINS = listOf(
            "www.google.com",
            "www.youtube.com",
            "www.wikipedia.org",
            "www.amazon.com",
            "www.netflix.com",
            "www.microsoft.com",
            "www.apple.com",
            "www.cloudflare.com",
        )
    }
}
