package com.networkanalyst.core.wifi

import com.networkanalyst.core.model.AccessPoint
import com.networkanalyst.core.model.WifiBand

/** A span of spectrum in MHz. */
data class FrequencyRange(val lowMhz: Double, val highMhz: Double) {
    val widthMhz: Double get() = highMhz - lowMhz

    fun overlapMhz(other: FrequencyRange): Double =
        (minOf(highMhz, other.highMhz) - maxOf(lowMhz, other.lowMhz)).coerceAtLeast(0.0)
}

/**
 * Channel arithmetic from the IEEE 802.11 channel plans:
 *  - 2.4 GHz: centre = 2407 + 5 x channel (channel 14 is the 2484 MHz exception)
 *  - 5 GHz:   centre = 5000 + 5 x channel
 *  - 6 GHz:   centre = 5950 + 5 x channel (channel 2 at 5935 MHz is the exception)
 */
object WifiChannels {
    fun bandOf(frequencyMhz: Int): WifiBand = when (frequencyMhz) {
        in 2400..2500 -> WifiBand.GHZ_2_4
        in 5150..5895 -> WifiBand.GHZ_5
        in 5925..7125 -> WifiBand.GHZ_6
        in 57000..71000 -> WifiBand.GHZ_60
        else -> WifiBand.UNKNOWN
    }

    /** Channel number for the centre frequency of a 20 MHz channel, or null if it is not on the plan. */
    fun channelOf(frequencyMhz: Int): Int? = when (bandOf(frequencyMhz)) {
        WifiBand.GHZ_2_4 -> when {
            frequencyMhz == 2484 -> 14
            (frequencyMhz - 2407) % 5 == 0 -> ((frequencyMhz - 2407) / 5).takeIf { it in 1..13 }
            else -> null
        }
        WifiBand.GHZ_5 -> if ((frequencyMhz - 5000) % 5 == 0) (frequencyMhz - 5000) / 5 else null
        WifiBand.GHZ_6 -> when {
            frequencyMhz == 5935 -> 2
            (frequencyMhz - 5950) % 5 == 0 -> ((frequencyMhz - 5950) / 5).takeIf { it in 1..233 }
            else -> null
        }
        WifiBand.GHZ_60 -> if ((frequencyMhz - 56160) % 2160 == 0) (frequencyMhz - 56160) / 2160 else null
        WifiBand.UNKNOWN -> null
    }

    fun frequencyOf(band: WifiBand, channel: Int): Int? = when (band) {
        WifiBand.GHZ_2_4 -> when (channel) {
            14 -> 2484
            in 1..13 -> 2407 + 5 * channel
            else -> null
        }
        WifiBand.GHZ_5 -> if (channel in 32..177) 5000 + 5 * channel else null
        WifiBand.GHZ_6 -> if (channel in 1..233) 5950 + 5 * channel else null
        WifiBand.GHZ_60 -> if (channel in 1..6) 56160 + 2160 * channel else null
        WifiBand.UNKNOWN -> null
    }

    /**
     * 5 GHz channels that require Dynamic Frequency Selection in most regulatory domains
     * (U-NII-2A and U-NII-2C): the router must vacate them when it detects radar.
     */
    fun isDfs(channel5Ghz: Int): Boolean = channel5Ghz in 52..64 || channel5Ghz in 100..144

    /** The three 2.4 GHz channels that do not overlap each other at 20 MHz. */
    val NON_OVERLAPPING_2_4 = listOf(1, 6, 11)

    /** Primary 20 MHz channels of the standard 5 GHz plan. */
    val CHANNELS_5 = listOf(
        36, 40, 44, 48, 52, 56, 60, 64,
        100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
        149, 153, 157, 161, 165,
    )

    /**
     * Lowest primary channel of each bonded block in the 5 GHz plan. A channel wider than
     * 20 MHz is always one of these fixed blocks, so the block can be derived from the
     * primary channel alone.
     */
    private val BLOCK_STARTS_5 = mapOf(
        40 to listOf(36, 44, 52, 60, 100, 108, 116, 124, 132, 140, 149, 157, 165, 173),
        80 to listOf(36, 52, 100, 116, 132, 149, 165),
        160 to listOf(36, 100, 149),
    )

    /**
     * Spectrum occupied by a 5 GHz channel of [widthMhz] whose primary channel is [primaryChannel].
     * Falls back to a plain 20 MHz channel when the combination is not on the plan.
     */
    fun range5Ghz(primaryChannel: Int, widthMhz: Int): FrequencyRange {
        val primaryCenter = 5000.0 + 5 * primaryChannel
        val starts = BLOCK_STARTS_5[widthMhz]
        if (starts != null) {
            val channelsPerBlock = widthMhz / 20
            val start = starts.lastOrNull { primaryChannel >= it && primaryChannel < it + 4 * channelsPerBlock }
            if (start != null) {
                val low = 5000.0 + 5 * start - 10
                return FrequencyRange(low, low + widthMhz)
            }
        }
        return FrequencyRange(primaryCenter - 10, primaryCenter + 10)
    }

    /** Spectrum occupied by an access point, using the scan's centre frequency when it has one. */
    fun occupiedRange(ap: AccessPoint): FrequencyRange {
        val width = ap.channelWidthMhz.coerceAtLeast(20)
        val primary = ap.frequencyMhz.toDouble()
        if (width == 20) return FrequencyRange(primary - 10, primary + 10)

        // For 160 MHz some drivers put the 80 MHz segment centre in centerFreq0 and the full
        // channel centre in centerFreq1, so prefer whichever centre actually contains the
        // primary channel at the reported width.
        val centers = listOfNotNull(ap.centerFreq1Mhz, ap.centerFreq0Mhz).filter { it > 0 }
        for (center in centers) {
            val range = FrequencyRange(center - width / 2.0, center + width / 2.0)
            if (primary - 10 >= range.lowMhz - 0.5 && primary + 10 <= range.highMhz + 0.5) return range
        }

        return when (bandOf(ap.frequencyMhz)) {
            WifiBand.GHZ_5 -> channelOf(ap.frequencyMhz)?.let { range5Ghz(it, width) }
                ?: FrequencyRange(primary - 10, primary + 10)
            WifiBand.GHZ_2_4 -> {
                // 40 MHz on 2.4 GHz bonds a second channel above or below the primary. Without
                // a centre frequency assume "above" for low channels and "below" for high ones.
                val channel = channelOf(ap.frequencyMhz) ?: 6
                if (channel <= 7) FrequencyRange(primary - 10, primary + 30) else FrequencyRange(primary - 30, primary + 10)
            }
            WifiBand.GHZ_6 -> channelOf(ap.frequencyMhz)?.let { range6Ghz(it, width) }
                ?: FrequencyRange(primary - 10, primary + 10)
            else -> FrequencyRange(primary - 10, primary + 10)
        }
    }

    /**
     * Spectrum occupied by a 6 GHz channel. The 6 GHz plan tiles the band from 5945 MHz with
     * aligned blocks, so the block follows from the primary channel's 20 MHz slot.
     */
    fun range6Ghz(primaryChannel: Int, widthMhz: Int): FrequencyRange {
        val slotsPerBlock = (widthMhz / 20).coerceAtLeast(1)
        val slot = (primaryChannel - 1) / 4
        val low = 5945.0 + 20 * (slot / slotsPerBlock * slotsPerBlock)
        return FrequencyRange(low, low + slotsPerBlock * 20)
    }
}
