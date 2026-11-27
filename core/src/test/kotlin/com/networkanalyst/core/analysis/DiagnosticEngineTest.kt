package com.networkanalyst.core.analysis

import com.networkanalyst.core.model.AccessPoint
import com.networkanalyst.core.model.ConnectionInfo
import com.networkanalyst.core.model.DnsBenchmarkResult
import com.networkanalyst.core.model.DnsServerResult
import com.networkanalyst.core.model.GatewayInfo
import com.networkanalyst.core.model.LanDevice
import com.networkanalyst.core.model.NetworkSnapshot
import com.networkanalyst.core.model.PathHop
import com.networkanalyst.core.model.PingMethod
import com.networkanalyst.core.model.PingResult
import com.networkanalyst.core.model.ScanUnavailableReason
import com.networkanalyst.core.model.SpeedTestResult
import com.networkanalyst.core.model.Transport
import com.networkanalyst.core.model.WifiCapabilities
import com.networkanalyst.core.model.WifiLink
import com.networkanalyst.core.model.WifiScan
import com.networkanalyst.core.model.WifiSecurity
import com.networkanalyst.core.model.WifiStandard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEngineTest {
    // ---- builders for a healthy home network; each test bends one thing ----

    private val wifiConnection = ConnectionInfo(
        transport = Transport.WIFI,
        isValidated = true,
        ipv4Addresses = listOf("192.168.1.23/24"),
        gatewayV4 = "192.168.1.1",
        dnsServers = listOf("192.168.1.1"),
    )

    private fun ping(target: String, label: String, sent: Int, vararg rtts: Double) =
        PingResult(target, label, PingMethod.ICMP, sent, rtts.toList())

    private fun steadyPing(target: String, label: String, ms: Double, sent: Int = 20, lost: Int = 0) =
        PingResult(target, label, PingMethod.ICMP, sent, List(sent - lost) { ms + (it % 3) * 0.4 })

    private fun myAp(freq: Int = 5180, rssi: Int = -52, width: Int = 80, ssid: String = "Home", bssid: String = "a4:11:22:33:44:50") =
        AccessPoint(ssid, bssid, rssi, freq, width, standard = WifiStandard.AX, security = WifiSecurity.WPA2, isConnected = true)

    private fun neighbour(n: Int, freq: Int, rssi: Int, width: Int = 20, ssid: String = "Neighbour$n") =
        AccessPoint(ssid, "10:00:00:00:%02x:01".format(n), rssi, freq, width)

    private fun link(ap: AccessPoint, tx: Int = 866, rx: Int = 866, standard: WifiStandard = WifiStandard.AX, security: WifiSecurity = WifiSecurity.WPA2, samples: List<Int>? = null) =
        WifiLink(
            ssid = ap.ssid, bssid = ap.bssid, rssiDbm = ap.rssiDbm, frequencyMhz = ap.frequencyMhz,
            txLinkSpeedMbps = tx, rxLinkSpeedMbps = rx, standard = standard, security = security,
            channelWidthMhz = ap.channelWidthMhz, rssiSamplesDbm = samples ?: List(6) { ap.rssiDbm + (it % 2) },
        )

    private fun dns(systemMs: Double = 12.0, publicMs: Double = 14.0, systemFailures: Int = 0) = DnsBenchmarkResult(
        listOf(
            DnsServerResult("Router", "192.168.1.1", true, 8, systemFailures, List(8 - systemFailures) { systemMs }),
            DnsServerResult("Cloudflare", "1.1.1.1", false, 8, 0, List(8) { publicMs }),
            DnsServerResult("Google", "8.8.8.8", false, 8, 0, List(8) { publicMs + 4 }),
        ),
    )

    private fun speed(down: Double? = 280.0, up: Double? = 22.0, idle: Double = 14.0, dlLoaded: Double = 19.0, ulLoaded: Double = 24.0) =
        SpeedTestResult(downloadMbps = down, uploadMbps = up, idleLatencyMs = idle, idleJitterMs = 1.2, downloadLoadedLatencyMs = dlLoaded, uploadLoadedLatencyMs = ulLoaded)

    private fun healthy(): NetworkSnapshot {
        val ap = myAp()
        return NetworkSnapshot(
            connection = wifiConnection,
            wifiLink = link(ap),
            wifiCapabilities = WifiCapabilities(supports5Ghz = true, supportsWifi5 = true, supportsWifi6 = true),
            wifiScan = WifiScan(listOf(ap, neighbour(1, 5745, -70, 80), neighbour(2, 2437, -60)), fresh = true),
            gatewayPing = steadyPing("192.168.1.1", "Router", 2.5),
            internetPings = listOf(steadyPing("1.1.1.1", "Cloudflare", 13.0), steadyPing("8.8.8.8", "Google", 16.0)),
            dns = dns(),
            speed = speed(),
            gateway = GatewayInfo(externalIp = "203.0.113.7", maxDownstreamBps = 1_000_000_000, backgroundDownBps = 40_000.0, backgroundUpBps = 12_000.0, uptimeSeconds = 400_000),
            devices = List(9) { LanDevice("192.168.1.${30 + it}") },
        )
    }

    private fun Diagnosis.ids() = findings.map { it.id }
    private fun Diagnosis.find(id: String) = findings.firstOrNull { it.id == id }
    private fun Diagnosis.problemIds() = problems.map { it.id }

    // ---- scenarios ----

    @Test
    fun `healthy network reports no problems`() {
        val diagnosis = DiagnosticEngine.diagnose(healthy())
        assertEquals(emptyList<String>(), diagnosis.problemIds())
        assertEquals(Bottleneck.NONE, diagnosis.bottleneck)
        assertEquals("No significant problems found", diagnosis.headline)
        assertTrue("score ${diagnosis.healthScore}", diagnosis.healthScore >= 95)
        for (id in listOf("wifi.signal.good", "wifi.channel.clear", "local.good", "internet.good", "dns.good", "speed.download.good", "speed.upload.good", "bufferbloat.good")) {
            assertEquals("$id should be reported as healthy", Severity.GOOD, diagnosis.find(id)?.severity)
        }
        assertTrue(diagnosis.useCases.all { it.rating == Rating.GREAT })
        assertTrue(diagnosis.useCases.first { it.name == "Video streaming" }.reason.contains("18 4K streams"))
    }

    @Test
    fun `no connection short-circuits everything`() {
        val diagnosis = DiagnosticEngine.diagnose(NetworkSnapshot())
        assertEquals(listOf("connection.none"), diagnosis.ids())
        assertEquals(Bottleneck.NO_CONNECTION, diagnosis.bottleneck)
        assertEquals(0, diagnosis.healthScore)
        assertTrue(diagnosis.useCases.isEmpty())
    }

    @Test
    fun `weak signal with a lossy slow hop blames the wifi`() {
        val ap = myAp(rssi = -79)
        val snapshot = healthy().copy(
            wifiLink = link(ap, tx = 26, rx = 39),
            wifiScan = WifiScan(listOf(ap), fresh = true),
            gatewayPing = PingResult("192.168.1.1", "Router", PingMethod.ICMP, 20, List(17) { 45.0 + it * 3 }),
            internetPings = listOf(PingResult("1.1.1.1", "Cloudflare", PingMethod.ICMP, 20, List(17) { 60.0 + it * 3 })),
            // 17 Mbps over a 39 Mbps link is close to all that link can carry.
            speed = speed(down = 17.0, up = 4.5),
        )
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        assertEquals(Severity.CRITICAL, diagnosis.find("wifi.signal.veryweak")!!.severity)
        assertEquals(Severity.CRITICAL, diagnosis.find("local.loss.high")!!.severity)
        assertNotNull(diagnosis.find("local.latency"))
        assertNotNull(diagnosis.find("wifi.linkrate.low"))
        assertNotNull(diagnosis.find("wifi.linkrate.ceiling"))
        // The internet probes lost exactly what the Wi-Fi hop lost, so the line is not blamed for loss.
        assertNull(diagnosis.find("internet.loss"))
        assertNull(diagnosis.find("internet.loss.high"))
        assertEquals(Bottleneck.WIFI, diagnosis.bottleneck)
        assertEquals("Your Wi-Fi is the weak point", diagnosis.headline)
        assertTrue(diagnosis.healthScore < 50)
        // Slow speed is explained by the Wi-Fi, and says so.
        assertTrue(diagnosis.find("speed.download.slow")!!.detail.contains("Wi-Fi problems"))
        assertTrue(diagnosis.find("wifi.signal.veryweak")!!.fixes.any { it.contains("2.4 GHz band reaches further") })
    }

    @Test
    fun `good wifi with a bad line blames the internet line`() {
        val snapshot = healthy().copy(
            internetPings = listOf(
                PingResult("1.1.1.1", "Cloudflare", PingMethod.ICMP, 20, List(18) { 120.0 + (it % 4) * 2 }),
                PingResult("8.8.8.8", "Google", PingMethod.ICMP, 20, List(17) { 130.0 + (it % 4) * 2 }),
            ),
            speed = speed(down = 3.2, up = 0.6),
        )
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        assertEquals(Severity.CRITICAL, diagnosis.find("internet.loss.high")!!.severity)
        assertEquals(Severity.WARNING, diagnosis.find("internet.latency")!!.severity)
        assertEquals(Severity.CRITICAL, diagnosis.find("speed.download.veryslow")!!.severity)
        assertEquals(Severity.CRITICAL, diagnosis.find("speed.upload.veryslow")!!.severity)
        assertEquals(Bottleneck.INTERNET_LINE, diagnosis.bottleneck)
        assertTrue(diagnosis.summary.startsWith("Your Wi-Fi link looks fine."))
        assertTrue(diagnosis.find("speed.download.veryslow")!!.detail.contains("Wi-Fi link looks healthy"))
        assertTrue(diagnosis.find("internet.latency")!!.detail.contains("to your router"))
        assertEquals(Rating.POOR, diagnosis.useCases.first { it.name == "Video calls" }.rating)
        assertEquals(Rating.POOR, diagnosis.useCases.first { it.name == "Online gaming" }.rating)
    }

    @Test
    fun `validated flag off with reachable router points past the router`() {
        val snapshot = healthy().copy(connection = wifiConnection.copy(isValidated = false), internetPings = emptyList(), speed = null, dns = null)
        val finding = DiagnosticEngine.diagnose(snapshot).find("connection.nointernet")!!
        assertEquals(Severity.CRITICAL, finding.severity)
        assertTrue(finding.detail.contains("beyond the router"))
        assertEquals(Bottleneck.INTERNET_LINE, DiagnosticEngine.diagnose(snapshot).bottleneck)
    }

    @Test
    fun `crowded 2_4 GHz channel recommends a clear one`() {
        val ap = myAp(freq = 2437, rssi = -55, width = 20)
        val aps = listOf(ap, neighbour(1, 2437, -58), neighbour(2, 2437, -61), neighbour(3, 2437, -66), neighbour(4, 2432, -63), neighbour(5, 2412, -84))
        val snapshot = healthy().copy(wifiLink = link(ap, tx = 144, rx = 144), wifiScan = WifiScan(aps, fresh = true), speed = speed(down = 38.0))
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        val finding = diagnosis.find("wifi.channel.crowded")!!
        assertEquals("Wi-Fi channel 6 is crowded", finding.title)
        assertTrue(finding.detail, finding.detail.contains("3 other networks on the same channel and 1 overlapping"))
        assertTrue(finding.detail.contains("Channel 11 is much quieter"))
        assertTrue(finding.fixes.first().contains("change the 2.4 GHz channel to 11"))
        // No 5 GHz network of ours in sight: a note, not a warning.
        assertEquals(Severity.INFO, diagnosis.find("wifi.band.24only")!!.severity)
        assertEquals(Bottleneck.WIFI, diagnosis.bottleneck)
    }

    @Test
    fun `off-grid channel and 40 MHz width on 2_4 GHz are both flagged`() {
        val ap = myAp(freq = 2422, rssi = -50, width = 40).copy(centerFreq0Mhz = 2432)
        val snapshot = healthy().copy(wifiLink = link(ap, tx = 300, rx = 300), wifiScan = WifiScan(listOf(ap, neighbour(1, 2412, -70)), fresh = true))
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        assertEquals("Router is on an overlapping Wi-Fi channel", diagnosis.find("wifi.channel.crowded")!!.title)
        assertNotNull(diagnosis.find("wifi.width.24wide"))
    }

    @Test
    fun `2_4 GHz while own 5 GHz is in range is a warning`() {
        val ap = myAp(freq = 2462, rssi = -58, width = 20)
        val sibling = AccessPoint("Home", "a4:11:22:33:44:54", -61, 5180, 80)
        val snapshot = healthy().copy(wifiLink = link(ap, tx = 144, rx = 144), wifiScan = WifiScan(listOf(ap, sibling), fresh = true), speed = speed(down = 45.0))
        val finding = DiagnosticEngine.diagnose(snapshot).find("wifi.band.slow")!!
        assertEquals(Severity.WARNING, finding.severity)
        assertTrue(finding.detail.contains("5 GHz at -61 dBm"))
    }

    @Test
    fun `sticky client on a distant mesh node`() {
        val far = myAp(rssi = -76, bssid = "a4:11:22:33:44:50")
        val near = AccessPoint("Home", "c8:aa:bb:cc:dd:10", -48, 5745, 80)
        val snapshot = healthy().copy(wifiLink = link(far, tx = 86, rx = 86), wifiScan = WifiScan(listOf(far, near), fresh = true))
        val finding = DiagnosticEngine.diagnose(snapshot).find("wifi.roaming.sticky")!!
        assertTrue(finding.detail.contains("-76 dBm"))
        assertTrue(finding.detail.contains("-48 dBm"))
    }

    @Test
    fun `a single lost probe is not treated as packet loss`() {
        val oneLost = healthy().copy(
            gatewayPing = steadyPing("192.168.1.1", "Router", 2.5, sent = 40, lost = 1),
            internetPings = listOf(steadyPing("1.1.1.1", "Cloudflare", 13.0, sent = 40, lost = 1), steadyPing("8.8.8.8", "Google", 16.0, sent = 40, lost = 1)),
        )
        val diagnosis = DiagnosticEngine.diagnose(oneLost)
        assertEquals(emptyList<String>(), diagnosis.problemIds())
        assertEquals(Severity.GOOD, diagnosis.find("local.good")!!.severity)
        assertTrue(diagnosis.useCases.all { it.rating == Rating.GREAT })

        // Two lost of forty (5%) is worth a warning; four (10%) is serious.
        val twoLost = healthy().copy(gatewayPing = steadyPing("192.168.1.1", "Router", 2.5, sent = 40, lost = 2))
        val warning = DiagnosticEngine.diagnose(twoLost).find("local.loss")!!
        assertEquals(Severity.WARNING, warning.severity)
        assertTrue(warning.detail, warning.detail.startsWith("2 of 40 test packets (5%)"))
        val fourLost = healthy().copy(gatewayPing = steadyPing("192.168.1.1", "Router", 2.5, sent = 40, lost = 4))
        assertEquals(Severity.CRITICAL, DiagnosticEngine.diagnose(fourLost).find("local.loss.high")!!.severity)
    }

    @Test
    fun `loss on only one internet target does not blame the line`() {
        val snapshot = healthy().copy(
            internetPings = listOf(steadyPing("1.1.1.1", "Cloudflare", 13.0, sent = 40, lost = 6), steadyPing("8.8.8.8", "Google", 16.0, sent = 40)),
        )
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        assertNull(diagnosis.find("internet.loss"))
        assertNull(diagnosis.find("internet.loss.high"))
    }

    @Test
    fun `health score shrinks by a share per finding instead of bottoming out`() {
        // One warning.
        assertEquals(88, DiagnosticEngine.diagnose(healthy().copy(dns = dns(systemMs = 180.0))).healthScore)
        // One critical.
        assertEquals(70, DiagnosticEngine.diagnose(healthy().copy(speed = speed(idle = 14.0, ulLoaded = 500.0))).healthScore)
        // Even a long list of problems leaves a score above the floor.
        val ap = myAp(freq = 2437, rssi = -79, width = 20)
        val bad = healthy().copy(
            wifiLink = link(ap, tx = 26, rx = 39),
            wifiScan = WifiScan(listOf(ap, neighbour(1, 2437, -58), neighbour(2, 2437, -61), neighbour(3, 2437, -66)), fresh = true),
            gatewayPing = steadyPing("192.168.1.1", "Router", 45.0, sent = 40, lost = 6),
            dns = dns(systemMs = 180.0),
            speed = speed(down = 3.0, up = 0.5, idle = 30.0, ulLoaded = 500.0),
        )
        val score = DiagnosticEngine.diagnose(bad).healthScore
        assertTrue("score $score", score in 2..25)
    }

    @Test
    fun `5 GHz in sight but too weak is explained, not called missing`() {
        val ap = myAp(freq = 2437, rssi = -68, width = 20)
        val weak5 = AccessPoint("Home", "a4:11:22:33:44:54", -78, 5180, 80)
        val snapshot = healthy().copy(wifiLink = link(ap, tx = 144, rx = 144), wifiScan = WifiScan(listOf(ap, weak5), fresh = true), speed = speed(down = 45.0))
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        val finding = diagnosis.find("wifi.band.5weak")!!
        assertEquals(Severity.INFO, finding.severity)
        assertTrue(finding.detail.contains("only at -78 dBm"))
        assertNull(diagnosis.find("wifi.band.24only"))
        assertNull(diagnosis.find("wifi.band.slow"))
    }

    @Test
    fun `fluctuating signal is reported`() {
        val ap = myAp(rssi = -60)
        val snapshot = healthy().copy(wifiLink = link(ap, samples = listOf(-52, -66, -55, -70, -51, -68)))
        val finding = DiagnosticEngine.diagnose(snapshot).find("wifi.signal.unstable")!!
        assertTrue(finding.detail.contains("between -70 and -51 dBm"))
    }

    @Test
    fun `legacy standard and TKIP security`() {
        val ap = myAp(freq = 2437, rssi = -50, width = 20)
        val snapshot = healthy().copy(
            wifiLink = link(ap, tx = 54, rx = 54, standard = WifiStandard.LEGACY, security = WifiSecurity.WPA),
            wifiScan = WifiScan(listOf(ap), fresh = true),
            speed = speed(down = 21.0),
        )
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        assertEquals(Severity.WARNING, diagnosis.find("wifi.standard.legacy")!!.severity)
        assertEquals(Severity.WARNING, diagnosis.find("wifi.security.legacy")!!.severity)
        assertTrue(diagnosis.find("wifi.security.legacy")!!.detail.contains("54 Mbps"))
        assertEquals(Bottleneck.WIFI, diagnosis.bottleneck)
        // With the Wi-Fi itself at fault, slow speed is not pinned on the provider.
        assertFalse(diagnosis.find("speed.download.slow")!!.detail.contains("looks healthy"))
    }

    @Test
    fun `open network is a security warning`() {
        val ap = myAp()
        val snapshot = healthy().copy(wifiLink = link(ap, security = WifiSecurity.OPEN))
        assertEquals(Category.SECURITY, DiagnosticEngine.diagnose(snapshot).find("wifi.security.open")!!.category)
    }

    @Test
    fun `scan blocked by missing permission explains how to enable it`() {
        val snapshot = healthy().copy(wifiScan = WifiScan(unavailableReason = ScanUnavailableReason.LOCATION_PERMISSION_MISSING))
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        val finding = diagnosis.find("wifi.channel.unavailable")!!
        assertEquals(Severity.INFO, finding.severity)
        assertTrue(finding.fixes.single().contains("Location permission"))
        assertEquals(emptyList<String>(), diagnosis.problemIds())
    }

    @Test
    fun `severe bufferbloat on upload`() {
        val snapshot = healthy().copy(speed = speed(idle = 14.0, dlLoaded = 40.0, ulLoaded = 430.0))
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        val finding = diagnosis.find("bufferbloat.severe")!!
        assertEquals(Severity.CRITICAL, finding.severity)
        assertTrue(finding.detail, finding.detail.contains("rises by 416 ms while uploading at full speed (grade F)"))
        assertTrue(finding.fixes.first().contains("Smart Queue Management"))
        assertEquals(Bottleneck.BUFFERBLOAT, diagnosis.bottleneck)
        assertEquals(Rating.OK, diagnosis.useCases.first { it.name == "Video calls" }.rating)
        assertEquals(Rating.OK, diagnosis.useCases.first { it.name == "Online gaming" }.rating)
    }

    @Test
    fun `moderate bufferbloat is a warning`() {
        val snapshot = healthy().copy(speed = speed(idle = 14.0, dlLoaded = 110.0, ulLoaded = 95.0))
        val finding = DiagnosticEngine.diagnose(snapshot).find("bufferbloat.moderate")!!
        assertEquals(Severity.WARNING, finding.severity)
        assertTrue(finding.detail.contains("grade C"))
    }

    @Test
    fun `slow dns with a faster public resolver`() {
        val snapshot = healthy().copy(dns = dns(systemMs = 180.0, publicMs = 15.0))
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        val finding = diagnosis.find("dns.slow")!!
        assertEquals(Severity.WARNING, finding.severity)
        assertTrue(finding.detail.contains("takes 180 ms"))
        assertTrue(finding.detail.contains("Cloudflare answers in 15 ms"))
        assertTrue(finding.fixes.first().contains("Cloudflare (1.1.1.1)"))
        assertTrue(finding.fixes[1].contains("one.one.one.one"))
        assertEquals(Bottleneck.DNS, diagnosis.bottleneck)
    }

    @Test
    fun `somewhat slower dns is only a suggestion`() {
        val snapshot = healthy().copy(dns = dns(systemMs = 55.0, publicMs = 12.0))
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        assertEquals(Severity.INFO, diagnosis.find("dns.faster_available")!!.severity)
        assertEquals(Bottleneck.NONE, diagnosis.bottleneck)
    }

    @Test
    fun `dead and failing dns servers`() {
        val dead = DiagnosticEngine.diagnose(healthy().copy(dns = dns(systemFailures = 8)))
        assertEquals(Severity.CRITICAL, dead.find("dns.dead")!!.severity)
        val failing = DiagnosticEngine.diagnose(healthy().copy(dns = dns(systemFailures = 3)))
        assertEquals(Severity.CRITICAL, failing.find("dns.failing")!!.severity)
        assertTrue(failing.find("dns.failing")!!.detail.contains("38%"))
    }

    @Test
    fun `private dns is mentioned alongside dns results`() {
        val snapshot = healthy().copy(connection = wifiConnection.copy(privateDnsActive = true), dns = dns(systemMs = 150.0))
        assertTrue(DiagnosticEngine.diagnose(snapshot).find("dns.slow")!!.detail.contains("Private DNS is on"))
    }

    @Test
    fun `double nat from the router's own report`() {
        val snapshot = healthy().copy(gateway = GatewayInfo(externalIp = "192.168.0.14"))
        val finding = DiagnosticEngine.diagnose(snapshot).find("nat.double")!!
        assertEquals(Severity.WARNING, finding.severity)
        assertTrue(finding.detail.contains("192.168.0.14"))
        assertTrue(finding.fixes.first().contains("bridge"))
    }

    @Test
    fun `double nat inferred from the path when the router says nothing`() {
        val snapshot = healthy().copy(
            gateway = null,
            path = listOf(PathHop(1, "192.168.1.1", 2.0), PathHop(2, "10.0.0.1", 3.0), PathHop(3, "203.0.113.1", 9.0)),
        )
        assertNotNull(DiagnosticEngine.diagnose(snapshot).find("nat.double"))
        // A single private hop is just the normal home router.
        val single = healthy().copy(gateway = null, path = listOf(PathHop(1, "192.168.1.1", 2.0), PathHop(2, "203.0.113.1", 9.0)))
        assertNull(DiagnosticEngine.diagnose(single).find("nat.double"))
    }

    @Test
    fun `carrier grade nat is informational`() {
        val snapshot = healthy().copy(gateway = GatewayInfo(externalIp = "100.72.14.9"))
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        assertEquals(Severity.INFO, diagnosis.find("nat.cgnat")!!.severity)
        assertNull(diagnosis.find("nat.double"))
    }

    @Test
    fun `background traffic from other devices`() {
        val snapshot = healthy().copy(
            gateway = GatewayInfo(externalIp = "203.0.113.7", backgroundDownBps = 96_000_000.0, backgroundUpBps = 9_500_000.0),
        )
        val finding = DiagnosticEngine.diagnose(snapshot).find("router.background")!!
        assertEquals(Severity.WARNING, finding.severity)
        assertTrue(finding.detail, finding.detail.contains("96 Mbps down and 9.5 Mbps up"))
        // Idle chatter is not reported.
        assertNull(DiagnosticEngine.diagnose(healthy()).find("router.background"))
    }

    @Test
    fun `wan port stuck at 100 Mbps`() {
        val snapshot = healthy().copy(
            gateway = GatewayInfo(manufacturer = "TestCorp", modelName = "TR-1000", externalIp = "203.0.113.7", maxDownstreamBps = 100_000_000),
            speed = speed(down = 94.0),
        )
        val finding = DiagnosticEngine.diagnose(snapshot).find("router.wanlink")!!
        assertTrue(finding.title.contains("only 100 Mbps"))
        assertTrue(finding.detail.startsWith("TestCorp TR-1000 reports"))
        assertTrue(finding.fixes.first().contains("Cat 5e"))
        // A gigabit WAN port with the same speed is not flagged.
        assertNull(DiagnosticEngine.diagnose(healthy().copy(speed = speed(down = 94.0))).find("router.wanlink"))
    }

    @Test
    fun `long router uptime is only mentioned when something is wrong`() {
        val longUp = GatewayInfo(externalIp = "203.0.113.7", uptimeSeconds = 90L * 86_400)
        assertNull(DiagnosticEngine.diagnose(healthy().copy(gateway = longUp)).find("router.uptime"))
        val troubled = healthy().copy(gateway = longUp, speed = speed(down = 3.0))
        assertTrue(DiagnosticEngine.diagnose(troubled).find("router.uptime")!!.title.contains("90 days"))
    }

    @Test
    fun `slower than usual compared with history`() {
        val snapshot = healthy().copy(speed = speed(down = 95.0))
        assertNull(DiagnosticEngine.diagnose(snapshot).find("speed.download.belowusual"))
        val finding = DiagnosticEngine.diagnose(snapshot, SpeedBaseline(downloadMbps = 290.0)).find("speed.download.belowusual")!!
        assertTrue(finding.detail.contains("about 290 Mbps"))
        assertTrue(finding.detail.contains("measured 95 Mbps"))
    }

    @Test
    fun `speed test failure is reported without blaming anything`() {
        val snapshot = healthy().copy(speed = SpeedTestResult(error = "Could not reach the speed test server: timeout"))
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        assertEquals(Severity.INFO, diagnosis.find("speed.failed")!!.severity)
        assertEquals(Bottleneck.NONE, diagnosis.bottleneck)
    }

    @Test
    fun `vpn and mobile data are context, not faults`() {
        val vpn = DiagnosticEngine.diagnose(healthy().copy(connection = wifiConnection.copy(isVpn = true)))
        assertEquals(Severity.INFO, vpn.find("connection.vpn")!!.severity)
        assertEquals("No problems found (through the VPN)", vpn.headline)

        val mobile = NetworkSnapshot(
            connection = ConnectionInfo(transport = Transport.CELLULAR, isValidated = true, isMetered = true, cellularGeneration = "5G"),
            internetPings = listOf(steadyPing("1.1.1.1", "Cloudflare", 38.0)),
            speed = speed(down = 120.0, up = 18.0, idle = 35.0, dlLoaded = 50.0, ulLoaded = 60.0),
        )
        val diagnosis = DiagnosticEngine.diagnose(mobile)
        assertEquals("On mobile data (5G)", diagnosis.find("connection.cellular")!!.title)
        assertTrue(diagnosis.ids().none { it.startsWith("wifi.") || it.startsWith("local.") })
        assertEquals(emptyList<String>(), diagnosis.problemIds())
    }

    @Test
    fun `many devices and missing local network permission`() {
        val crowded = healthy().copy(devices = List(48) { LanDevice("192.168.1.${10 + it}") })
        assertEquals(Severity.WARNING, DiagnosticEngine.diagnose(crowded).find("devices.many")!!.severity)
        val several = healthy().copy(devices = List(28) { LanDevice("192.168.1.${10 + it}") })
        assertEquals(Severity.INFO, DiagnosticEngine.diagnose(several).find("devices.several")!!.severity)
        val blocked = healthy().copy(localNetworkAccess = false, devices = null, gatewayPing = null, gateway = null)
        assertTrue(DiagnosticEngine.diagnose(blocked).find("devices.nopermission")!!.fixes.single().contains("Nearby devices"))
    }

    @Test
    fun `findings are ordered most serious first and score reflects them`() {
        val snapshot = healthy().copy(
            dns = dns(systemMs = 180.0),
            speed = speed(down = 3.0, idle = 14.0, ulLoaded = 500.0),
        )
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        val severities = diagnosis.findings.map { it.severity.ordinal }
        assertEquals(severities.sorted(), severities)
        assertEquals(Severity.CRITICAL, diagnosis.findings.first().severity)
        // Two criticals (speed, bufferbloat) and one warning (dns): 100 x 0.70 x 0.70 x 0.88.
        assertEquals(43, diagnosis.healthScore)
        assertEquals(3, diagnosis.problems.size)
    }

    @Test
    fun `partial scans do not invent findings`() {
        // Only connection info and a ping: nothing about speed, DNS or channels may appear.
        val snapshot = NetworkSnapshot(connection = wifiConnection, gatewayPing = steadyPing("192.168.1.1", "Router", 3.0))
        val diagnosis = DiagnosticEngine.diagnose(snapshot)
        assertEquals(listOf("local.good"), diagnosis.ids())
        assertTrue(diagnosis.useCases.isEmpty())
    }

    @Test
    fun `speed text formatting`() {
        assertEquals("3.2 Mbps", DiagnosticEngine.speedText(3.24))
        assertEquals("95 Mbps", DiagnosticEngine.speedText(94.6))
        assertEquals("1.20 Gbps", DiagnosticEngine.speedText(1200.0))
    }
}
