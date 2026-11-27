package com.networkanalyst.core.connect

import com.networkanalyst.core.analysis.Severity
import com.networkanalyst.core.model.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestAnalyzerTest {
    private fun target(n: Int, freq: Int, rssi: Int = -55) = TestTarget("Home", "aa:bb:cc:00:00:%02x".format(n), freq, rssi)

    private fun ok(target: TestTarget, ms: Long = 1800, hidden: List<FailedStage>? = emptyList()) =
        ConnectTrial(target.bssid, success = true, durationMs = ms, hiddenFailures = hidden)

    private fun failed(target: TestTarget, why: ConnectFailure, hidden: List<FailedStage>? = emptyList()) =
        ConnectTrial(target.bssid, success = false, failure = why, durationMs = 9000, hiddenFailures = hidden)

    private fun result(target: TestTarget, vararg trials: ConnectTrial) = TargetResult(target, trials.toList())

    private val ap24 = target(1, 2437, -48)
    private val ap5 = target(2, 5180, -52)
    private val far5 = target(3, 5745, -74)

    @Test
    fun `target labels and statistics`() {
        assertEquals(WifiBand.GHZ_5, ap5.band)
        assertEquals("5 GHz ch 36", ap5.radioLabel)
        assertEquals("2.4 GHz ch 6", ap24.radioLabel)
        val r = result(ap5, ok(ap5, 1000), ok(ap5, 3000, listOf(FailedStage.JOINING)), failed(ap5, ConnectFailure.ASSOCIATION, listOf(FailedStage.JOINING, FailedStage.JOINING)))
        assertEquals(2, r.successes)
        assertEquals(1, r.failures)
        assertEquals(3, r.hiddenFailureCount)
        assertEquals(2000.0, r.medianConnectMs!!, 1e-9)
        assertFalse(r.clean)
        assertFalse(r.dead)
        assertEquals(listOf(ConnectFailure.ASSOCIATION to 1), r.failureReasons)
        // Unknown for one trial means unknown overall.
        assertNull(result(ap5, ok(ap5), ok(ap5, hidden = null)).hiddenFailureCount)
        assertTrue("with retries unknown, all-success still counts as clean", result(ap5, ok(ap5, hidden = null)).clean)
    }

    @Test
    fun `all clean says the network accepts the phone and points at the saved network`() {
        val report = ConnectionTestAnalyzer.analyze(listOf(result(ap24, ok(ap24), ok(ap24), ok(ap24)), result(ap5, ok(ap5), ok(ap5), ok(ap5))))
        val finding = report.findings.single()
        assertEquals("connect.all_ok", finding.id)
        assertEquals(Severity.GOOD, finding.severity)
        assertTrue(finding.detail, finding.detail.contains("All 6 connection requests across 2 access point radios succeeded on the first try"))
        assertTrue(finding.fixes.any { it.contains("forget the network") })
        assertEquals("The network accepts this phone at every access point tested.", report.headline)
    }

    @Test
    fun `all clean on a phone that hides retries says so`() {
        val report = ConnectionTestAnalyzer.analyze(listOf(result(ap5, ok(ap5, hidden = null), ok(ap5, hidden = null))))
        assertTrue(report.findings.single().detail.contains("does not reveal silent retries"))
    }

    @Test
    fun `one band failing is called out as band specific`() {
        val report = ConnectionTestAnalyzer.analyze(
            listOf(
                result(ap24, ok(ap24), ok(ap24), ok(ap24)),
                result(ap5, failed(ap5, ConnectFailure.ASSOCIATION), ok(ap5, hidden = listOf(FailedStage.JOINING)), failed(ap5, ConnectFailure.ASSOCIATION)),
                result(far5, failed(far5, ConnectFailure.NO_RESPONSE), failed(far5, ConnectFailure.NO_RESPONSE), failed(far5, ConnectFailure.NO_RESPONSE)),
            ),
        )
        val band = report.findings.first { it.id == "connect.band" }
        assertEquals(Severity.CRITICAL, band.severity)
        assertEquals("Connecting fails on 5 GHz only", band.title)
        assertTrue(band.detail, band.detail.contains("while 2.4 GHz connected cleanly"))
        assertTrue(band.detail.contains("5 GHz ch 36 (aa:bb:cc:00:00:02, -52 dBm): 1 of 3 connected, 1 silent retry, mostly \"Refused when joining\""))
        assertTrue(band.fixes.first().contains("hide the 5 GHz band"))
        assertEquals("Connecting fails on 5 GHz only.", report.headline)
        // The hidden retry is reported too.
        assertNotNull(report.findings.firstOrNull { it.id == "connect.retries" })
        assertNull(report.findings.firstOrNull { it.id == "connect.some_aps" })
    }

    @Test
    fun `one bad access point among good ones of the same band`() {
        val near5 = target(4, 5180, -50)
        val report = ConnectionTestAnalyzer.analyze(
            listOf(
                result(ap24, ok(ap24), ok(ap24)),
                result(near5, ok(near5), ok(near5)),
                result(far5, failed(far5, ConnectFailure.NO_RESPONSE), ok(far5)),
            ),
        )
        val finding = report.findings.first { it.id == "connect.some_aps" }
        assertEquals("One access point radio gives trouble", finding.title)
        assertEquals("not dead, so a warning rather than critical", Severity.WARNING, finding.severity)
        assertTrue(finding.detail.contains("2 of 3 access point radios connected cleanly"))
        assertTrue(finding.detail.contains("-74 dBm"))
        assertEquals("One access point radio gives trouble.", report.headline)
    }

    @Test
    fun `a dead access point among good ones is critical`() {
        val near5 = target(4, 5180, -50)
        val report = ConnectionTestAnalyzer.analyze(
            listOf(result(near5, ok(near5), ok(near5)), result(far5, failed(far5, ConnectFailure.AUTHENTICATION), failed(far5, ConnectFailure.AUTHENTICATION))),
        )
        assertEquals(Severity.CRITICAL, report.findings.first { it.id == "connect.some_aps" }.severity)
        // The password worked elsewhere, so intermittent key exchange failure is its own finding.
        val keys = report.findings.first { it.id == "connect.keys_sometimes" }
        assertTrue(keys.detail.contains("so the password is right"))
    }

    @Test
    fun `everything failing at the key exchange suspects the password first`() {
        val report = ConnectionTestAnalyzer.analyze(
            listOf(result(ap24, failed(ap24, ConnectFailure.AUTHENTICATION)), result(ap5, failed(ap5, ConnectFailure.AUTHENTICATION))),
        )
        val finding = report.findings.single()
        assertEquals("connect.all_failed", finding.id)
        assertEquals("The key exchange failed on every access point", finding.title)
        assertTrue(finding.fixes.first().contains("Check the password"))
        assertEquals("The key exchange failed on every access point.", report.headline)
    }

    @Test
    fun `everything failing for lack of an address points at dhcp`() {
        val report = ConnectionTestAnalyzer.analyze(listOf(result(ap5, failed(ap5, ConnectFailure.IP_ADDRESS), failed(ap5, ConnectFailure.IP_ADDRESS))))
        assertEquals("The phone joins but is never given an IP address", report.findings.single().title)
    }

    @Test
    fun `everything refused suggests steering, blocking and a different network`() {
        val report = ConnectionTestAnalyzer.analyze(listOf(result(ap5, failed(ap5, ConnectFailure.ASSOCIATION)), result(ap24, failed(ap24, ConnectFailure.NO_RESPONSE))))
        val finding = report.findings.single()
        assertEquals("No access point would take the phone", finding.title)
        assertTrue(finding.fixes.any { it.contains("hotspot") })
    }

    @Test
    fun `unreliable everywhere is not pinned on one unit`() {
        val report = ConnectionTestAnalyzer.analyze(
            listOf(
                result(ap24, ok(ap24, hidden = listOf(FailedStage.KEY_EXCHANGE)), failed(ap24, ConnectFailure.AUTHENTICATION)),
                result(ap5, failed(ap5, ConnectFailure.AUTHENTICATION), ok(ap5, hidden = listOf(FailedStage.KEY_EXCHANGE, FailedStage.JOINING))),
            ),
        )
        val flaky = report.findings.first { it.id == "connect.flaky" }
        assertTrue(flaky.detail, flaky.detail.contains("2 of 4 connection requests succeeded, and Android made 3 silent retries along the way"))
        assertEquals("Connecting is unreliable everywhere.", report.headline)
        val retries = report.findings.first { it.id == "connect.retries" }
        assertEquals("Android had to retry 3 times behind the scenes", retries.title)
        assertTrue(retries.detail.contains("2 stopped at \"Exchanging encryption keys\", 1 stopped at \"Joining the access point\""))
        // Sorted most serious first.
        assertEquals(report.findings.map { it.severity.ordinal }.sorted(), report.findings.map { it.severity.ordinal })
    }

    @Test
    fun `retries with eventual success are still a finding`() {
        val report = ConnectionTestAnalyzer.analyze(
            listOf(result(ap24, ok(ap24), ok(ap24)), result(ap5, ok(ap5, 9000, listOf(FailedStage.JOINING, FailedStage.JOINING)), ok(ap5))),
        )
        // Every request succeeded, yet the 5 GHz radio needed retries: that is the lead, as a warning.
        val lead = report.findings.first { it.id == "connect.band" }
        assertEquals("Connecting needs retries on 5 GHz only", lead.title)
        assertEquals(Severity.WARNING, lead.severity)
        assertEquals("Connecting needs retries on 5 GHz only.", report.headline)
        assertTrue(report.findings.any { it.id == "connect.retries" && it.title.contains("2 times") })
    }

    @Test
    fun `some attempts without an address get the dhcp note`() {
        val report = ConnectionTestAnalyzer.analyze(listOf(result(ap24, ok(ap24), ok(ap24)), result(ap5, failed(ap5, ConnectFailure.IP_ADDRESS), ok(ap5))))
        assertNotNull(report.findings.firstOrNull { it.id == "connect.dhcp" })
    }

    @Test
    fun `declined or empty tests report nothing rather than a verdict`() {
        val declined = ConnectionTestAnalyzer.analyze(listOf(result(ap5, failed(ap5, ConnectFailure.DECLINED))))
        assertTrue(declined.findings.isEmpty())
        assertEquals("Nothing was tested because the request was not allowed.", declined.headline)
        assertEquals("Nothing was tested.", ConnectionTestAnalyzer.analyze(emptyList()).headline)
        assertEquals("Nothing was tested.", ConnectionTestAnalyzer.analyze(listOf(result(ap5))).headline)
    }

    @Test
    fun `a request Android would not take ends the test without counting against the access point`() {
        // The app left the screen after two good connections; the third request was refused outright.
        val report = ConnectionTestAnalyzer.analyze(
            listOf(result(ap24, ok(ap24), ok(ap24), failed(ap24, ConnectFailure.REJECTED)), result(ap5)),
        )
        assertEquals(ConnectFailure.REJECTED, report.stoppedBy)
        assertEquals("the refused request is not shown as a trial", 2, report.results.first().trials.size)
        assertEquals(listOf("connect.all_ok"), report.findings.map { it.id })
        assertTrue(report.findings.single().detail.contains("All 2 connection requests across 1 access point radio succeeded"))

        val nothing = ConnectionTestAnalyzer.analyze(listOf(result(ap24, failed(ap24, ConnectFailure.REJECTED))))
        assertEquals("Nothing was tested because Android did not accept the request.", nothing.headline)
        assertEquals(ConnectFailure.REJECTED, nothing.stoppedBy)
        assertTrue(nothing.findings.isEmpty())

        // A real failure is still a failure, and a normal run has nothing to report here.
        assertEquals(null, ConnectionTestAnalyzer.analyze(listOf(result(ap5, failed(ap5, ConnectFailure.ASSOCIATION)))).stoppedBy)
        assertTrue(ConnectFailure.entries.filter { !it.countsAsTest } == listOf(ConnectFailure.DECLINED, ConnectFailure.REJECTED))
    }

    @Test
    fun `a clean result points at what the test does differently from normal connecting`() {
        // The test uses its own network entry and normally a randomized address; when it passes
        // but everyday connecting fails, those two differences are where to look.
        val fixes = ConnectionTestAnalyzer.analyze(listOf(result(ap24, ok(ap24), ok(ap24)))).findings.single().fixes
        assertTrue(fixes.any { it.contains("randomized hardware (MAC) address") && it.contains("forget the network") })
        assertTrue(fixes.any { it.contains("blocked, paused") })
    }

    @Test
    fun `both radios of one box failing is pinned on the box, not on a band`() {
        // As measured on a real phone: one mesh unit's two radios give trouble, the other unit is clean.
        val a24 = TestTarget("Home", "a4:11:22:33:45:86", 2462, -47)
        val a5 = TestTarget("Home", "a4:11:22:33:45:87", 5560, -68)
        val b5 = TestTarget("Home", "c0:22:33:44:9a:e7", 5560, -51)
        val report = ConnectionTestAnalyzer.analyze(
            listOf(
                TargetResult(a24, listOf(ConnectTrial(a24.bssid, true, durationMs = 4000, hiddenFailures = listOf(FailedStage.JOINING)), ConnectTrial(a24.bssid, true, durationMs = 4000, hiddenFailures = emptyList()))),
                TargetResult(b5, List(3) { ConnectTrial(b5.bssid, true, durationMs = 500, hiddenFailures = emptyList()) }),
                TargetResult(
                    a5,
                    listOf(
                        ConnectTrial(a5.bssid, false, ConnectFailure.ASSOCIATION, durationMs = 20_000, hiddenFailures = List(3) { FailedStage.JOINING }),
                        ConnectTrial(a5.bssid, true, durationMs = 1600, hiddenFailures = emptyList()),
                    ),
                ),
            ),
        )
        assertEquals("The trouble follows one access point.", report.headline)
        val unit = report.findings.first()
        assertEquals("connect.unit", unit.id)
        assertEquals(Severity.CRITICAL, unit.severity)
        assertTrue(unit.detail.startsWith("Every radio of the access point at a4:11:22:33:45:86/87 gave trouble (2.4 GHz and 5 GHz), while the other access point tested connected cleanly."))
        assertTrue(unit.fixes.first().contains("addresses starting a4:11:22:33:45"))
        assertTrue(report.findings.none { it.id == "connect.band" || it.id == "connect.some_aps" })
    }

    @Test
    fun `one band failing across two boxes is still a band problem`() {
        val a24 = TestTarget("Home", "a4:11:22:33:45:86", 2462, -47)
        val a5 = TestTarget("Home", "a4:11:22:33:45:87", 5560, -68)
        val b24 = TestTarget("Home", "c0:22:33:44:9a:e6", 2437, -45)
        val b5 = TestTarget("Home", "c0:22:33:44:9a:e7", 5560, -51)
        fun good(t: TestTarget) = TargetResult(t, List(2) { ConnectTrial(t.bssid, true, durationMs = 500, hiddenFailures = emptyList()) })
        fun bad(t: TestTarget) = TargetResult(t, List(2) { ConnectTrial(t.bssid, false, ConnectFailure.ASSOCIATION, durationMs = 9000, hiddenFailures = emptyList()) })
        val report = ConnectionTestAnalyzer.analyze(listOf(good(a24), bad(a5), good(b24), bad(b5)))
        assertEquals("Connecting fails on 5 GHz only.", report.headline)
        // And a box with one bad radio out of two is not blamed as a whole.
        val mixed = ConnectionTestAnalyzer.analyze(listOf(good(a24), bad(a5), good(b24), good(b5)))
        assertTrue(mixed.findings.none { it.id == "connect.unit" })
    }

    @Test
    fun `silent retries are summed up by the step they stopped at`() {
        val result = TargetResult(
            ap5,
            listOf(
                ConnectTrial(ap5.bssid, true, durationMs = 900, hiddenFailures = listOf(FailedStage.JOINING, FailedStage.KEY_EXCHANGE, FailedStage.JOINING)),
                ConnectTrial(ap5.bssid, true, durationMs = 900, hiddenFailures = emptyList()),
            ),
        )
        assertEquals("3 silent retries (2 refused when joining, 1 key exchange failed)", result.hiddenFailureSummary)
        assertEquals(null, TargetResult(ap5, listOf(ConnectTrial(ap5.bssid, true, durationMs = 900, hiddenFailures = emptyList()))).hiddenFailureSummary)
        assertEquals(null, TargetResult(ap5, listOf(ConnectTrial(ap5.bssid, true, durationMs = 900, hiddenFailures = null))).hiddenFailureSummary)
    }

    @Test
    fun `a radio that lets the phone in and drops it gets its own finding and advice`() {
        val good = TestTarget("Home", "c0:22:33:44:9a:e7", 5560, -61)
        val bad = TestTarget("Home", "a4:11:22:33:45:87", 5560, -64)
        val report = ConnectionTestAnalyzer.analyze(
            listOf(
                TargetResult(good, List(3) { ConnectTrial(good.bssid, true, durationMs = 600, hiddenFailures = emptyList()) }),
                TargetResult(bad, List(3) { ConnectTrial(bad.bssid, false, ConnectFailure.DROPPED, durationMs = 1500, hiddenFailures = List(3) { FailedStage.DROPPED }) }),
            ),
        )
        val dropped = report.findings.first { it.id == "connect.dropped" }
        assertEquals("The phone is let in, then dropped at once", dropped.title)
        assertTrue(dropped.detail.startsWith("12 tries at 5 GHz ch 112 (a4:11:22:33:45:87, -64 dBm) went the same way: the access point accepted the phone and the link was cut a moment later"))
        assertTrue(dropped.fixes.first().contains("client steering"))
        // It is not a password problem, so the key-exchange advice must stay out of it.
        assertTrue(report.findings.none { it.id == "connect.keys_sometimes" })
        assertTrue(report.findings.first { it.id == "connect.retries" }.detail.contains("9 stopped at \"Dropped right after joining\""))

        val everywhere = ConnectionTestAnalyzer.analyze(listOf(TargetResult(bad, List(2) { ConnectTrial(bad.bssid, false, ConnectFailure.DROPPED, durationMs = 1500, hiddenFailures = emptyList()) })))
        assertEquals("Every access point drops the phone right after letting it join.", everywhere.headline)
    }

    @Test
    fun `connect time leaves out the time android spent looking for the radio`() {
        val result = TargetResult(
            ap24,
            listOf(
                ConnectTrial(ap24.bssid, true, durationMs = 35_000, waitMs = 34_000, hiddenFailures = emptyList()),
                ConnectTrial(ap24.bssid, true, durationMs = 900, waitMs = 300, hiddenFailures = emptyList()),
                ConnectTrial(ap24.bssid, true, durationMs = 3_700, waitMs = 2_700, hiddenFailures = emptyList()),
            ),
        )
        assertEquals(1_000.0, result.medianConnectMs!!, 0.0)
        assertEquals(2_700.0, result.medianWaitMs!!, 0.0)
        // On a phone that shows no steps the whole time is all there is.
        val blind = TargetResult(ap24, listOf(ConnectTrial(ap24.bssid, true, durationMs = 2_000, hiddenFailures = null)))
        assertEquals(2_000.0, blind.medianConnectMs!!, 0.0)
        assertEquals(null, blind.medianWaitMs)
    }
}
