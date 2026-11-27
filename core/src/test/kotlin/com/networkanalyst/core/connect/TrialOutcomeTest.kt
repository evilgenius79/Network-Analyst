package com.networkanalyst.core.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrialOutcomeTest {
    private fun s(ms: Long, state: String) = WifiEvent(ms, WifiEventKind.SUPPLICANT, state)
    private fun n(ms: Long, state: String) = WifiEvent(ms, WifiEventKind.NETWORK, state)

    private val cleanConnect = listOf(s(100, "DISCONNECTED"), s(300, "ASSOCIATING"), s(400, "ASSOCIATED"), s(500, "FOUR_WAY_HANDSHAKE"), s(600, "COMPLETED"), n(1200, "CONNECTED"))

    @Test
    fun `clean success has no hidden failures`() {
        assertEquals(TrialOutcome.Result(null, emptyList()), TrialOutcome.success(cleanConnect))
    }

    @Test
    fun `success after silent retries lists where each one stopped`() {
        val events = listOf(s(100, "ASSOCIATING"), s(900, "DISCONNECTED"), s(2000, "ASSOCIATING"), s(2100, "ASSOCIATED"), s(2200, "FOUR_WAY_HANDSHAKE"), s(4000, "DISCONNECTED")) +
            cleanConnect.map { it.copy(timeMs = it.timeMs + 5000) }
        assertEquals(listOf(FailedStage.JOINING, FailedStage.KEY_EXCHANGE), TrialOutcome.success(events).hiddenFailures)
    }

    @Test
    fun `a phone that shows nothing gives unknown rather than zero`() {
        assertNull(TrialOutcome.success(emptyList()).hiddenFailures)
        assertNull(TrialOutcome.success(listOf(WifiEvent(0, WifiEventKind.NOTE, "x"), WifiEvent(1, WifiEventKind.LINK_UP))).hiddenFailures)
    }

    @Test
    fun `the tail of an unrelated attempt at the start of the window is not counted`() {
        // The phone was mid-way through rejoining its saved network when the request was made.
        val events = listOf(s(10, "ASSOCIATED"), s(60, "FOUR_WAY_HANDSHAKE"), s(120, "DISCONNECTED")) + cleanConnect.map { it.copy(timeMs = it.timeMs + 200) }
        assertEquals(emptyList<FailedStage>(), TrialOutcome.success(events).hiddenFailures)
        // Same with coarse network states only.
        val coarse = listOf(n(10, "OBTAINING_IPADDR"), n(100, "DISCONNECTED"), n(300, "CONNECTING"), n(700, "OBTAINING_IPADDR"), n(1200, "CONNECTED"))
        assertEquals(emptyList<FailedStage>(), TrialOutcome.success(coarse).hiddenFailures)
    }

    @Test
    fun `reported failure keeps its stage and separates the earlier tries`() {
        val events = listOf(s(0, "ASSOCIATING"), s(500, "DISCONNECTED"), s(2000, "ASSOCIATING"), s(2500, "DISCONNECTED"), s(4000, "ASSOCIATING"), s(4500, "DISCONNECTED"), s(6000, "ASSOCIATING"), s(6500, "DISCONNECTED"))
        val result = TrialOutcome.failure(ConnectFailure.ASSOCIATION, timedOut = false, events, approvalExpected = false, stageReporting = true)
        assertEquals(ConnectFailure.ASSOCIATION, result.failure)
        assertEquals("four tries were made; three were silent", 3, result.hiddenFailures!!.size)
    }

    @Test
    fun `without a reported stage the last observed try names the failure`() {
        fun failingAt(vararg states: String) = states.mapIndexed { i, state -> s(i * 100L, state) }
        fun classify(events: List<WifiEvent>) = TrialOutcome.failure(null, false, events, approvalExpected = false, stageReporting = false).failure
        assertEquals(ConnectFailure.ASSOCIATION, classify(failingAt("ASSOCIATING", "DISCONNECTED")))
        assertEquals(ConnectFailure.AUTHENTICATION, classify(failingAt("ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "DISCONNECTED")))
        assertEquals(ConnectFailure.IP_ADDRESS, classify(failingAt("ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "COMPLETED", "DISCONNECTED")))
        assertEquals(ConnectFailure.UNKNOWN, classify(listOf(n(0, "CONNECTING"), n(500, "DISCONNECTED"))))
    }

    @Test
    fun `nothing attempted means declined or not found, depending on whether approval was pending`() {
        val idle = listOf(s(0, "SCANNING"))
        assertEquals(ConnectFailure.DECLINED, TrialOutcome.failure(null, false, idle, approvalExpected = true, stageReporting = true).failure)
        assertEquals(ConnectFailure.NOT_FOUND, TrialOutcome.failure(null, false, idle, approvalExpected = false, stageReporting = true).failure)
        // Steps visible but none taken: same conclusion even on an Android that reports no stages.
        assertEquals(ConnectFailure.NOT_FOUND, TrialOutcome.failure(null, false, idle, approvalExpected = false, stageReporting = false).failure)
        // Nothing visible and no stage reporting: honestly unknown.
        val blind = TrialOutcome.failure(null, false, emptyList(), approvalExpected = false, stageReporting = false)
        assertEquals(ConnectFailure.UNKNOWN, blind.failure)
        assertNull(blind.hiddenFailures)
        // Nothing visible but stage reporting says it was not a connection failure.
        assertEquals(ConnectFailure.DECLINED, TrialOutcome.failure(null, false, emptyList(), approvalExpected = true, stageReporting = true).failure)
    }

    @Test
    fun `a timeout is a timeout, and an explicit reason always wins`() {
        assertEquals(ConnectFailure.TIMED_OUT, TrialOutcome.failure(null, timedOut = true, emptyList(), approvalExpected = true, stageReporting = true).failure)
        assertEquals(ConnectFailure.IP_ADDRESS, TrialOutcome.failure(ConnectFailure.IP_ADDRESS, timedOut = true, emptyList(), approvalExpected = true, stageReporting = true).failure)
    }

    @Test
    fun `a request that ends at once without an attempt was refused, not answered`() {
        fun classify(elapsedMs: Long?, approvalExpected: Boolean, events: List<WifiEvent> = emptyList(), stageReporting: Boolean = true) =
            TrialOutcome.failure(null, false, events, approvalExpected, stageReporting, elapsedMs).failure
        // Nobody reads and answers a dialog in a tenth of a second, and no scan finishes that fast.
        assertEquals(ConnectFailure.REJECTED, classify(80, approvalExpected = true))
        assertEquals(ConnectFailure.REJECTED, classify(80, approvalExpected = false))
        assertEquals(ConnectFailure.REJECTED, classify(80, approvalExpected = false, stageReporting = false))
        // Given time, the same silence means what it meant before.
        assertEquals(ConnectFailure.DECLINED, classify(2_500, approvalExpected = true))
        assertEquals(ConnectFailure.NOT_FOUND, classify(2_500, approvalExpected = false))
        assertEquals(ConnectFailure.DECLINED, classify(null, approvalExpected = true))
        // An attempt that was seen, or a reason Android gave, is never overruled by the clock.
        assertEquals(ConnectFailure.ASSOCIATION, classify(80, approvalExpected = false, events = listOf(s(0, "ASSOCIATING"), s(50, "DISCONNECTED"))))
        assertEquals(
            ConnectFailure.NO_RESPONSE,
            TrialOutcome.failure(ConnectFailure.NO_RESPONSE, false, emptyList(), approvalExpected = false, stageReporting = true, elapsedMs = 80).failure,
        )
    }

    @Test
    fun `when time runs out mid-retry, the tries seen so far name the failure`() {
        // Joined and exchanged keys twice, never got an address, and the clock ran out on the third go.
        fun noAddress(at: Long) = listOf(s(at, "ASSOCIATING"), s(at + 100, "ASSOCIATED"), s(at + 200, "FOUR_WAY_HANDSHAKE"), s(at + 300, "COMPLETED"), s(at + 20_000, "DISCONNECTED"))
        val events = noAddress(0) + noAddress(25_000) + listOf(s(50_000, "ASSOCIATING"))
        val result = TrialOutcome.failure(null, timedOut = true, events, approvalExpected = false, stageReporting = true)
        assertEquals(ConnectFailure.IP_ADDRESS, result.failure)
        assertEquals(listOf(FailedStage.IP_ADDRESS), result.hiddenFailures)
        // With only coarse states there is no stage to name, so it stays a timeout.
        val coarse = listOf(n(0, "CONNECTING"), n(9_000, "DISCONNECTED"), n(10_000, "CONNECTING"))
        assertEquals(ConnectFailure.TIMED_OUT, TrialOutcome.failure(null, timedOut = true, coarse, approvalExpected = false, stageReporting = false).failure)
    }

    @Test
    fun `when android reports a failure without a stage, the steps seen name it`() {
        val refused = listOf(s(0, "ASSOCIATING"), s(500, "DISCONNECTED"), s(2000, "ASSOCIATING"), s(2500, "DISCONNECTED"))
        val named = TrialOutcome.failure(ConnectFailure.UNKNOWN, timedOut = false, refused, approvalExpected = false, stageReporting = true)
        assertEquals(ConnectFailure.ASSOCIATION, named.failure)
        assertEquals(listOf(FailedStage.JOINING), named.hiddenFailures)
        // With nothing seen, Android's "failed, stage unknown" stands: it is not a decline or a not-found.
        assertEquals(ConnectFailure.UNKNOWN, TrialOutcome.failure(ConnectFailure.UNKNOWN, false, emptyList(), approvalExpected = true, stageReporting = true, elapsedMs = 50).failure)
        // A stage Android did give is never overruled by what was seen.
        assertEquals(ConnectFailure.IP_ADDRESS, TrialOutcome.failure(ConnectFailure.IP_ADDRESS, false, refused, approvalExpected = false, stageReporting = true).failure)
    }

    @Test
    fun `tries the phone made at another access point are not counted against this one`() {
        val target = "a4:11:22:33:45:87"
        fun at(ms: Long, state: String, bssid: String?) = WifiEvent(ms, WifiEventKind.SUPPLICANT, state, bssid = bssid)
        // The phone began rejoining its saved network at another access point; the request cut that short.
        val events = listOf(
            at(0, "ASSOCIATING", "c0:22:33:44:9a:e7"), at(80, "DISCONNECTED", null),
            at(300, "ASSOCIATING", target), at(900, "DISCONNECTED", null),
            at(1500, "ASSOCIATING", target), at(1600, "ASSOCIATED", target), at(1700, "FOUR_WAY_HANDSHAKE", target), at(1800, "COMPLETED", target),
        )
        assertEquals(listOf(FailedStage.JOINING), TrialOutcome.success(events, target).hiddenFailures)
        // Without knowing the target, or on a phone that names no access point, every try counts.
        assertEquals(2, TrialOutcome.success(events).hiddenFailures!!.size)
        assertEquals(2, TrialOutcome.success(events.map { it.copy(bssid = null) }, target).hiddenFailures!!.size)
        // Capital letters in an address make no difference.
        assertEquals(1, TrialOutcome.success(events, target.uppercase()).hiddenFailures!!.size)
    }
}
