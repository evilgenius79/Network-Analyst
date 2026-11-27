package com.networkanalyst.core.report

import com.networkanalyst.core.analysis.DiagnosticEngine
import com.networkanalyst.core.model.ConnectionInfo
import com.networkanalyst.core.model.NetworkSnapshot
import com.networkanalyst.core.model.PingMethod
import com.networkanalyst.core.model.PingResult
import com.networkanalyst.core.model.SpeedTestResult
import com.networkanalyst.core.model.Transport
import com.networkanalyst.core.model.WifiLink
import com.networkanalyst.core.model.WifiStandard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReportTest {
    @Test
    fun `report lists measurements, problems with fixes, and healthy checks`() {
        val snapshot = NetworkSnapshot(
            connection = ConnectionInfo(transport = Transport.WIFI, isValidated = true),
            wifiLink = WifiLink(ssid = "Home", bssid = "a4:11:22:33:44:50", rssiDbm = -78, frequencyMhz = 5180, txLinkSpeedMbps = 86, rxLinkSpeedMbps = 130, standard = WifiStandard.AC, channelWidthMhz = 80),
            gatewayPing = PingResult("192.168.1.1", "Router", PingMethod.ICMP, 10, List(10) { 3.0 }),
            internetPings = listOf(PingResult("1.1.1.1", "Cloudflare", PingMethod.ICMP, 10, emptyList())),
            speed = SpeedTestResult(downloadMbps = 42.5, uploadMbps = 9.25, idleLatencyMs = 15.0, idleJitterMs = 2.0, downloadLoadedLatencyMs = 20.0, uploadLoadedLatencyMs = 110.0, serverLocation = "Cloudflare IAD"),
        )
        val report = TextReport.build(snapshot, DiagnosticEngine.diagnose(snapshot))
        val lines = report.lines()
        assertEquals("Network Analyst report", lines[0])
        assertTrue(report, report.contains("Wi-Fi: Home, 5 GHz channel 36 (80 MHz), signal -78 dBm, Wi-Fi 5 (802.11ac), link 130 Mbps down / 86 Mbps up"))
        assertTrue(report.contains("Ping Router (192.168.1.1): 3.0 ms median, jitter 0.0 ms, 0% loss"))
        assertTrue(report.contains("Ping Cloudflare (1.1.1.1): no reply"))
        assertTrue(report.contains("Download: 42.5 Mbps"))
        assertTrue(report.contains("Upload: 9.3 Mbps"))
        assertTrue(report.contains("Latency under load: +95 ms (grade C)"))
        assertTrue(report.contains("1. [Problem] Very weak Wi-Fi signal"))
        assertTrue(report.contains("   - Move closer to the router"))
        assertTrue(report.contains("CHECKED AND FINE"))
        assertTrue(report.contains("- Link to the router is healthy"))
        assertTrue(report.endsWith("\n"))
    }

    @Test
    fun `clean bill of health`() {
        val snapshot = NetworkSnapshot(
            connection = ConnectionInfo(transport = Transport.WIFI, isValidated = true),
            gatewayPing = PingResult("192.168.1.1", "Router", PingMethod.ICMP, 10, List(10) { 3.0 }),
        )
        val report = TextReport.build(snapshot, DiagnosticEngine.diagnose(snapshot))
        assertTrue(report.contains("No problems found."))
    }
}
