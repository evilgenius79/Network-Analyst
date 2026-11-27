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
        assertEquals("The trouble is tied to specific access points.", report.headline)
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
}
