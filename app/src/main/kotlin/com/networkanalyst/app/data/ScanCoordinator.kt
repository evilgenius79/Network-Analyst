package com.networkanalyst.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.networkanalyst.core.discovery.LanScanResult
import com.networkanalyst.core.discovery.LanScanner
import com.networkanalyst.core.discovery.LanTransport
import com.networkanalyst.core.dns.DnsBenchmark
import com.networkanalyst.core.dns.DnsServer
import com.networkanalyst.core.model.ConnectionInfo
import com.networkanalyst.core.model.DnsBenchmarkResult
import com.networkanalyst.core.model.GatewayInfo
import com.networkanalyst.core.model.NetworkSnapshot
import com.networkanalyst.core.model.PathHop
import com.networkanalyst.core.model.PingResult
import com.networkanalyst.core.model.SpeedTestResult
import com.networkanalyst.core.model.Transport
import com.networkanalyst.core.model.WifiScan
import com.networkanalyst.core.speed.SpeedPhase
import com.networkanalyst.core.speed.SpeedTestConfig
import com.networkanalyst.core.speed.SpeedTestEngine
import com.networkanalyst.core.speed.SpeedTestProgress
import com.networkanalyst.core.util.IpUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import java.net.DatagramSocket
import java.net.Socket

enum class ScanStep(val title: String) {
    CONNECTION("Connection"),
    WIFI("Wi-Fi link and nearby networks"),
    LATENCY("Latency to router and internet"),
    DNS("DNS servers"),
    DEVICES("Devices on the network"),
    ROUTER("Router and path"),
    SPEED("Speed test"),
    ANALYSIS("Analysis"),
}

enum class StepStatus { PENDING, RUNNING, DONE, SKIPPED }

/** Live reading shown while the speed test runs. */
data class SpeedLive(
    val phase: SpeedPhase = SpeedPhase.LATENCY,
    val currentMbps: Double = 0.0,
    val fraction: Double = 0.0,
    val latencyMs: Double? = null,
    /** Recent rates for the little graph, oldest first. */
    val series: List<Double> = emptyList(),
)

data class ScanProgress(
    val steps: Map<ScanStep, StepStatus> = ScanStep.entries.associateWith { StepStatus.PENDING },
    val message: String = "",
    val speed: SpeedLive? = null,
)

data class ScanOptions(
    val speedTest: Boolean = true,
    val deviceScan: Boolean = true,
    /** Cap on speed test traffic per direction; set on metered connections. */
    val maxSpeedTestBytes: Long = Long.MAX_VALUE,
)

class ScanOutcome(val snapshot: NetworkSnapshot, val lan: LanScanResult?)

/** Sockets and pings tied to one Android [Network], for the device scanner. */
class AndroidLanTransport(private val network: Network, private val pinger: Pinger) : LanTransport {
    override val httpClient: OkHttpClient by lazy { OkHttpClient.Builder().socketFactory(network.socketFactory).build() }

    override fun datagramSocket(): DatagramSocket {
        val socket = DatagramSocket()
        try {
            network.bindSocket(socket)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        return socket
    }

    override fun socket(): Socket = network.socketFactory.createSocket()

    override suspend fun pingSweep(addresses: List<String>, timeoutMs: Long): Map<String, Double> = pinger.sweep(addresses, timeoutMs)
}

/**
 * Runs the measurements of a full scan in an order that keeps them from disturbing each
 * other: quiet probes first, the device sweep next, the router's traffic counters while the
 * phone is idle, and the speed test last because it saturates the line.
 */
class ScanCoordinator(
    context: Context,
    private val connectivity: ConnectivityCollector,
    private val wifi: WifiCollector,
) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)

    /** Android 17 puts local network traffic behind its own runtime permission. */
    fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < API_LOCAL_NETWORK_PERMISSION ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    suspend fun run(options: ScanOptions, onProgress: (ScanProgress) -> Unit): ScanOutcome = coroutineScope {
        var progress = ScanProgress()
        fun update(step: ScanStep, status: StepStatus, message: String? = null) {
            progress = progress.copy(steps = progress.steps + (step to status), message = message ?: progress.message)
            onProgress(progress)
        }

        // ---- Connection ----
        update(ScanStep.CONNECTION, StepStatus.RUNNING, "Checking the connection")
        val connection = connectivity.snapshot()
        update(ScanStep.CONNECTION, StepStatus.DONE)
        if (connection.transport == Transport.NONE) {
            ScanStep.entries.filter { it != ScanStep.CONNECTION }.forEach { update(it, StepStatus.SKIPPED) }
            return@coroutineScope ScanOutcome(NetworkSnapshot(timestampMs = System.currentTimeMillis(), connection = connection), null)
        }

        val wifiNetwork = connectivity.wifiNetwork()
        val onWifi = connection.transport == Transport.WIFI && wifiNetwork != null
        val localAllowed = hasLocalNetworkPermission()
        // The router and other home devices are reached over the Wi-Fi link itself; internet
        // targets go the way apps go, which is through the VPN when one is active.
        val localPinger = Pinger(if (onWifi) wifiNetwork else connectivity.defaultNetwork())
        val internetPinger = Pinger(connectivity.defaultNetwork())

        // ---- Wi-Fi ----
        var scan: WifiScan? = null
        val rssiSamples = async { if (onWifi) wifi.sampleRssi(count = 12, intervalMs = 500) else emptyList() }
        if (onWifi) {
            update(ScanStep.WIFI, StepStatus.RUNNING, "Scanning nearby Wi-Fi networks")
            scan = wifi.scan()
            update(ScanStep.WIFI, StepStatus.DONE)
        } else {
            update(ScanStep.WIFI, StepStatus.SKIPPED)
        }

        // ---- Latency ----
        update(ScanStep.LATENCY, StepStatus.RUNNING, "Measuring latency to the router and the internet")
        var gatewayPing: PingResult? = null
        var internetPings: List<PingResult> = emptyList()
        withLowLatencyWifi {
            val gateway = connection.gatewayV4?.takeIf { onWifi && localAllowed }
            val gatewayJob = gateway?.let { async { localPinger.ping(it, "Router", tcpPorts = listOf(53, 80, 443)) } }
            val internetJobs = INTERNET_TARGETS.map { (address, label) -> async { internetPinger.ping(address, label) } }
            gatewayPing = gatewayJob?.await()
            internetPings = internetJobs.awaitAll()
        }
        update(ScanStep.LATENCY, StepStatus.DONE)

        // ---- DNS ----
        update(ScanStep.DNS, StepStatus.RUNNING, "Timing DNS servers")
        val dns = benchmarkDns(connection, if (onWifi) wifiNetwork else connectivity.defaultNetwork())
        update(ScanStep.DNS, StepStatus.DONE)

        // ---- Devices and router ----
        var lan: LanScanResult? = null
        var gatewayInfo: GatewayInfo? = null
        var path: List<PathHop> = emptyList()
        val ownCidr = connection.ipv4Addresses.firstOrNull()
        if (onWifi && localAllowed && ownCidr != null) {
            val scanner = LanScanner(AndroidLanTransport(wifiNetwork, localPinger))
            val description = if (options.deviceScan) {
                update(ScanStep.DEVICES, StepStatus.RUNNING, "Looking for devices on the network")
                lan = scanner.scan(ownCidr, connection.gatewayV4, connection.dnsServers.filter(IpUtils::isIpv4))
                update(ScanStep.DEVICES, StepStatus.DONE)
                lan.gatewayDescription
            } else {
                update(ScanStep.DEVICES, StepStatus.SKIPPED)
                null
            }
            update(ScanStep.ROUTER, StepStatus.RUNNING, "Asking the router about its internet connection")
            val pathJob = async { internetPinger.firstHops(INTERNET_TARGETS.first().first) }
            val router = description ?: scanner.findGateway()
            // The phone stays quiet here so the router's counters show other devices' traffic.
            gatewayInfo = router?.let { scanner.queryGateway(it, trafficSampleMs = 3_000) }
            path = pathJob.await()
            update(ScanStep.ROUTER, StepStatus.DONE)
        } else {
            update(ScanStep.DEVICES, StepStatus.SKIPPED)
            update(ScanStep.ROUTER, StepStatus.SKIPPED)
        }

        // ---- Speed ----
        var speed: SpeedTestResult? = null
        if (options.speedTest) {
            update(ScanStep.SPEED, StepStatus.RUNNING, "Running the speed test")
            speed = runSpeedTest(options.maxSpeedTestBytes) { live ->
                progress = progress.copy(speed = live)
                onProgress(progress)
            }
            update(ScanStep.SPEED, StepStatus.DONE)
        } else {
            update(ScanStep.SPEED, StepStatus.SKIPPED)
        }

        // ---- Assemble ----
        update(ScanStep.ANALYSIS, StepStatus.RUNNING, "Working out what it all means")
        val link = if (onWifi) wifi.currentLink(scan?.accessPoints.orEmpty(), rssiSamples.await()) else null
        val snapshot = NetworkSnapshot(
            timestampMs = System.currentTimeMillis(),
            connection = connection,
            wifiLink = link,
            wifiCapabilities = if (onWifi) wifi.capabilities() else null,
            wifiScan = scan,
            gatewayPing = gatewayPing,
            internetPings = internetPings,
            dns = dns,
            speed = speed,
            path = path,
            gateway = gatewayInfo,
            devices = lan?.devices,
            localNetworkAccess = !onWifi || (localAllowed && !localPinger.localAccessDenied),
        )
        update(ScanStep.ANALYSIS, StepStatus.DONE, "Done")
        ScanOutcome(snapshot, lan)
    }

    /** Runs only the speed test, reporting live readings. */
    suspend fun runSpeedTest(maxBytes: Long = Long.MAX_VALUE, onLive: (SpeedLive) -> Unit): SpeedTestResult {
        var live = SpeedLive()
        val engine = SpeedTestEngine(OkHttpClient(), SpeedTestConfig(maxBytesPerDirection = maxBytes))
        return engine.run { event ->
            live = when (event) {
                is SpeedTestProgress.PhaseStarted -> live.copy(phase = event.phase, currentMbps = 0.0, fraction = 0.0, series = emptyList())
                is SpeedTestProgress.LatencySample -> live.copy(latencyMs = event.latencyMs, fraction = event.index.toDouble() / event.total)
                is SpeedTestProgress.Throughput -> live.copy(
                    phase = event.phase,
                    currentMbps = event.currentMbps,
                    fraction = event.fraction,
                    series = (live.series + event.currentMbps).takeLast(SERIES_POINTS),
                )
            }
            onLive(live)
        }
    }

    /** Scans for devices only (the Devices tab). Returns null when not possible. */
    suspend fun scanDevices(onProgress: (String) -> Unit): LanScanResult? {
        val connection = connectivity.snapshot()
        val wifiNetwork = connectivity.wifiNetwork() ?: return null
        val ownCidr = connection.ipv4Addresses.firstOrNull() ?: return null
        if (connection.transport != Transport.WIFI || !hasLocalNetworkPermission()) return null
        val scanner = LanScanner(AndroidLanTransport(wifiNetwork, Pinger(wifiNetwork)))
        return scanner.scan(ownCidr, connection.gatewayV4, connection.dnsServers.filter(IpUtils::isIpv4), onProgress)
    }

    private suspend fun benchmarkDns(connection: ConnectionInfo, network: Network?): DnsBenchmarkResult {
        val system = connection.dnsServers.distinct().mapIndexed { index, address ->
            DnsServer(if (address == connection.gatewayV4) "Your router" else "Network DNS ${index + 1}", address, isSystem = true)
        }
        val public = DnsBenchmark.PUBLIC_SERVERS.filter { candidate -> system.none { it.address == candidate.address } }
        val benchmark = DnsBenchmark(socketFactory = {
            DatagramSocket().also { socket ->
                try {
                    network?.bindSocket(socket)
                } catch (e: Exception) {
                    socket.close()
                    throw e
                }
            }
        })
        return DnsBenchmarkResult(benchmark.run(system + public))
    }

    /**
     * Holds a low-latency Wi-Fi lock while [block] runs. Without it the phone's Wi-Fi radio
     * dozes between packets to save power, which adds tens of milliseconds to every ping and
     * would be mistaken for a network problem. The lock only has an effect while the screen
     * is on and the app is in the foreground.
     */
    private suspend fun withLowLatencyWifi(block: suspend () -> Unit) {
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        val lock = try {
            wifiManager?.createWifiLock(mode, "NetworkAnalyst:latency")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: RuntimeException) {
            null
        }
        try {
            block()
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: RuntimeException) {
            }
        }
    }

    companion object {
        const val API_LOCAL_NETWORK_PERMISSION = 37
        private const val SERIES_POINTS = 60

        /** Well-connected public anycast addresses used as "the internet". */
        val INTERNET_TARGETS = listOf("1.1.1.1" to "Cloudflare (1.1.1.1)", "8.8.8.8" to "Google (8.8.8.8)")
    }
}
