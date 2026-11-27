package com.networkanalyst.app.ui

import com.networkanalyst.app.data.ConnectTestProgress
import com.networkanalyst.app.data.ScanProgress
import com.networkanalyst.app.data.ScanStep
import com.networkanalyst.app.data.SpeedLive
import com.networkanalyst.app.data.StepStatus
import com.networkanalyst.core.analysis.DiagnosticEngine
import com.networkanalyst.core.connect.ConnectFailure
import com.networkanalyst.core.connect.ConnectTrial
import com.networkanalyst.core.connect.ConnectionTestAnalyzer
import com.networkanalyst.core.connect.FailedStage
import com.networkanalyst.core.connect.TargetPicker
import com.networkanalyst.core.connect.TargetResult
import com.networkanalyst.core.connect.WatchAnalyzer
import com.networkanalyst.core.connect.WifiEvent
import com.networkanalyst.core.connect.WifiEventKind
import com.networkanalyst.core.discovery.LanScanResult
import com.networkanalyst.core.history.HistoryEntry
import com.networkanalyst.core.model.AccessPoint
import com.networkanalyst.core.model.ConnectionInfo
import com.networkanalyst.core.model.DeviceType
import com.networkanalyst.core.model.DnsBenchmarkResult
import com.networkanalyst.core.model.DnsServerResult
import com.networkanalyst.core.model.GatewayInfo
import com.networkanalyst.core.model.LanDevice
import com.networkanalyst.core.model.NetworkSnapshot
import com.networkanalyst.core.model.PingMethod
import com.networkanalyst.core.model.PingResult
import com.networkanalyst.core.model.SpeedTestResult
import com.networkanalyst.core.model.Transport
import com.networkanalyst.core.model.WifiCapabilities
import com.networkanalyst.core.model.WifiLink
import com.networkanalyst.core.model.WifiScan
import com.networkanalyst.core.model.WifiSecurity
import com.networkanalyst.core.model.WifiStandard
import com.networkanalyst.core.speed.SpeedPhase
import com.networkanalyst.core.wifi.ChannelAnalyzer

/** Realistic states for rendering every screen without a device. */
object SampleData {
    const val NOW = 1_795_770_000_000L

    val wifiConnection = ConnectionInfo(
        transport = Transport.WIFI, isValidated = true,
        ipv4Addresses = listOf("192.168.1.23/24"), gatewayV4 = "192.168.1.1", dnsServers = listOf("192.168.1.1"),
    )

    val permissionsGranted = PermissionsState(locationGranted = true, locationEnabled = true, localNetworkGranted = true)

    private fun ap(ssid: String, bssid: String, rssi: Int, freq: Int, width: Int = 20, standard: WifiStandard = WifiStandard.AX, security: WifiSecurity = WifiSecurity.WPA2, connected: Boolean = false) =
        AccessPoint(ssid, bssid, rssi, freq, width, standard = standard, security = security, isConnected = connected)

    /** A busy apartment-block 2.4 GHz environment with the user's router on crowded channel 6. */
    val crowdedAccessPoints = listOf(
        ap("Home", "a4:11:22:33:44:50", -71, 2437, connected = true),
        ap("Home", "a4:11:22:33:44:54", -74, 5180, 80),
        ap("Vodafone-8F2A", "10:00:00:00:01:01", -58, 2437, standard = WifiStandard.N),
        ap("NETGEAR42", "10:00:00:00:02:01", -63, 2437),
        ap("TP-Link_5C10", "10:00:00:00:03:01", -66, 2432, 40, standard = WifiStandard.N),
        ap("FRITZ!Box 7590", "10:00:00:00:04:01", -69, 2437),
        ap("", "10:00:00:00:05:01", -77, 2412, standard = WifiStandard.N),
        ap("CoffeeShop Guest", "10:00:00:00:06:01", -80, 2412, security = WifiSecurity.OPEN, standard = WifiStandard.N),
        ap("SKY-A91D", "10:00:00:00:07:01", -72, 5180, 80, standard = WifiStandard.AC),
        ap("Vodafone-8F2A", "10:00:00:00:01:05", -70, 5500, 80),
        ap("Linksys-Mesh", "10:00:00:00:08:01", -83, 5745, 80),
    )

    val crowdedLink = WifiLink(
        ssid = "Home", bssid = "a4:11:22:33:44:50", rssiDbm = -71, frequencyMhz = 2437,
        txLinkSpeedMbps = 72, rxLinkSpeedMbps = 86, maxTxLinkSpeedMbps = 286, maxRxLinkSpeedMbps = 286,
        standard = WifiStandard.AX, security = WifiSecurity.WPA2, channelWidthMhz = 20,
        rssiSamplesDbm = listOf(-70, -72, -71, -73, -70, -71, -72, -71),
    )

    private fun pings(target: String, label: String, base: Double, spread: Double, sent: Int = 40, lost: Int = 0) =
        PingResult(target, label, PingMethod.ICMP, sent, List(sent - lost) { base + (it * 7 % 5) * spread })

    /** Weak-ish signal on a crowded channel, lag under load, slow router DNS. */
    val troubledSnapshot = NetworkSnapshot(
        timestampMs = NOW,
        connection = wifiConnection,
        wifiLink = crowdedLink,
        wifiCapabilities = WifiCapabilities(supports5Ghz = true, supports6Ghz = false, supportsWifi5 = true, supportsWifi6 = true),
        wifiScan = WifiScan(crowdedAccessPoints, fresh = true),
        gatewayPing = pings("192.168.1.1", "Router", 9.0, 9.0, lost = 3),
        internetPings = listOf(pings("1.1.1.1", "Cloudflare (1.1.1.1)", 24.0, 6.0, lost = 3), pings("8.8.8.8", "Google (8.8.8.8)", 27.0, 6.0, lost = 4)),
        dns = DnsBenchmarkResult(
            listOf(
                DnsServerResult("Your router", "192.168.1.1", true, 8, 0, List(8) { 118.0 + it }),
                DnsServerResult("Cloudflare", "1.1.1.1", false, 8, 0, List(8) { 16.0 + it % 3 }),
                DnsServerResult("Google", "8.8.8.8", false, 8, 0, List(8) { 21.0 + it % 3 }),
                DnsServerResult("Quad9", "9.9.9.9", false, 8, 0, List(8) { 24.0 + it % 3 }),
            ),
        ),
        speed = SpeedTestResult(
            downloadMbps = 38.4, uploadMbps = 8.9, idleLatencyMs = 22.0, idleJitterMs = 3.1,
            downloadLoadedLatencyMs = 61.0, uploadLoadedLatencyMs = 284.0,
            bytesDownloaded = 31_500_000, bytesUploaded = 8_200_000,
            serverLocation = "Cloudflare LHR", publicIp = "203.0.113.84", asn = "AS64500", city = "London", country = "GB", timestampMs = NOW,
        ),
        gateway = GatewayInfo(manufacturer = "TestCorp", modelName = "AX1800", externalIp = "203.0.113.84", maxDownstreamBps = 1_000_000_000, backgroundDownBps = 180_000.0, backgroundUpBps = 40_000.0, uptimeSeconds = 71L * 86_400),
        devices = List(14) { LanDevice("192.168.1.${20 + it}") },
    )

    val healthySnapshot = NetworkSnapshot(
        timestampMs = NOW,
        connection = wifiConnection,
        wifiLink = WifiLink(
            ssid = "Home", bssid = "a4:11:22:33:44:54", rssiDbm = -49, frequencyMhz = 5180, txLinkSpeedMbps = 1201, rxLinkSpeedMbps = 1201,
            standard = WifiStandard.AX, security = WifiSecurity.WPA2_WPA3, channelWidthMhz = 80, rssiSamplesDbm = List(8) { -49 - it % 2 },
        ),
        wifiCapabilities = WifiCapabilities(supports5Ghz = true, supportsWifi5 = true, supportsWifi6 = true),
        wifiScan = WifiScan(
            listOf(
                ap("Home", "a4:11:22:33:44:54", -49, 5180, 80, security = WifiSecurity.WPA2_WPA3, connected = true),
                ap("Home", "a4:11:22:33:44:50", -45, 2462),
                ap("Neighbour", "10:00:00:00:09:01", -78, 5745, 80),
            ),
            fresh = true,
        ),
        gatewayPing = pings("192.168.1.1", "Router", 2.1, 0.3),
        internetPings = listOf(pings("1.1.1.1", "Cloudflare (1.1.1.1)", 9.0, 0.6), pings("8.8.8.8", "Google (8.8.8.8)", 11.0, 0.6)),
        dns = DnsBenchmarkResult(
            listOf(
                DnsServerResult("Your router", "192.168.1.1", true, 8, 0, List(8) { 11.0 + it % 2 }),
                DnsServerResult("Cloudflare", "1.1.1.1", false, 8, 0, List(8) { 10.0 + it % 2 }),
            ),
        ),
        speed = SpeedTestResult(
            downloadMbps = 512.0, uploadMbps = 104.0, idleLatencyMs = 9.0, idleJitterMs = 0.8,
            downloadLoadedLatencyMs = 13.0, uploadLoadedLatencyMs = 16.0, bytesDownloaded = 410_000_000, bytesUploaded = 95_000_000,
            serverLocation = "Cloudflare LHR", publicIp = "203.0.113.84", asn = "AS64500", timestampMs = NOW,
        ),
        gateway = GatewayInfo(externalIp = "203.0.113.84"),
        devices = List(9) { LanDevice("192.168.1.${20 + it}") },
    )

    val troubled = ScanUiState.Done(troubledSnapshot, DiagnosticEngine.diagnose(troubledSnapshot))
    val healthy = ScanUiState.Done(healthySnapshot, DiagnosticEngine.diagnose(healthySnapshot))

    val runningSpeed = ScanUiState.Running(
        ScanProgress(
            steps = mapOf(
                ScanStep.CONNECTION to StepStatus.DONE, ScanStep.WIFI to StepStatus.DONE, ScanStep.LATENCY to StepStatus.DONE,
                ScanStep.DNS to StepStatus.DONE, ScanStep.DEVICES to StepStatus.DONE, ScanStep.ROUTER to StepStatus.SKIPPED,
                ScanStep.SPEED to StepStatus.RUNNING, ScanStep.ANALYSIS to StepStatus.PENDING,
            ),
            message = "Running the speed test",
            speed = SpeedLive(SpeedPhase.DOWNLOAD, 187.4, 0.55, 14.2, listOf(20.0, 85.0, 140.0, 171.0, 182.0, 190.0, 186.0, 189.0, 187.4)),
        ),
    )

    val wifiState = WifiUiState(
        link = crowdedLink,
        rssiHistory = List(60) { -71 + ((it * 13) % 7) - 3 },
        scan = WifiScan(crowdedAccessPoints, fresh = true),
        bands = ChannelAnalyzer.analyze(crowdedAccessPoints, crowdedAccessPoints.first()),
    )

    val history = listOf(
        HistoryEntry(NOW, "wifi:Home", "Home", 38.4, 8.9, 22.0, 262.0, 45),
        HistoryEntry(NOW - 86_400_000, "wifi:Home", "Home", 61.2, 9.4, 21.0, 240.0, 55),
        HistoryEntry(NOW - 3 * 86_400_000, "mobile", "Mobile data", 142.0, 21.5, 38.0, 20.0, null),
    )

    val speedResult = SpeedUiState(result = troubledSnapshot.speed, history = history)
    val speedLive = SpeedUiState(running = true, live = SpeedLive(SpeedPhase.UPLOAD, 8.61, 0.7, 22.0, listOf(1.0, 4.0, 7.2, 8.4, 8.9, 8.7, 8.6)), history = history)

    val devices = DevicesUiState(
        result = LanScanResult(
            devices = listOf(
                LanDevice("192.168.1.1", hostname = "router", name = "Home Router", manufacturer = "TestCorp", model = "AX1800", type = DeviceType.ROUTER, isGateway = true, pingMs = 2.4, openPorts = listOf(80, 443)),
                LanDevice("192.168.1.20", hostname = "Johns-MacBook-Pro", type = DeviceType.COMPUTER, pingMs = 5.1, services = listOf("_smb._tcp")),
                LanDevice("192.168.1.23", hostname = "Pixel-8", type = DeviceType.THIS_DEVICE, isSelf = true),
                LanDevice("192.168.1.31", hostname = "chromecast-abc", name = "Living Room TV", model = "Chromecast Ultra", type = DeviceType.TV, pingMs = 8.0),
                LanDevice("192.168.1.34", name = "Kitchen speaker", manufacturer = "Sonos, Inc.", model = "One", type = DeviceType.SPEAKER, pingMs = 11.3),
                LanDevice("192.168.1.40", hostname = "HP-ENVY-6000", type = DeviceType.PRINTER, pingMs = 14.9),
                LanDevice("192.168.1.52", hostname = "Marias-iPhone", type = DeviceType.PHONE, pingMs = 38.0),
                LanDevice("192.168.1.77", pingMs = 6.2),
            ),
            subnet = "192.168.1.0/24",
            scannedHosts = 254,
        ),
    )

    // ------------------------------------------------------------------ connect tab

    /** A three-unit mesh, each unit with a 2.4 GHz and a 5 GHz radio, plus the neighbours. */
    private val meshAccessPoints = listOf(
        ap("Home", "a4:11:22:33:44:51", -47, 5180, 80, connected = true),
        ap("Home", "a4:11:22:33:44:50", -44, 2437),
        ap("Home", "a4:11:22:33:55:61", -63, 5745, 80),
        ap("Home", "a4:11:22:33:55:60", -60, 2412),
        ap("Home", "a4:11:22:33:66:71", -84, 5500, 80),
        ap("Home", "a4:11:22:33:66:70", -78, 2462),
        ap("Vodafone-8F2A", "10:00:00:00:01:01", -58, 2437, standard = WifiStandard.N),
        ap("NETGEAR42", "10:00:00:00:02:01", -66, 2437),
        ap("CoffeeShop Guest", "10:00:00:00:06:01", -80, 2412, security = WifiSecurity.OPEN, standard = WifiStandard.N),
    )

    private val connectNetworks = TargetPicker.networks(meshAccessPoints, "Home")
    private val home = connectNetworks.first()
    private val homeSelection = TargetPicker.defaultSelection(home.targets)

    val connectSetup = ConnectUiState(networks = connectNetworks, ssid = "Home", selected = homeSelection, password = "correct horse battery")

    private fun trial(bssid: String, seconds: Double, vararg retries: FailedStage) =
        ConnectTrial(bssid, success = true, durationMs = (seconds * 1000).toLong(), hiddenFailures = retries.toList())

    private fun failed(bssid: String, reason: ConnectFailure, seconds: Double, vararg retries: FailedStage) =
        ConnectTrial(bssid, success = false, failure = reason, durationMs = (seconds * 1000).toLong(), hiddenFailures = retries.toList())

    /** 2.4 GHz takes the phone every time; 5 GHz refuses it or needs retries. */
    private val connectResults = home.targets.filter { it.bssid in homeSelection }.map { target ->
        val b = target.bssid
        TargetResult(
            target,
            when (b) {
                "a4:11:22:33:44:50" -> listOf(trial(b, 1.9), trial(b, 2.2), trial(b, 1.8))
                "a4:11:22:33:55:60" -> listOf(trial(b, 2.4), trial(b, 2.1), trial(b, 2.6))
                "a4:11:22:33:66:70" -> listOf(trial(b, 3.1), trial(b, 2.9), trial(b, 3.4))
                "a4:11:22:33:44:51" -> listOf(
                    failed(b, ConnectFailure.ASSOCIATION, 21.4, FailedStage.JOINING, FailedStage.JOINING, FailedStage.JOINING),
                    trial(b, 9.7, FailedStage.JOINING),
                    failed(b, ConnectFailure.ASSOCIATION, 22.0, FailedStage.JOINING, FailedStage.JOINING, FailedStage.JOINING),
                )
                else -> listOf(
                    failed(b, ConnectFailure.ASSOCIATION, 20.8, FailedStage.JOINING, FailedStage.JOINING, FailedStage.JOINING),
                    failed(b, ConnectFailure.ASSOCIATION, 21.1, FailedStage.JOINING, FailedStage.JOINING, FailedStage.JOINING),
                    failed(b, ConnectFailure.ASSOCIATION, 20.5, FailedStage.JOINING, FailedStage.JOINING, FailedStage.JOINING),
                )
            },
        )
    }

    val connectResult = connectSetup.copy(report = ConnectionTestAnalyzer.analyze(connectResults))

    val connectAllGood = connectSetup.copy(
        report = ConnectionTestAnalyzer.analyze(connectResults.map { r -> r.copy(trials = List(3) { trial(r.target.bssid, 2.0 + it * 0.2) }) }),
    )

    val connectRunning = connectSetup.copy(
        running = true,
        progress = ConnectTestProgress(
            "Connecting to 5 GHz ch 36, attempt 2 of 3",
            done = 4,
            total = connectResults.size * 3,
            results = connectResults.mapIndexed { index, r ->
                when (index) {
                    0 -> r
                    1 -> r.copy(trials = r.trials.take(1))
                    else -> r.copy(trials = emptyList())
                }
            },
        ),
    )

    /** The phone's own behaviour: one failed join on 5 GHz, a retry that worked, later a drop. */
    private val watchEvents = run {
        var t = NOW - 26 * 60_000L
        fun at(seconds: Double, kind: WifiEventKind, value: String = "", bssid: String? = null, rssi: Int? = null): WifiEvent {
            t += (seconds * 1000).toLong()
            return WifiEvent(t, kind, value, if (bssid != null) "Home" else null, bssid, rssi, null)
        }
        listOf(
            at(0.0, WifiEventKind.LINK_UP, "Already connected when watching started", "a4:11:22:33:44:50", -46),
            at(412.0, WifiEventKind.SUPPLICANT, "DISCONNECTED", rssi = -49),
            at(0.1, WifiEventKind.LINK_DOWN, rssi = -49),
            at(1.2, WifiEventKind.SUPPLICANT, "ASSOCIATING"),
            at(4.0, WifiEventKind.SUPPLICANT, "DISCONNECTED"),
            at(2.5, WifiEventKind.SUPPLICANT, "ASSOCIATING"),
            at(4.0, WifiEventKind.SUPPLICANT, "DISCONNECTED"),
            at(6.0, WifiEventKind.SUPPLICANT, "ASSOCIATING"),
            at(0.3, WifiEventKind.SUPPLICANT, "ASSOCIATED"),
            at(0.1, WifiEventKind.SUPPLICANT, "FOUR_WAY_HANDSHAKE"),
            at(0.2, WifiEventKind.SUPPLICANT, "COMPLETED", "a4:11:22:33:44:50", -45),
            at(0.1, WifiEventKind.NETWORK, "OBTAINING_IPADDR"),
            at(1.1, WifiEventKind.NETWORK, "CONNECTED", "a4:11:22:33:44:50", -45),
            at(0.3, WifiEventKind.LINK_UP, "", "a4:11:22:33:44:50", -45),
        )
    }

    val watchQuiet = WatchUiState()
    val watchBusy = WatchUiState(WatchAnalyzer.analyze(watchEvents), watchEvents.asReversed(), watchEvents.size, background = true)
}
