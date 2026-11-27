package com.networkanalyst.core.connect

import com.networkanalyst.core.analysis.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchReportTest {
    private fun s(ms: Long, state: String) = WifiEvent(ms, WifiEventKind.SUPPLICANT, state)
    private fun n(ms: Long, state: String) = WifiEvent(ms, WifiEventKind.NETWORK, state)

    private fun good(at: Long) = listOf(s(at, "ASSOCIATING"), s(at + 100, "ASSOCIATED"), s(at + 200, "FOUR_WAY_HANDSHAKE"), s(at + 300, "COMPLETED"), n(at + 900, "CONNECTED"))

    @Test
    fun `nothing seen yet`() {
        assertEquals("Watching. Nothing has happened yet.", WatchAnalyzer.analyze(emptyList()).headline)
        assertEquals(
            "Watching. No connection attempts or drops seen yet.",
            WatchAnalyzer.analyze(listOf(s(0, "SCANNING"))).headline,
        )
    }

    @Test
    fun `all good`() {
        val report = WatchAnalyzer.analyze(good(0) + listOf(WifiEvent(60_000, WifiEventKind.WIFI_SWITCH, "off")) + good(120_000))
        assertEquals("2 connection attempts seen, all successful.", report.headline)
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `failures are grouped by stage with what android flagged`() {
        val events = listOf(s(0, "ASSOCIATING"), s(100, "ASSOCIATED"), s(200, "FOUR_WAY_HANDSHAKE"), WifiEvent(250, WifiEventKind.AUTH_ERROR, "TIMEOUT"), s(300, "DISCONNECTED")) +
            listOf(s(5_000, "ASSOCIATING"), s(5_100, "ASSOCIATED"), s(5_200, "FOUR_WAY_HANDSHAKE"), s(5_300, "DISCONNECTED")) +
            listOf(s(9_000, "ASSOCIATING"), s(9_200, "DISCONNECTED")) +
            good(12_000)
        val report = WatchAnalyzer.analyze(events)
        assertEquals("3 of 4 connection attempts failed.", report.headline)
        val keys = report.findings.first { it.id == "watch.failed.key_exchange" }
        assertEquals("2 attempts stopped at \"Exchanging encryption keys\"", keys.title)
        assertTrue(keys.detail.contains("Android flagged 1 of them as an authentication timeout."))
        assertEquals("1 attempt stopped at \"Joining the access point\"", report.findings.first { it.id == "watch.failed.joining" }.title)
        assertNull(report.findings.firstOrNull { it.id == "watch.coarse" })
    }

    @Test
    fun `drops report how long the connection lasted and whether signal explains them`() {
        fun session(start: Long, lengthMs: Long, rssi: Int) = listOf(
            WifiEvent(start, WifiEventKind.LINK_UP, bssid = "aa:bb:cc:00:00:01", rssiDbm = -55),
            WifiEvent(start + lengthMs - 1000, WifiEventKind.NOTE, "signal", rssiDbm = rssi),
            WifiEvent(start + lengthMs, WifiEventKind.LINK_DOWN),
        )
        val strong = WatchAnalyzer.analyze(session(0, 300_000, -58) + session(400_000, 240_000, -61) + session(700_000, 360_000, -57))
        val finding = strong.findings.single()
        assertEquals("The connection dropped 3 times", finding.title)
        assertEquals(Severity.WARNING, finding.severity)
        assertTrue(finding.detail, finding.detail.startsWith("Typically after 5 minutes connected."))
        assertTrue(finding.detail.contains("signal was fine each time (-61 dBm or better)"))

        val weak = WatchAnalyzer.analyze(session(0, 60_000, -82))
        assertEquals(Severity.INFO, weak.findings.single().severity)
        assertTrue(weak.findings.single().detail.contains("look like range"))
    }

    @Test
    fun `failed hand-over between access points`() {
        val events = listOf(WifiEvent(0, WifiEventKind.LINK_UP, bssid = "aa:bb:cc:00:00:01")) +
            listOf(s(60_000, "ASSOCIATING"), s(60_100, "ASSOCIATED"), s(60_200, "DISCONNECTED"), n(60_300, "DISCONNECTED"))
        val report = WatchAnalyzer.analyze(events)
        assertNotNull(report.findings.firstOrNull { it.id == "watch.roams" })
        assertNotNull(report.findings.firstOrNull { it.id == "watch.drops" })
    }

    @Test
    fun `coarse phones are explained`() {
        val report = WatchAnalyzer.analyze(listOf(n(0, "CONNECTING"), n(800, "DISCONNECTED")))
        assertNotNull(report.findings.firstOrNull { it.id == "watch.coarse" })
        assertEquals("1 attempt stopped at \"Joining or exchanging keys\"", report.findings.first { it.id.startsWith("watch.failed") }.title)
    }

    @Test
    fun `the app's own test connections are not the phone's behaviour`() {
        val test = listOf(WifiEvent(1_000, WifiEventKind.TEST_START, "Home"), s(1_100, "ASSOCIATING"), s(1_200, "DISCONNECTED"), WifiEvent(2_000, WifiEventKind.TEST_END))
        assertEquals("Watching. No connection attempts or drops seen yet.", WatchAnalyzer.analyze(test).headline)
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("45 seconds", WatchAnalyzer.duration(45_000))
        assertEquals("5 minutes", WatchAnalyzer.duration(300_000))
        assertEquals("2.5 hours", WatchAnalyzer.duration(9_000_000))
    }

    @Test
    fun `log text has one line per event with the details that matter`() {
        val events = listOf(
            WifiEvent(1_000, WifiEventKind.NOTE, "Watching started"),
            WifiEvent(2_000, WifiEventKind.SUPPLICANT, "ASSOCIATING"),
            WifiEvent(3_000, WifiEventKind.AUTH_ERROR, "WRONG_PASSWORD"),
            WifiEvent(4_000, WifiEventKind.LINK_UP, ssid = "Home", bssid = "aa:bb:cc:00:00:01", rssiDbm = -52, frequencyMhz = 5180),
            WifiEvent(5_000, WifiEventKind.WIFI_SWITCH, "off"),
            WifiEvent(6_000, WifiEventKind.TEST_START, "Home"),
            WifiEvent(7_000, WifiEventKind.TEST_END),
        )
        val text = WatchLogText.build(events)
        val lines = text.trim().lines()
        assertEquals("Network Analyst connection log", lines[0])
        assertEquals(events.size, lines.size - 3)
        assertTrue(text.contains("Wi-Fi step: ASSOCIATING"))
        assertTrue(text.contains("Authentication error: WRONG_PASSWORD"))
        assertTrue(text.contains("Connected  (\"Home\" aa:bb:cc:00:00:01 5180 MHz -52 dBm)"))
        assertTrue(text.contains("Wi-Fi switched off"))
        assertTrue(text.contains("Connection test started: Home"))
        assertEquals("Disconnected", WatchLogText.describe(WifiEvent(0, WifiEventKind.LINK_DOWN)))
    }
}
