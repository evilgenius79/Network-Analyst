package com.networkanalyst.core.model

import com.networkanalyst.core.util.Stats
import kotlinx.serialization.Serializable

enum class Transport(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Mobile data"),
    ETHERNET("Ethernet"),
    OTHER("Other"),
    NONE("No connection"),
}

/** How the phone is attached to the network right now. */
@Serializable
data class ConnectionInfo(
    val transport: Transport = Transport.NONE,
    val isVpn: Boolean = false,
    val isMetered: Boolean = false,
    /** True when Android has confirmed the network reaches the internet. */
    val isValidated: Boolean = false,
    val hasCaptivePortal: Boolean = false,
    val interfaceName: String? = null,
    /** Address with prefix length, for example "192.168.1.23/24". */
    val ipv4Addresses: List<String> = emptyList(),
    val ipv6Addresses: List<String> = emptyList(),
    val gatewayV4: String? = null,
    val gatewayV6: String? = null,
    val dnsServers: List<String> = emptyList(),
    val privateDnsActive: Boolean = false,
    val privateDnsServerName: String? = null,
    val mtu: Int? = null,
    val httpProxy: String? = null,
    /** Android's own estimate of the link's capacity, kbps. */
    val downstreamKbps: Int? = null,
    val upstreamKbps: Int? = null,
    val dataSaverOn: Boolean = false,
    val batterySaverOn: Boolean = false,
    /** Mobile network generation ("5G", "LTE", ...) when on mobile data and it is known. */
    val cellularGeneration: String? = null,
) {
    val hasGlobalIpv6: Boolean
        get() = ipv6Addresses.any { address ->
            val a = address.substringBefore('/').lowercase()
            // 2000::/3 is the global unicast range.
            a.isNotEmpty() && (a[0] == '2' || a[0] == '3')
        }
}

enum class PingMethod(val label: String) {
    ICMP("ICMP echo"),
    SYSTEM_PING("system ping"),
    TCP("TCP connect"),
}

/** Result of a burst of latency probes to one target. */
@Serializable
data class PingResult(
    val target: String,
    val label: String,
    val method: PingMethod,
    val sent: Int,
    /** Round-trip time of every reply that came back, in ms, in send order. */
    val rttsMs: List<Double>,
    val error: String? = null,
) {
    val received: Int get() = rttsMs.size
    val lossPercent: Double get() = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent
    val minMs: Double? get() = rttsMs.minOrNull()
    val maxMs: Double? get() = rttsMs.maxOrNull()
    val avgMs: Double? get() = Stats.mean(rttsMs)
    val medianMs: Double? get() = Stats.median(rttsMs)
    val jitterMs: Double? get() = Stats.jitter(rttsMs)
    val reachable: Boolean get() = rttsMs.isNotEmpty()
}

@Serializable
data class DnsServerResult(
    val name: String,
    val address: String,
    /** True for the resolver the network handed out, false for public alternatives. */
    val isSystem: Boolean,
    val queries: Int,
    val failures: Int,
    val timesMs: List<Double>,
) {
    val medianMs: Double? get() = Stats.median(timesMs)
    val avgMs: Double? get() = Stats.mean(timesMs)
    val p95Ms: Double? get() = Stats.percentile(timesMs, 0.95)
    val failurePercent: Double get() = if (queries == 0) 0.0 else failures * 100.0 / queries
    val usable: Boolean get() = timesMs.isNotEmpty()
}

@Serializable
data class DnsBenchmarkResult(
    val servers: List<DnsServerResult> = emptyList(),
) {
    val systemServers: List<DnsServerResult> get() = servers.filter { it.isSystem }
    val publicServers: List<DnsServerResult> get() = servers.filter { !it.isSystem }
    val fastestPublic: DnsServerResult?
        get() = publicServers.filter { it.usable && it.failurePercent < 20 }.minByOrNull { it.medianMs ?: Double.MAX_VALUE }
    val bestSystem: DnsServerResult?
        get() = systemServers.filter { it.usable }.minByOrNull { it.medianMs ?: Double.MAX_VALUE }
}

@Serializable
data class SpeedTestResult(
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    /** Latency to the test server with the line idle. */
    val idleLatencyMs: Double? = null,
    val idleJitterMs: Double? = null,
    /** Latency measured while the download / upload was saturating the line. */
    val downloadLoadedLatencyMs: Double? = null,
    val uploadLoadedLatencyMs: Double? = null,
    val bytesDownloaded: Long = 0,
    val bytesUploaded: Long = 0,
    val serverLocation: String? = null,
    val publicIp: String? = null,
    val asn: String? = null,
    /** Approximate location of the public IP as the test server sees it. */
    val city: String? = null,
    val country: String? = null,
    val error: String? = null,
    val timestampMs: Long = 0,
) {
    /** Extra delay the line adds when it is busy: the worse of the two directions. */
    val bufferbloatMs: Double?
        get() {
            val idle = idleLatencyMs ?: return null
            val loaded = listOfNotNull(downloadLoadedLatencyMs, uploadLoadedLatencyMs).maxOrNull() ?: return null
            return (loaded - idle).coerceAtLeast(0.0)
        }
}

/** One hop on the path to the internet. [address] is null when the hop did not answer. */
@Serializable
data class PathHop(val ttl: Int, val address: String? = null, val rttMs: Double? = null)

/** What the router itself reports over UPnP, when it supports that. */
@Serializable
data class GatewayInfo(
    val friendlyName: String? = null,
    val manufacturer: String? = null,
    val modelName: String? = null,
    val externalIp: String? = null,
    val wanAccessType: String? = null,
    /** Sync / provisioned rates of the WAN link as the router reports them, bits per second. */
    val maxDownstreamBps: Long? = null,
    val maxUpstreamBps: Long? = null,
    val physicalLinkStatus: String? = null,
    val connectionStatus: String? = null,
    val uptimeSeconds: Long? = null,
    /** Traffic through the router's WAN port while this phone was idle, bits per second. */
    val backgroundDownBps: Double? = null,
    val backgroundUpBps: Double? = null,
)

enum class DeviceType(val label: String) {
    ROUTER("Router"),
    THIS_DEVICE("This phone"),
    PHONE("Phone or tablet"),
    COMPUTER("Computer"),
    TV("TV or streaming device"),
    SPEAKER("Smart speaker"),
    PRINTER("Printer"),
    GAME_CONSOLE("Game console"),
    NAS("Storage / NAS"),
    CAMERA("Camera"),
    SMART_HOME("Smart home device"),
    UNKNOWN("Unknown device"),
}

@Serializable
data class LanDevice(
    val ip: String,
    val hostname: String? = null,
    /** Human-friendly name advertised by the device (mDNS, UPnP, NetBIOS). */
    val name: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val type: DeviceType = DeviceType.UNKNOWN,
    val isGateway: Boolean = false,
    val isSelf: Boolean = false,
    val pingMs: Double? = null,
    val openPorts: List<Int> = emptyList(),
    /** Service types the device advertises, for example "_googlecast._tcp". */
    val services: List<String> = emptyList(),
) {
    val displayName: String
        get() = name ?: hostname ?: listOfNotNull(manufacturer, model).joinToString(" ").ifBlank { null } ?: ip
}

/** Everything one scan collected. Any part may be missing if it was skipped or failed. */
@Serializable
data class NetworkSnapshot(
    val timestampMs: Long = 0,
    val connection: ConnectionInfo = ConnectionInfo(),
    val wifiLink: WifiLink? = null,
    val wifiCapabilities: WifiCapabilities? = null,
    val wifiScan: WifiScan? = null,
    val gatewayPing: PingResult? = null,
    val internetPings: List<PingResult> = emptyList(),
    val dns: DnsBenchmarkResult? = null,
    val speed: SpeedTestResult? = null,
    val path: List<PathHop> = emptyList(),
    val gateway: GatewayInfo? = null,
    val devices: List<LanDevice>? = null,
    /** False when Android blocked local network access (Android 17 permission not granted). */
    val localNetworkAccess: Boolean = true,
)
