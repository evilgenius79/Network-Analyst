package com.networkanalyst.core.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectReportTextTest {
    private val ap24 = TestTarget("Home", "aa:bb:cc:00:00:01", 2437, -48)
    private val ap5 = TestTarget("Home", "aa:bb:cc:00:00:02", 5180, -52)

    @Test
    fun `target line summarises one access point`() {
        val result = TargetResult(
            ap5,
            listOf(
                ConnectTrial(ap5.bssid, true, durationMs = 1800, hiddenFailures = emptyList()),
                ConnectTrial(ap5.bssid, true, durationMs = 7400, hiddenFailures = listOf(FailedStage.JOINING)),
                ConnectTrial(ap5.bssid, false, ConnectFailure.ASSOCIATION, durationMs = 30_000, hiddenFailures = listOf(FailedStage.JOINING, FailedStage.JOINING, FailedStage.JOINING)),
            ),
        )
        assertEquals(
            "5 GHz ch 36 (aa:bb:cc:00:00:02, -52 dBm): 2/3 connected, typically 4.6 s, 4 silent retries (4 refused when joining), failed: Refused when joining x1",
            ConnectReportText.targetLine(result),
        )
        // A phone that hides retries gets no retry count rather than a zero.
        val blind = TargetResult(ap24, listOf(ConnectTrial(ap24.bssid, true, durationMs = 2000, hiddenFailures = null)))
        assertEquals("2.4 GHz ch 6 (aa:bb:cc:00:00:01, -48 dBm): 1/1 connected, typically 2.0 s", ConnectReportText.targetLine(blind))
    }

    @Test
    fun `full report has test, findings, watch and log sections`() {
        val results = listOf(
            TargetResult(ap24, List(2) { ConnectTrial(ap24.bssid, true, durationMs = 1500, hiddenFailures = emptyList()) }),
            TargetResult(ap5, List(2) { ConnectTrial(ap5.bssid, false, ConnectFailure.ASSOCIATION, durationMs = 20_000, hiddenFailures = emptyList()) }),
        )
        val events = listOf(
            WifiEvent(1_000, WifiEventKind.SUPPLICANT, "ASSOCIATING"),
            WifiEvent(1_500, WifiEventKind.SUPPLICANT, "DISCONNECTED"),
        )
        val text = ConnectReportText.build(ConnectionTestAnalyzer.analyze(results), events)
        assertTrue(text.startsWith("Network Analyst connection report\n"))
        assertTrue(text.contains("CONNECTION TEST for \"Home\""))
        assertTrue(text.contains("Connecting fails on 5 GHz only."))
        assertTrue(text.contains("- 2.4 GHz ch 6 (aa:bb:cc:00:00:01, -48 dBm): 2/2 connected"))
        assertTrue(text.contains("1. [Problem] Connecting fails on 5 GHz only"))
        assertTrue(text.contains("CONNECTION WATCH\n1 of 1 connection attempt failed."))
        assertTrue(text.contains("EVENT LOG"))
        assertTrue(text.contains("Wi-Fi step: ASSOCIATING"))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `watch only, with nothing recorded`() {
        val text = ConnectReportText.build(null, emptyList())
        assertFalse(text.contains("CONNECTION TEST"))
        assertTrue(text.contains("CONNECTION WATCH\nWatching. Nothing has happened yet."))
        assertFalse(text.contains("EVENT LOG"))
    }

    @Test
    fun `a test that was cut short says so`() {
        val results = listOf(
            TargetResult(ap24, listOf(ConnectTrial(ap24.bssid, true, durationMs = 1500, hiddenFailures = emptyList()), ConnectTrial(ap24.bssid, false, ConnectFailure.REJECTED, durationMs = 40))),
        )
        val text = ConnectReportText.build(ConnectionTestAnalyzer.analyze(results), emptyList())
        assertTrue(text.contains("- 2.4 GHz ch 6 (aa:bb:cc:00:00:01, -48 dBm): 1/1 connected"))
        assertTrue(text.contains("The test stopped early: Not accepted. Android turned the request down at once"))
    }
}
