package com.networkanalyst.core.discovery

import com.networkanalyst.core.dns.DnsMessage
import com.networkanalyst.core.dns.DnsPacket
import com.networkanalyst.core.dns.RData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** What one device said about itself over mDNS. */
data class MdnsObservation(
    val address: String,
    /** Service types without the ".local" suffix, e.g. "_googlecast._tcp". */
    val serviceTypes: Set<String> = emptySet(),
    /** User-visible instance names, e.g. "Living Room TV". */
    val instanceNames: Set<String> = emptySet(),
    /** Host names without ".local", e.g. "Johns-MacBook-Pro". */
    val hostnames: Set<String> = emptySet(),
    /** Selected TXT keys that describe the device (model, friendly name). */
    val txt: Map<String, String> = emptyMap(),
) {
    fun merge(other: MdnsObservation) = MdnsObservation(
        address,
        serviceTypes + other.serviceTypes,
        instanceNames + other.instanceNames,
        hostnames + other.hostnames,
        txt + other.txt,
    )
}

/**
 * Multicast DNS / DNS-SD browsing (RFC 6762, RFC 6763): phones, TVs, speakers, printers and
 * computers announce their names and services this way.
 *
 * Queries are sent from an ordinary (non-5353) port with the unicast-response bit set, which
 * makes responders reply directly to this socket. That avoids joining the multicast group.
 */
object Mdns {
    const val MULTICAST_ADDRESS = "224.0.0.251"
    const val PORT = 5353
    const val SERVICE_ENUMERATION = "_services._dns-sd._udp.local"

    /** Asked for up front; anything else is picked up through service enumeration. */
    val COMMON_SERVICE_TYPES = listOf(
        "_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_companion-link._tcp", "_androidtvremote2._tcp",
        "_spotify-connect._tcp", "_sonos._tcp", "_amzn-wplay._tcp", "_ipp._tcp", "_printer._tcp",
        "_smb._tcp", "_afpovertcp._tcp", "_ssh._tcp", "_workstation._tcp", "_device-info._tcp",
        "_hap._tcp", "_matter._tcp", "_homekit._tcp", "_http._tcp",
    )

    private val INTERESTING_TXT_KEYS = setOf("fn", "md", "model", "am", "ty", "n", "name", "manufacturer")
    private const val MAX_QUERIED_TYPES = 60

    /** Folds one mDNS response from [fromAddress] into an observation. */
    fun observe(packet: DnsPacket, fromAddress: String): MdnsObservation {
        val types = LinkedHashSet<String>()
        val instances = LinkedHashSet<String>()
        val hosts = LinkedHashSet<String>()
        val txt = LinkedHashMap<String, String>()
        for (record in packet.allRecords) {
            val name = record.name
            when (val data = record.data) {
                is RData.Name -> if (record.type == DnsMessage.TYPE_PTR) {
                    if (name.equals(SERVICE_ENUMERATION, ignoreCase = true)) {
                        serviceTypeOf(data.name)?.let(types::add)
                    } else {
                        val type = serviceTypeOf(name)
                        if (type != null) {
                            types += type
                            instanceOf(data.name, name)?.let(instances::add)
                        }
                    }
                }
                is RData.Service -> {
                    serviceTypeOfInstance(name)?.let(types::add)
                    stripLocal(data.target).takeIf { it.isNotEmpty() }?.let(hosts::add)
                }
                is RData.Address -> stripLocal(name).takeIf { it.isNotEmpty() && !it.startsWith("_") }?.let(hosts::add)
                is RData.Text -> for (entry in data.entries) {
                    val key = entry.substringBefore('=', "").lowercase()
                    val value = entry.substringAfter('=', "")
                    if (key in INTERESTING_TXT_KEYS && value.isNotBlank()) txt.putIfAbsent(key, value)
                }
                is RData.Other -> Unit
            }
        }
        return MdnsObservation(fromAddress, types, instances, hosts, txt)
    }

    private fun stripLocal(name: String): String = name.removeSuffix(".").let {
        if (it.endsWith(".local", ignoreCase = true)) it.dropLast(6) else it
    }

    /** "_googlecast._tcp.local" -> "_googlecast._tcp"; null if the name is not a service type. */
    private fun serviceTypeOf(name: String): String? {
        val labels = stripLocal(name).split('.')
        if (labels.size != 2) return null
        if (!labels[0].startsWith("_") || (labels[1] != "_tcp" && labels[1] != "_udp")) return null
        return labels.joinToString(".")
    }

    /** "Living Room._googlecast._tcp.local" -> "_googlecast._tcp". */
    private fun serviceTypeOfInstance(name: String): String? {
        val labels = stripLocal(name).split('.')
        if (labels.size < 3) return null
        return serviceTypeOf(labels.takeLast(2).joinToString(".") + ".local")
    }

    /** Instance label of "Living Room._googlecast._tcp.local" given its type name. */
    private fun instanceOf(instanceName: String, typeName: String): String? {
        val suffix = "." + typeName.removeSuffix(".")
        val trimmed = instanceName.removeSuffix(".")
        if (!trimmed.endsWith(suffix, ignoreCase = true)) return null
        return trimmed.dropLast(suffix.length).takeIf { it.isNotBlank() }
    }

    /** Browses the local network for [listenMs] and returns one observation per responding address. */
    suspend fun discover(
        socketFactory: () -> DatagramSocket,
        listenMs: Long = 3_500,
        destination: InetAddress = InetAddress.getByName(MULTICAST_ADDRESS),
        port: Int = PORT,
    ): Map<String, MdnsObservation> = withContext(Dispatchers.IO) {
        val observations = LinkedHashMap<String, MdnsObservation>()
        try {
            socketFactory().use { socket ->
                val asked = LinkedHashSet<String>()
                fun ask(name: String) {
                    if (asked.size >= MAX_QUERIED_TYPES || !asked.add(name.lowercase())) return
                    val query = DnsMessage.buildQuery(0, name, DnsMessage.TYPE_PTR, recursionDesired = false, unicastResponse = true)
                    socket.send(DatagramPacket(query, query.size, destination, port))
                }
                ask(SERVICE_ENUMERATION)
                COMMON_SERVICE_TYPES.forEach { ask("$it.local") }

                val deadline = System.nanoTime() + listenMs * 1_000_000L
                val buffer = ByteArray(9000)
                while (true) {
                    val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining
                    val datagram = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(datagram)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val from = datagram.address?.hostAddress ?: continue
                    val packet = DnsMessage.parse(buffer, datagram.length) ?: continue
                    if (!packet.isResponse) continue
                    val observation = observe(packet, from)
                    observations[from] = observations[from]?.merge(observation) ?: observation
                    // Follow up on service types we had not heard of yet.
                    observation.serviceTypes.forEach { ask("$it.local") }
                }
            }
        } catch (_: Exception) {
            // Local network access denied or interface gone: return what was collected.
        }
        observations
    }
}
