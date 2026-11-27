package com.networkanalyst.core.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTimelineTest {
    private var clock = 0L

    private fun at(ms: Long, kind: WifiEventKind, value: String = "", bssid: String? = null, rssi: Int? = null) =
        WifiEvent(ms, kind, value, bssid = bssid, rssiDbm = rssi)

    /** Supplicant states [stepMs] apart starting at [startMs]. */
    private fun supplicant(startMs: Long, vararg states: String, stepMs: Long = 100): List<WifiEvent> =
        states.mapIndexed { i, state -> at(startMs + i * stepMs, WifiEventKind.SUPPLICANT, state) }

    private fun network(ms: Long, state: String, bssid: String? = null) = at(ms, WifiEventKind.NETWORK, state, bssid)

    @Test
    fun `a clean connection is one successful attempt`() {
        val events = supplicant(1000, "SCANNING", "AUTHENTICATING", "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED") +
            network(1650, "OBTAINING_IPADDR") + network(2400, "CONNECTED", "aa:bb:cc:00:00:01")
        val summary = ConnectionTimeline.summarize(events)
        assertEquals(1, summary.attempts.size)
        val attempt = summary.attempts.single()
        assertTrue(attempt.success)
        assertFalse(attempt.roam)
        assertEquals(1100L, attempt.startMs)
        assertEquals(1300L, attempt.durationMs)
        assertEquals("aa:bb:cc:00:00:01", attempt.bssid)
        assertTrue(summary.detailed)
        assertTrue(summary.drops.isEmpty())
        assertEquals(1300.0, summary.medianConnectMs!!, 1e-9)
    }

    @Test
    fun `refused association fails at joining`() {
        val events = supplicant(0, "ASSOCIATING", "DISCONNECTED")
        val failure = ConnectionTimeline.summarize(events).failures.single()
        assertEquals(FailedStage.JOINING, failure.failedAt)
    }

    @Test
    fun `handshake that never completes fails at key exchange, with the auth error attached`() {
        val events = supplicant(0, "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE") +
            at(350, WifiEventKind.AUTH_ERROR, "TIMEOUT") + supplicant(400, "DISCONNECTED")
        val failure = ConnectionTimeline.summarize(events).failures.single()
        assertEquals(FailedStage.KEY_EXCHANGE, failure.failedAt)
        assertEquals("TIMEOUT", failure.authError)
    }

    @Test
    fun `keys done but no address fails at IP address`() {
        val events = supplicant(0, "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED") +
            network(600, "OBTAINING_IPADDR") + supplicant(15_000, "DISCONNECTED") + network(15_050, "DISCONNECTED")
        val summary = ConnectionTimeline.summarize(events)
        assertEquals(FailedStage.IP_ADDRESS, summary.failures.single().failedAt)
        assertEquals("the late network DISCONNECTED must not add a second failure", 1, summary.attempts.size)
        assertTrue(summary.drops.isEmpty())
    }

    @Test
    fun `silent retries show up as separate failed attempts before the success`() {
        // What Android does behind one connection request: fail, fail, then get through.
        val events = supplicant(0, "ASSOCIATING", "DISCONNECTED") +
            supplicant(2_000, "SCANNING", "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "DISCONNECTED") +
            supplicant(5_000, "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED") +
            network(5_600, "OBTAINING_IPADDR") + network(6_000, "CONNECTED")
        val summary = ConnectionTimeline.summarize(events)
        assertEquals(3, summary.attempts.size)
        assertEquals(listOf(FailedStage.JOINING, FailedStage.KEY_EXCHANGE), summary.failures.map { it.failedAt })
        assertEquals(mapOf(FailedStage.JOINING to 1, FailedStage.KEY_EXCHANGE to 1), summary.failuresByStage)
        assertTrue(summary.attempts.last().success)
    }

    @Test
    fun `starting over without a disconnect in between still counts the abandoned try`() {
        val events = supplicant(0, "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE") +
            supplicant(4_000, "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED") +
            network(4_700, "CONNECTED")
        val summary = ConnectionTimeline.summarize(events)
        assertEquals(2, summary.attempts.size)
        assertEquals(FailedStage.KEY_EXCHANGE, summary.attempts[0].failedAt)
        assertTrue(summary.attempts[1].success)
    }

    @Test
    fun `network states alone give a coarser stage`() {
        val joinFailed = ConnectionTimeline.summarize(listOf(network(0, "CONNECTING"), network(900, "DISCONNECTED")))
        assertFalse(joinFailed.detailed)
        assertEquals(FailedStage.BEFORE_IP_ADDRESS, joinFailed.failures.single().failedAt)

        val addressFailed = ConnectionTimeline.summarize(listOf(network(0, "CONNECTING"), network(500, "OBTAINING_IPADDR"), network(9_000, "DISCONNECTED")))
        assertEquals(FailedStage.IP_ADDRESS, addressFailed.failures.single().failedAt)

        val ok = ConnectionTimeline.summarize(listOf(network(0, "CONNECTING"), network(500, "OBTAINING_IPADDR"), network(1_200, "CONNECTED")))
        assertTrue(ok.attempts.single().success)
    }

    @Test
    fun `the app's own link-up event completes an attempt when network states are missing`() {
        val events = supplicant(0, "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED") +
            at(1_500, WifiEventKind.LINK_UP, bssid = "aa:bb:cc:00:00:02")
        val attempt = ConnectionTimeline.summarize(events).attempts.single()
        assertTrue(attempt.success)
        assertEquals("aa:bb:cc:00:00:02", attempt.bssid)
    }

    @Test
    fun `a connection that later goes away is a drop, not a failed attempt`() {
        val events = listOf(at(0, WifiEventKind.LINK_UP, bssid = "aa:bb:cc:00:00:01", rssi = -60)) +
            at(200_000, WifiEventKind.NOTE, "signal", rssi = -81) +
            supplicant(240_000, "DISCONNECTED") + network(240_050, "DISCONNECTED") + at(240_100, WifiEventKind.LINK_DOWN)
        val summary = ConnectionTimeline.summarize(events)
        assertTrue(summary.attempts.isEmpty())
        val drop = summary.drops.single()
        assertEquals(240_000L, drop.connectedForMs)
        assertEquals("aa:bb:cc:00:00:01", drop.bssid)
        assertEquals(-81, drop.rssiDbm)
    }

    @Test
    fun `moving between access points while connected is a roam`() {
        val events = listOf(at(0, WifiEventKind.LINK_UP, bssid = "aa:bb:cc:00:00:01")) +
            supplicant(60_000, "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE") +
            at(60_400, WifiEventKind.SUPPLICANT, "COMPLETED", bssid = "aa:bb:cc:00:00:09")
        val summary = ConnectionTimeline.summarize(events)
        val roam = summary.attempts.single()
        assertTrue(roam.success)
        assertTrue(roam.roam)
        assertEquals("aa:bb:cc:00:00:09", roam.bssid)
        assertTrue(summary.drops.isEmpty())
        assertNull("roams are not counted in connect time", summary.medianConnectMs)
    }

    @Test
    fun `a failed roam drops the connection`() {
        val events = listOf(at(0, WifiEventKind.LINK_UP, bssid = "aa:bb:cc:00:00:01")) +
            supplicant(60_000, "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "DISCONNECTED") + network(60_400, "DISCONNECTED")
        val summary = ConnectionTimeline.summarize(events)
        assertEquals(FailedStage.KEY_EXCHANGE, summary.failures.single().failedAt)
        assertTrue(summary.failures.single().roam)
        assertEquals(1, summary.drops.size)
    }

    @Test
    fun `late news of the old link does not fail the reconnect already under way`() {
        // The supplicant reports the drop and the next attempt before the network broadcast catches up.
        val events = listOf(at(0, WifiEventKind.LINK_UP, bssid = "aa:bb:cc:00:00:01")) +
            supplicant(10_000, "DISCONNECTED", "SCANNING", "ASSOCIATING") +
            network(10_250, "DISCONNECTED") +
            supplicant(10_300, "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED") +
            network(10_900, "OBTAINING_IPADDR") + network(11_500, "CONNECTED")
        val summary = ConnectionTimeline.summarize(events)
        assertEquals(1, summary.drops.size)
        assertEquals(1, summary.attempts.size)
        assertTrue(summary.attempts.single().success)
        assertFalse(summary.attempts.single().roam)
    }

    @Test
    fun `switching wifi off is neither a failure nor a drop`() {
        val events = listOf(at(0, WifiEventKind.LINK_UP)) + at(5_000, WifiEventKind.WIFI_SWITCH, "off") +
            supplicant(5_050, "DISCONNECTED") + network(5_100, "DISCONNECTED") + at(5_150, WifiEventKind.LINK_DOWN)
        val summary = ConnectionTimeline.summarize(events)
        assertTrue(summary.attempts.isEmpty())
        assertTrue(summary.drops.isEmpty())
    }

    @Test
    fun `events from the app's own test can be left out`() {
        val own = supplicant(1_000, "ASSOCIATING", "DISCONNECTED")
        val test = listOf(at(10_000, WifiEventKind.TEST_START)) +
            supplicant(10_100, "ASSOCIATING", "DISCONNECTED", "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED") +
            network(11_000, "CONNECTED") + at(12_000, WifiEventKind.TEST_END)
        val after = supplicant(20_000, "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED") + network(20_600, "CONNECTED")

        val everything = ConnectionTimeline.summarize(own + test + after, includeTests = true)
        assertEquals(4, everything.attempts.size)
        val phoneOnly = ConnectionTimeline.summarize(own + test + after, includeTests = false)
        assertEquals(2, phoneOnly.attempts.size)
        assertEquals(1, phoneOnly.failures.size)
        assertTrue("the test's connection must not look like a later drop", phoneOnly.drops.isEmpty())
    }

    @Test
    fun `events are ordered by time and nothing in means nothing out`() {
        val shuffled = (supplicant(0, "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED") + network(700, "CONNECTED")).reversed()
        assertTrue(ConnectionTimeline.summarize(shuffled).attempts.single().success)
        val empty = ConnectionTimeline.summarize(emptyList())
        assertTrue(empty.attempts.isEmpty())
        assertFalse(empty.detailed)
        assertNull(empty.medianConnectMs)
    }

    @Test
    fun `a failed attempt remembers the access point it was aimed at`() {
        fun at(ms: Long, state: String, bssid: String?) = WifiEvent(ms, WifiEventKind.SUPPLICANT, state, bssid = bssid)
        val summary = ConnectionTimeline.summarize(
            listOf(
                at(0, "ASSOCIATING", "a4:11:22:33:45:87"), at(900, "DISCONNECTED", null),
                at(2000, "ASSOCIATING", "a4:11:22:33:45:87"), at(2100, "ASSOCIATED", "a4:11:22:33:45:87"), at(2200, "FOUR_WAY_HANDSHAKE", "a4:11:22:33:45:87"), at(5000, "DISCONNECTED", null),
                at(6000, "ASSOCIATING", "c0:22:33:44:9a:e7"), at(6100, "ASSOCIATED", "c0:22:33:44:9a:e7"), at(6200, "COMPLETED", "c0:22:33:44:9a:e7"),
                WifiEvent(6800, WifiEventKind.NETWORK, "CONNECTED"),
            ),
        )
        assertEquals(listOf("a4:11:22:33:45:87", "a4:11:22:33:45:87", "c0:22:33:44:9a:e7"), summary.attempts.map { it.bssid })
        assertEquals(listOf(false, false, true), summary.attempts.map { it.success })
        assertEquals(listOf(FailedStage.JOINING, FailedStage.KEY_EXCHANGE), summary.failures.map { it.failedAt })
    }

    @Test
    fun `moving on to another access point without a word means the first one failed`() {
        fun at(ms: Long, state: String, bssid: String?) = WifiEvent(ms, WifiEventKind.SUPPLICANT, state, bssid = bssid)
        val summary = ConnectionTimeline.summarize(
            listOf(
                at(0, "ASSOCIATING", "a4:11:22:33:45:87"),
                at(1500, "ASSOCIATING", "c0:22:33:44:9a:e7"), at(1600, "ASSOCIATED", "c0:22:33:44:9a:e7"), at(1700, "COMPLETED", "c0:22:33:44:9a:e7"),
                WifiEvent(2300, WifiEventKind.NETWORK, "CONNECTED"),
            ),
        )
        assertEquals(2, summary.attempts.size)
        assertEquals("a4:11:22:33:45:87", summary.failures.single().bssid)
        assertEquals(FailedStage.JOINING, summary.failures.single().failedAt)
        // The same access point named twice in a row is one try, as before.
        val same = ConnectionTimeline.summarize(listOf(at(0, "AUTHENTICATING", "a4:11:22:33:45:87"), at(200, "ASSOCIATING", "a4:11:22:33:45:87"), at(900, "DISCONNECTED", null)))
        assertEquals(1, same.attempts.size)
    }
}
