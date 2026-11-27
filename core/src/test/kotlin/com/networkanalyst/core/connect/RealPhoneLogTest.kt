package com.networkanalyst.core.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A log shared from a real Galaxy A17 5G on a two-unit mesh, with network names, access point
 * addresses and the date replaced and the timing left exactly as recorded. It holds four runs
 * of the connection test (the last with a version that records which access point each step
 * was aimed at) and some everyday use in between.
 *
 * On that phone one 5 GHz radio accepted the phone and then cut it off about 40 ms later,
 * before the key exchange began, while three other radios let it in every time.
 */
class RealPhoneLogTest {
    private val events = LogFixture.load("real-run-galaxy-a17.log")
    private val failingRadio = "a4:11:22:33:45:87"

    private fun lastTestWindow(): List<WifiEvent> {
        val start = events.indexOfLast { it.kind == WifiEventKind.TEST_START }
        val end = events.indexOfLast { it.kind == WifiEventKind.TEST_END }
        return events.subList(start + 1, end)
    }

    @Test
    fun `the log reads back whole and renders to the same text`() {
        assertEquals(767, events.size)
        assertEquals(4, events.count { it.kind == WifiEventKind.TEST_START })
        // Reading a line and describing it again gives the line back, so nothing was lost.
        val original = requireNotNull(javaClass.classLoader.getResource("real-run-galaxy-a17.log")).readText().lines().filter { it.isNotBlank() }
        assertEquals(original.map { it.substring(25) }, events.map { WatchLogText.describe(it) })
    }

    @Test
    fun `every try at the failing radio was let in and then dropped at once`() {
        val summary = ConnectionTimeline.summarize(lastTestWindow())
        val failures = summary.failures
        // Three requests, each tried four times by Android.
        assertEquals(12, failures.size)
        assertTrue(failures.all { it.failedAt == FailedStage.DROPPED })
        assertTrue(failures.all { it.bssid == failingRadio })
        assertTrue("cut off 24 to 55 ms after being accepted", failures.all { it.droppedAfterMs!! in 20..60 })

        // The other three radios, three requests each, went straight through.
        val joined = summary.successes.groupingBy { it.bssid }.eachCount()
        assertEquals(mapOf("c0:22:33:44:9a:e6" to 3, "a4:11:22:33:45:86" to 3, "c0:22:33:44:9a:e7" to 3), joined)
    }

    @Test
    fun `a request to that radio is reported as dropped, with three silent retries behind it`() {
        val window = lastTestWindow()
        // One request's worth: the first burst of four tries at the failing radio, which all
        // fell within a second and a half.
        val firstTry = window.first { it.bssid == failingRadio }.timeMs
        val burst = window.filter { it.timeMs in firstTry..firstTry + 1_500 }
        // Android reported this failure without a stage; the steps seen name it.
        val outcome = TrialOutcome.failure(ConnectFailure.UNKNOWN, timedOut = false, burst, approvalExpected = false, stageReporting = true, targetBssid = failingRadio)
        assertEquals(ConnectFailure.DROPPED, outcome.failure)
        assertEquals(List(3) { FailedStage.DROPPED }, outcome.hiddenFailures)
    }

    @Test
    fun `the same signature runs through the earlier runs that recorded no addresses`() {
        val all = ConnectionTimeline.summarize(events).failures
        val dropped = all.filter { it.failedAt == FailedStage.DROPPED }
        assertEquals(43, dropped.size)
        // Nearly all were cut off within a tenth of a second of being accepted; the slowest took about half a second.
        assertEquals(41, dropped.count { it.droppedAfterMs!! <= 100 })
        assertTrue(dropped.all { it.droppedAfterMs!! < 600 })
        // Besides those there was a single try that got no answer at all.
        assertEquals(listOf(FailedStage.JOINING), all.filter { it.failedAt != FailedStage.DROPPED }.map { it.failedAt })
    }

    @Test
    fun `the watch leaves the app's own test connections out of the phone's record`() {
        val report = WatchAnalyzer.analyze(events)
        assertEquals("2 connection attempts succeeded; the connection dropped 3 times.", report.headline)
        assertTrue(report.findings.none { it.id.startsWith("watch.failed") })
    }
}
