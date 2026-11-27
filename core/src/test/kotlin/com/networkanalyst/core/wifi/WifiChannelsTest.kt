package com.networkanalyst.core.wifi

import com.networkanalyst.core.model.AccessPoint
import com.networkanalyst.core.model.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiChannelsTest {
    @Test
    fun `frequency to channel on every band`() {
        val expected = mapOf(
            2412 to 1, 2437 to 6, 2462 to 11, 2472 to 13, 2484 to 14,
            5180 to 36, 5240 to 48, 5260 to 52, 5500 to 100, 5745 to 149, 5825 to 165,
            5955 to 1, 6115 to 33, 7115 to 233, 5935 to 2,
        )
        for ((frequency, channel) in expected) {
            assertEquals("channel of $frequency MHz", channel, WifiChannels.channelOf(frequency))
        }
        assertNull(WifiChannels.channelOf(2413))
        assertNull(WifiChannels.channelOf(900))
    }

    @Test
    fun `bands and round trip`() {
        assertEquals(WifiBand.GHZ_2_4, WifiChannels.bandOf(2437))
        assertEquals(WifiBand.GHZ_5, WifiChannels.bandOf(5180))
        assertEquals(WifiBand.GHZ_6, WifiChannels.bandOf(5955))
        assertEquals(WifiBand.GHZ_60, WifiChannels.bandOf(58320))
        assertEquals(2437, WifiChannels.frequencyOf(WifiBand.GHZ_2_4, 6))
        assertEquals(5745, WifiChannels.frequencyOf(WifiBand.GHZ_5, 149))
        assertEquals(5955, WifiChannels.frequencyOf(WifiBand.GHZ_6, 1))
        assertEquals(58320, WifiChannels.frequencyOf(WifiBand.GHZ_60, 1))
        assertEquals(1, WifiChannels.channelOf(58320))
    }

    @Test
    fun `dfs channels`() {
        assertFalse(WifiChannels.isDfs(36))
        assertFalse(WifiChannels.isDfs(48))
        assertTrue(WifiChannels.isDfs(52))
        assertTrue(WifiChannels.isDfs(100))
        assertTrue(WifiChannels.isDfs(144))
        assertFalse(WifiChannels.isDfs(149))
    }

    @Test
    fun `5 GHz bonded blocks follow the channel plan`() {
        // 80 MHz block 36-48 is centred on channel 42 (5210 MHz).
        assertEquals(FrequencyRange(5170.0, 5250.0), WifiChannels.range5Ghz(44, 80))
        // 40 MHz pair 149+153 is centred on channel 151 (5755 MHz).
        assertEquals(FrequencyRange(5735.0, 5775.0), WifiChannels.range5Ghz(153, 40))
        // 160 MHz block 36-64 is centred on channel 50 (5250 MHz).
        assertEquals(FrequencyRange(5170.0, 5330.0), WifiChannels.range5Ghz(60, 160))
        // 80 MHz block 100-112 is centred on channel 106 (5530 MHz).
        assertEquals(FrequencyRange(5490.0, 5570.0), WifiChannels.range5Ghz(108, 80))
        assertEquals(FrequencyRange(5170.0, 5190.0), WifiChannels.range5Ghz(36, 20))
    }

    @Test
    fun `6 GHz blocks`() {
        assertEquals(FrequencyRange(5945.0, 6025.0), WifiChannels.range6Ghz(5, 80))
        assertEquals(FrequencyRange(6105.0, 6265.0), WifiChannels.range6Ghz(37, 160))
        assertEquals(FrequencyRange(5945.0, 5965.0), WifiChannels.range6Ghz(1, 20))
    }

    @Test
    fun `occupied range prefers the scan centre frequency`() {
        val ap = AccessPoint("x", "aa:bb:cc:dd:ee:01", -50, 5180, channelWidthMhz = 80, centerFreq0Mhz = 5210)
        assertEquals(FrequencyRange(5170.0, 5250.0), WifiChannels.occupiedRange(ap))
        // 160 MHz reported as segment centre + full centre.
        val wide = AccessPoint("x", "aa:bb:cc:dd:ee:02", -50, 5180, channelWidthMhz = 160, centerFreq0Mhz = 5210, centerFreq1Mhz = 5250)
        assertEquals(FrequencyRange(5170.0, 5330.0), WifiChannels.occupiedRange(wide))
        // A centre that cannot contain the primary channel is ignored in favour of the plan.
        val bogus = AccessPoint("x", "aa:bb:cc:dd:ee:03", -50, 5745, channelWidthMhz = 80, centerFreq0Mhz = 5210)
        assertEquals(FrequencyRange(5735.0, 5815.0), WifiChannels.occupiedRange(bogus))
        // 2.4 GHz 40 MHz with a centre 10 MHz above the primary.
        val ht40 = AccessPoint("x", "aa:bb:cc:dd:ee:04", -50, 2412, channelWidthMhz = 40, centerFreq0Mhz = 2422)
        assertEquals(FrequencyRange(2402.0, 2442.0), WifiChannels.occupiedRange(ht40))
        val plain = AccessPoint("x", "aa:bb:cc:dd:ee:05", -50, 2437)
        assertEquals(FrequencyRange(2427.0, 2447.0), WifiChannels.occupiedRange(plain))
    }

    @Test
    fun `overlap between ranges`() {
        assertEquals(15.0, FrequencyRange(2402.0, 2422.0).overlapMhz(FrequencyRange(2407.0, 2427.0)), 1e-9)
        assertEquals(0.0, FrequencyRange(2402.0, 2422.0).overlapMhz(FrequencyRange(2427.0, 2447.0)), 1e-9)
        assertNotNull(WifiChannels.NON_OVERLAPPING_2_4)
    }
}
