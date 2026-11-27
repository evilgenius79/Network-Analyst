package com.networkanalyst.core.wifi

import com.networkanalyst.core.model.AccessPoint
import com.networkanalyst.core.model.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAnalyzerTest {
    private var counter = 0

    private fun ap(ssid: String, channel: Int, rssi: Int, band: WifiBand = WifiBand.GHZ_2_4, width: Int = 20, bssid: String? = null) =
        AccessPoint(
            ssid = ssid,
            bssid = bssid ?: "02:00:%02x:%02x:00:01".format(++counter / 256, counter % 256),
            rssiDbm = rssi,
            frequencyMhz = WifiChannels.frequencyOf(band, channel)!!,
            channelWidthMhz = width,
        )

    @Test
    fun `signal weight grows with strength and is bounded`() {
        assertEquals(0.0, ChannelAnalyzer.signalWeight(-100), 1e-9)
        assertEquals(1.0, ChannelAnalyzer.signalWeight(-30), 1e-9)
        assertTrue(ChannelAnalyzer.signalWeight(-50) > ChannelAnalyzer.signalWeight(-70))
        assertTrue(ChannelAnalyzer.signalWeight(-70) > ChannelAnalyzer.signalWeight(-88))
    }

    @Test
    fun `crowded channel 6 leads to a recommendation for a clear channel`() {
        val mine = ap("Home", 6, -45)
        val neighbours = listOf(
            ap("N1", 6, -55), ap("N2", 6, -60), ap("N3", 6, -62), ap("N4", 6, -70),
            ap("N5", 1, -80),
        )
        val analysis = ChannelAnalyzer.analyzeBand(WifiBand.GHZ_2_4, neighbours + mine, mine)
        assertEquals(6, analysis.currentChannel)
        assertEquals(4, analysis.currentScore!!.coChannelCount)
        assertEquals(11, analysis.recommendedChannel)
        assertEquals(0.0, analysis.recommendedScore!!.score, 1e-9)
        assertEquals(5, analysis.networkCount)
    }

    @Test
    fun `no recommendation when the current channel is already the quietest`() {
        val mine = ap("Home", 11, -45)
        val neighbours = listOf(ap("N1", 1, -55), ap("N2", 6, -60), ap("N3", 6, -62))
        val analysis = ChannelAnalyzer.analyzeBand(WifiBand.GHZ_2_4, neighbours + mine, mine)
        assertNull(analysis.recommendedChannel)
    }

    @Test
    fun `no recommendation for a marginal improvement`() {
        val mine = ap("Home", 1, -45)
        // One faint neighbour on our channel: moving would gain almost nothing.
        val neighbours = listOf(ap("N1", 1, -86), ap("N2", 6, -60), ap("N3", 11, -60))
        val analysis = ChannelAnalyzer.analyzeBand(WifiBand.GHZ_2_4, neighbours + mine, mine)
        assertNull(analysis.recommendedChannel)
    }

    @Test
    fun `off-grid 2_4 GHz channel is always told to move to 1 6 or 11`() {
        val mine = ap("Home", 3, -45)
        val analysis = ChannelAnalyzer.analyzeBand(WifiBand.GHZ_2_4, listOf(mine, ap("N1", 1, -70)), mine)
        assertTrue(analysis.recommendedChannel in WifiChannels.NON_OVERLAPPING_2_4)
        // Channel 1 has the neighbour; 6 and 11 are empty and 6 wins the tie as the lower channel.
        assertEquals(6, analysis.recommendedChannel)
    }

    @Test
    fun `partial overlap is counted separately and penalised`() {
        val strongOverlap = ap("N1", 4, -50)
        val analysis = ChannelAnalyzer.analyzeBand(WifiBand.GHZ_2_4, listOf(strongOverlap), null)
        val ch6 = analysis.scores.first { it.channel == 6 }
        val ch4 = analysis.scores.first { it.channel == 4 }
        val ch11 = analysis.scores.first { it.channel == 11 }
        assertEquals(1, ch6.overlappingCount)
        assertEquals(0, ch6.coChannelCount)
        assertEquals(1, ch4.coChannelCount)
        assertEquals(0.0, ch11.score, 1e-9)
        // Channel 6 overlaps channel 4 by half (10 of 20 MHz) with a 1.5x penalty.
        assertEquals(ChannelAnalyzer.signalWeight(-50) * 0.5 * 1.5, ch6.score, 1e-9)
        assertEquals(ChannelAnalyzer.signalWeight(-50), ch4.score, 1e-9)
    }

    @Test
    fun `virtual networks from the same radio are not interference`() {
        val mine = ap("Home", 6, -45, bssid = "a4:11:22:33:44:50")
        val guest = ap("Home-Guest", 6, -45, bssid = "a6:11:22:33:44:51")
        val other = ap("Other", 6, -60, bssid = "10:99:88:77:66:55")
        assertTrue(ChannelAnalyzer.sameRadio(mine, guest))
        assertFalse(ChannelAnalyzer.sameRadio(mine, other))
        val analysis = ChannelAnalyzer.analyzeBand(WifiBand.GHZ_2_4, listOf(mine, guest, other), mine)
        assertEquals(1, analysis.networkCount)
        assertEquals(1, analysis.currentScore!!.coChannelCount)
    }

    @Test
    fun `5 GHz recommendation picks a free block and prefers non-DFS`() {
        val mine = ap("Home", 36, -50, WifiBand.GHZ_5, width = 80)
        val neighbours = listOf(
            ap("N1", 44, -55, WifiBand.GHZ_5, width = 80),
            ap("N2", 40, -58, WifiBand.GHZ_5, width = 80),
            ap("N3", 48, -65, WifiBand.GHZ_5, width = 40),
        )
        val analysis = ChannelAnalyzer.analyzeBand(WifiBand.GHZ_5, neighbours + mine, mine)
        assertEquals(36, analysis.currentChannel)
        assertEquals(3, analysis.currentScore!!.coChannelCount)
        // Blocks 52-64 and 100+ are empty too but need DFS; 149-161 is the free non-DFS block.
        assertEquals(149, analysis.recommendedChannel)
        assertFalse(analysis.recommendedScore!!.isDfs)
        assertTrue(analysis.scores.first { it.channel == 52 }.isDfs)
    }

    @Test
    fun `analyze groups by band and skips empty bands`() {
        val mine = ap("Home", 36, -50, WifiBand.GHZ_5, width = 80)
        val result = ChannelAnalyzer.analyze(listOf(mine, ap("N", 1, -60)), mine)
        assertEquals(listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5), result.map { it.band })
        assertNull(result.first { it.band == WifiBand.GHZ_2_4 }.currentChannel)
    }
}
