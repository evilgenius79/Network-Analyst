package com.networkanalyst.core.discovery

import com.networkanalyst.core.dns.DnsMessage
import com.networkanalyst.core.dns.RData
import com.networkanalyst.core.model.GatewayInfo
import com.networkanalyst.core.model.LanDevice
import com.networkanalyst.core.util.IpUtils
import com.networkanalyst.core.util.Ipv4Subnet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

/** Outcome of knocking on one TCP port. */
enum class PortState {
    /** Something accepted the connection. */
    OPEN,
    /** The host answered "nothing listens here", which still proves the host is there. */
    CLOSED,
    /** No answer at all: host absent, asleep, or silently filtering. */
    NO_ANSWER,
}

/**
 * How the scanner reaches the network. The app supplies an implementation whose sockets are
 * bound to the Wi-Fi network and which can send ICMP; the defaults work on a plain JVM.
 */
interface LanTransport {
    fun datagramSocket(): DatagramSocket = DatagramSocket()

    fun socket(): Socket = Socket()

    val httpClient: OkHttpClient get() = DEFAULT_CLIENT

    /** ICMP echo sweep: address to round-trip ms for every host that answered. Optional. */
    suspend fun pingSweep(addresses: List<String>, timeoutMs: Long): Map<String, Double> = emptyMap()

    companion object {
        private val DEFAULT_CLIENT by lazy { OkHttpClient() }
        val DEFAULT = object : LanTransport {}
    }
}

data class LanScanConfig(
    /** Largest number of addresses to probe; bigger subnets are sampled around this device. */
    val maxHosts: Int = 512,
    /** Ports knocked on to find hosts that ignore ping. 62078 is the iPhone/iPad sync port. */
    val probePorts: List<Int> = listOf(80, 443, 445, 22, 62078),
    val tcpTimeoutMs: Int = 350,
    val parallelism: Int = 96,
    val pingTimeoutMs: Long = 1_500,
    val mdnsListenMs: Long = 3_500,
    val ssdpListenMs: Long = 3_000,
    val nameLookupTimeoutMs: Int = 600,
    val maxDescriptionFetches: Int = 24,
)

data class LanScanResult(
    val devices: List<LanDevice>,
    /** UPnP description of the internet gateway, when the router offers one. */
    val gatewayDescription: UpnpDescription? = null,
    val subnet: String? = null,
    /** Number of addresses actually probed. */
    val scannedHosts: Int = 0,
    /** True when the subnet was larger than the scan window. */
    val truncated: Boolean = false,
)

/**
 * Finds devices on the local IPv4 network and works out what they are.
 *
 * Presence is established by ICMP echo and by TCP connection attempts (a refused connection
 * is as good as an accepted one for proving a host exists). Identity comes from what devices
 * announce: mDNS/DNS-SD, UPnP descriptions, NetBIOS names, and the router's reverse DNS.
 *
 * Hardware (MAC) addresses are deliberately not used: Android no longer lets apps read the
 * ARP table, so vendor lookup by MAC prefix is not possible.
 */
class LanScanner(
    private val transport: LanTransport = LanTransport.DEFAULT,
    private val config: LanScanConfig = LanScanConfig(),
) {
    /**
     * @param ownCidr this device's address with prefix length, e.g. "192.168.1.23/24"
     * @param gateway the default gateway's address, if known
     * @param dnsServers resolvers handed out by the network, tried for reverse lookups
     */
    suspend fun scan(
        ownCidr: String,
        gateway: String?,
        dnsServers: List<String> = emptyList(),
        onProgress: (String) -> Unit = {},
    ): LanScanResult = coroutineScope {
        val subnet = Ipv4Subnet.parse(ownCidr) ?: return@coroutineScope LanScanResult(emptyList())
        val self = ownCidr.substringBefore('/')
        val selfValue = IpUtils.parseIpv4(self) ?: return@coroutineScope LanScanResult(emptyList())
        val hosts = subnet.hosts(config.maxHosts, around = selfValue).map(IpUtils::formatIpv4)
        val truncated = subnet.hostCount > hosts.size
        val others = hosts.filter { it != self }

        onProgress("Looking for devices on $subnet")
        val pingJob = async { runCatching { transport.pingSweep(others, config.pingTimeoutMs) }.getOrDefault(emptyMap()) }
        val mdnsJob = async { Mdns.discover(transport::datagramSocket, config.mdnsListenMs) }
        val ssdpJob = async { Ssdp.search(transport::datagramSocket, listenMs = config.ssdpListenMs) }
        val tcpJob = async { tcpSweep(others) }

        val pings = pingJob.await()
        val tcp = tcpJob.await()
        val mdns = mdnsJob.await()
        val ssdp = ssdpJob.await()

        onProgress("Identifying devices")
        val upnp = fetchDescriptions(ssdp)

        // Anything that answered in any way is on the network.
        val alive = LinkedHashSet<String>()
        alive += pings.keys
        alive += tcp.filterValues { ports -> ports.values.any { it != PortState.NO_ANSWER } }.keys
        alive += mdns.keys.filter { IpUtils.isIpv4(it) && subnet.contains(IpUtils.parseIpv4(it)!!) }
        alive += upnp.keys.filter { IpUtils.isIpv4(it) && subnet.contains(IpUtils.parseIpv4(it)!!) }
        gateway?.takeIf { IpUtils.isIpv4(it) }?.let(alive::add)
        alive -= self

        val gate = Semaphore(config.parallelism)
        val netbios = alive.map { ip -> async { gate.withPermit { ip to netBiosLookup(ip) } } }
        val reverse = alive.map { ip -> async { gate.withPermit { ip to reverseLookup(ip, dnsServers) } } }
        val netbiosByIp = netbios.awaitAll().toMap()
        val reverseByIp = reverse.awaitAll().toMap()

        val devices = (alive + self).map { ip ->
            buildDevice(
                ip = ip,
                isSelf = ip == self,
                isGateway = ip == gateway,
                pingMs = pings[ip],
                ports = tcp[ip].orEmpty(),
                mdns = mdns[ip],
                upnp = upnp[ip],
                netBios = netbiosByIp[ip],
                reverseName = reverseByIp[ip],
            )
        }.sortedBy { IpUtils.parseIpv4(it.ip)?.toUInt() ?: UInt.MAX_VALUE }

        val gatewayDescription = upnp.values.firstOrNull { it.isInternetGateway && (gateway == null || upnp[gateway] == it) }
            ?: upnp.values.firstOrNull { it.isInternetGateway }
        LanScanResult(devices, gatewayDescription, subnet.toString(), hosts.size, truncated)
    }

    /**
     * Looks for the internet gateway only, without sweeping the subnet. Used when the full
     * device scan is switched off but the router's own report is still wanted.
     */
    suspend fun findGateway(listenMs: Long = 2_500): UpnpDescription? {
        val responses = Ssdp.search(
            transport::datagramSocket,
            searchTargets = listOf(Ssdp.TARGET_GATEWAY, Ssdp.TARGET_ROOT_DEVICE),
            listenMs = listenMs,
        )
        return fetchDescriptions(responses).values.firstOrNull { it.isInternetGateway }
    }

    /** Queries the router for WAN details using a description found by [scan]. */
    suspend fun queryGateway(description: UpnpDescription, trafficSampleMs: Long = 3_000): GatewayInfo =
        UpnpClient(transport.httpClient).queryGateway(description, trafficSampleMs)

    private suspend fun tcpSweep(addresses: List<String>): Map<String, Map<Int, PortState>> = coroutineScope {
        val gate = Semaphore(config.parallelism)
        addresses.map { ip ->
            async(Dispatchers.IO) {
                val states = LinkedHashMap<Int, PortState>()
                for (port in config.probePorts) {
                    val state = gate.withPermit { probe(ip, port) }
                    states[port] = state
                    // A host that stays silent on the first two ports is very unlikely to
                    // answer on the rest, so stop early and keep the sweep fast.
                    if (states.size >= 2 && states.values.all { it == PortState.NO_ANSWER }) break
                }
                ip to states
            }
        }.awaitAll().toMap()
    }

    internal fun probe(ip: String, port: Int): PortState = try {
        transport.socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(ip), port), config.tcpTimeoutMs)
            PortState.OPEN
        }
    } catch (_: ConnectException) {
        // "Connection refused": the host exists and rejected the port.
        PortState.CLOSED
    } catch (_: SocketTimeoutException) {
        PortState.NO_ANSWER
    } catch (_: Exception) {
        // No route to host, network unreachable, permission denied, ...
        PortState.NO_ANSWER
    }

    private suspend fun fetchDescriptions(responses: List<SsdpResponse>): Map<String, UpnpDescription> = coroutineScope {
        val client = UpnpClient(transport.httpClient)
        // One description per device is enough; prefer the root device entry.
        val byAddress = responses.filter { it.location != null }
            .sortedBy { if (it.searchTarget == Ssdp.TARGET_ROOT_DEVICE) 0 else 1 }
            .distinctBy { it.address }
            .take(config.maxDescriptionFetches)
        byAddress.map { response ->
            async(Dispatchers.IO) { response.address to client.fetchDescription(response.location!!) }
        }.awaitAll().mapNotNull { (address, description) -> description?.let { address to it } }.toMap()
    }

    private suspend fun netBiosLookup(ip: String): NetBiosInfo? = withContext(Dispatchers.IO) {
        try {
            transport.datagramSocket().use { socket ->
                socket.soTimeout = config.nameLookupTimeoutMs
                val id = Random.nextInt(0, 0x10000)
                val request = NetBios.buildNodeStatusRequest(id)
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(ip), NetBios.PORT))
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                NetBios.parseNodeStatusResponse(buffer, packet.length, id)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Asks the network's own DNS server (normally the router) what it calls this address. */
    private suspend fun reverseLookup(ip: String, dnsServers: List<String>): String? = withContext(Dispatchers.IO) {
        val name = reverseName(ip) ?: return@withContext null
        for (server in dnsServers.filter(IpUtils::isIpv4).take(2)) {
            try {
                transport.datagramSocket().use { socket ->
                    socket.soTimeout = config.nameLookupTimeoutMs
                    val id = Random.nextInt(0, 0x10000)
                    val query = DnsMessage.buildQuery(id, name, DnsMessage.TYPE_PTR)
                    socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val response = DnsMessage.parse(buffer, packet.length)
                    if (response != null && response.id == id) {
                        val pointer = response.answers.firstNotNullOfOrNull { (it.data as? RData.Name)?.name }
                        cleanHostname(pointer)?.let { return@withContext it }
                    }
                }
            } catch (_: Exception) {
                // Try the next server.
            }
        }
        null
    }

    companion object {
        /** "192.168.1.20" -> "20.1.168.192.in-addr.arpa". */
        fun reverseName(ip: String): String? {
            val parts = ip.split('.')
            if (!IpUtils.isIpv4(ip)) return null
            return parts.reversed().joinToString(".") + ".in-addr.arpa"
        }

        /** Drops local-domain suffixes ("lan", "home", "local", ...) so only the device's own name is left. */
        fun cleanHostname(name: String?): String? {
            val trimmed = name?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
            val first = trimmed.substringBefore('.')
            return first.takeIf { it.isNotEmpty() && !IpUtils.isIpv4(trimmed) }
        }

        /** Combines every clue about one address into a device entry. */
        fun buildDevice(
            ip: String,
            isSelf: Boolean,
            isGateway: Boolean,
            pingMs: Double?,
            ports: Map<Int, PortState>,
            mdns: MdnsObservation?,
            upnp: UpnpDescription?,
            netBios: NetBiosInfo?,
            reverseName: String?,
        ): LanDevice {
            val openPorts = ports.filterValues { it == PortState.OPEN }.keys.sorted()
            val hostname = reverseName ?: mdns?.hostnames?.firstOrNull() ?: netBios?.computerName
            // Most specific human-readable name first.
            val name = upnp?.friendlyName
                ?: mdns?.txt?.get("fn")
                ?: mdns?.instanceNames?.firstOrNull()
                ?: netBios?.computerName
            val manufacturer = upnp?.manufacturer ?: mdns?.txt?.get("manufacturer")
            val model = upnp?.modelName ?: mdns?.txt?.get("md") ?: mdns?.txt?.get("model") ?: mdns?.txt?.get("am")
            val services = mdns?.serviceTypes?.sorted().orEmpty()
            val type = DeviceClassifier.classify(
                isGateway = isGateway,
                isSelf = isSelf,
                services = services,
                names = listOf(name, hostname) + mdns?.instanceNames.orEmpty(),
                manufacturer = manufacturer,
                model = model,
                upnpDeviceType = upnp?.deviceType,
                openPorts = openPorts,
            )
            return LanDevice(
                ip = ip,
                hostname = hostname,
                name = name?.takeIf { it != hostname },
                manufacturer = manufacturer,
                model = model,
                type = type,
                isGateway = isGateway,
                isSelf = isSelf,
                pingMs = pingMs,
                openPorts = openPorts,
                services = services,
            )
        }
    }
}
