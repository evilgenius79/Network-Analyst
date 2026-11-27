package com.networkanalyst.core.wifi

import com.networkanalyst.core.model.AccessPoint
import com.networkanalyst.core.model.WifiBand
import kotlin.math.pow

/** How busy one candidate channel is. Lower [score] is better. */
data class ChannelScore(
    val channel: Int,
    val score: Double,
    /** Other networks sitting on exactly this channel (they take turns with yours). */
    val coChannelCount: Int,
    /** Other networks that only partly overlap this channel (they collide with yours). */
    val overlappingCount: Int,
    val isDfs: Boolean = false,
)

data class BandAnalysis(
    val band: WifiBand,
    val scores: List<ChannelScore>,
    val networkCount: Int,
    /** Channel the phone's own access point is on, when it is in this band. */
    val currentChannel: Int? = null,
    val currentWidthMhz: Int? = null,
    val currentScore: ChannelScore? = null,
    /** Best alternative, set only when moving would be a real improvement. */
    val recommendedChannel: Int? = null,
    val recommendedScore: ChannelScore? = null,
)

/**
 * Scores Wi-Fi channels by how much they are disturbed by neighbouring networks.
 *
 * This is a heuristic, not a spectrum measurement: a phone can only see other access points'
 * beacons, not how much traffic they carry or non-Wi-Fi interference. Each neighbour adds
 *
 *     weight(signal) x overlap fraction x penalty
 *
 * to every channel its transmission overlaps. A neighbour on the same primary channel
 * coordinates with you (both wait their turn), while one that only partly overlaps cannot
 * decode your frames and collides with them, so partial overlap is penalised more heavily.
 */
object ChannelAnalyzer {
    private const val PARTIAL_OVERLAP_PENALTY = 1.5

    /** A move is only suggested when the score drops by this fraction and by this absolute amount. */
    private const val MIN_RELATIVE_GAIN = 0.4
    private const val MIN_ABSOLUTE_GAIN = 0.35

    /** Maps signal strength to 0..1: about -95 dBm is inaudible, -40 dBm is next to you. */
    fun signalWeight(rssiDbm: Int): Double =
        ((rssiDbm + 95) / 55.0).coerceIn(0.0, 1.0).pow(1.5)

    /**
     * Two BSSIDs that share their middle four bytes on the same frequency are almost always
     * the same physical radio broadcasting several network names (main, guest, IoT). They are
     * not competing with each other, so they are not counted as interference.
     */
    fun sameRadio(a: AccessPoint, b: AccessPoint): Boolean {
        if (a.frequencyMhz != b.frequencyMhz) return false
        val x = a.bssid.lowercase().split(':')
        val y = b.bssid.lowercase().split(':')
        if (x.size != 6 || y.size != 6) return a.bssid.equals(b.bssid, ignoreCase = true)
        return x.subList(1, 5) == y.subList(1, 5)
    }

    fun analyze(accessPoints: List<AccessPoint>, connected: AccessPoint?): List<BandAnalysis> =
        listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5, WifiBand.GHZ_6).mapNotNull { band ->
            val inBand = accessPoints.filter { WifiChannels.bandOf(it.frequencyMhz) == band }
            val connectedInBand = connected?.takeIf { WifiChannels.bandOf(it.frequencyMhz) == band }
            if (inBand.isEmpty() && connectedInBand == null) null else analyzeBand(band, inBand, connectedInBand)
        }

    fun analyzeBand(band: WifiBand, accessPoints: List<AccessPoint>, connected: AccessPoint?): BandAnalysis {
        val neighbours = accessPoints.filter { connected == null || !sameRadio(it, connected) }
        val width = when (band) {
            // 40 MHz on 2.4 GHz is discouraged; candidates are always judged at 20 MHz there.
            WifiBand.GHZ_2_4 -> 20
            else -> connected?.channelWidthMhz ?: 20
        }
        val candidates: List<Int> = when (band) {
            WifiBand.GHZ_2_4 -> (1..13).toList()
            WifiBand.GHZ_5 -> WifiChannels.CHANNELS_5
            WifiBand.GHZ_6 -> neighbours.mapNotNull { WifiChannels.channelOf(it.frequencyMhz) }
                .plus(listOfNotNull(connected?.let { WifiChannels.channelOf(it.frequencyMhz) }))
                .distinct().sorted()
            else -> emptyList()
        }

        val scores = candidates.map { score(band, it, width, neighbours) }
        val currentChannel = connected?.let { WifiChannels.channelOf(it.frequencyMhz) }
        val currentScore = currentChannel?.let { ch -> scores.firstOrNull { it.channel == ch } ?: score(band, ch, width, neighbours) }

        val recommended = recommend(band, scores, currentChannel, currentScore, width)
        return BandAnalysis(
            band = band,
            scores = scores,
            networkCount = neighbours.size,
            currentChannel = currentChannel,
            currentWidthMhz = connected?.channelWidthMhz,
            currentScore = currentScore,
            recommendedChannel = recommended?.channel,
            recommendedScore = recommended,
        )
    }

    private fun candidateRange(band: WifiBand, channel: Int, widthMhz: Int): FrequencyRange? = when (band) {
        WifiBand.GHZ_2_4 -> WifiChannels.frequencyOf(band, channel)?.let { FrequencyRange(it - 10.0, it + 10.0) }
        WifiBand.GHZ_5 -> WifiChannels.range5Ghz(channel, widthMhz)
        WifiBand.GHZ_6 -> WifiChannels.range6Ghz(channel, widthMhz)
        else -> null
    }

    private fun score(band: WifiBand, channel: Int, widthMhz: Int, neighbours: List<AccessPoint>): ChannelScore {
        val range = candidateRange(band, channel, widthMhz)
            ?: return ChannelScore(channel, 0.0, 0, 0)
        var total = 0.0
        var coChannel = 0
        var overlapping = 0
        for (ap in neighbours) {
            val apRange = WifiChannels.occupiedRange(ap)
            val overlap = range.overlapMhz(apRange)
            if (overlap <= 0.0) continue
            val fraction = overlap / range.widthMhz
            // "Same channel" means the neighbour's primary channel lies inside ours, so both
            // radios hear each other's frames and share airtime in an orderly way.
            val primaryInside = ap.frequencyMhz - 10 >= range.lowMhz - 0.5 && ap.frequencyMhz + 10 <= range.highMhz + 0.5
            val aligned = if (band == WifiBand.GHZ_2_4) WifiChannels.channelOf(ap.frequencyMhz) == channel && ap.channelWidthMhz <= 20 else primaryInside
            if (aligned) {
                coChannel++
                total += signalWeight(ap.rssiDbm) * fraction
            } else {
                overlapping++
                total += signalWeight(ap.rssiDbm) * fraction * PARTIAL_OVERLAP_PENALTY
            }
        }
        return ChannelScore(
            channel = channel,
            score = total,
            coChannelCount = coChannel,
            overlappingCount = overlapping,
            isDfs = band == WifiBand.GHZ_5 && dfsInBlock(channel, widthMhz),
        )
    }

    /** True when any part of the bonded block containing [channel] needs DFS. */
    private fun dfsInBlock(channel: Int, widthMhz: Int): Boolean {
        val range = WifiChannels.range5Ghz(channel, widthMhz)
        return WifiChannels.CHANNELS_5.any { ch ->
            val center = 5000.0 + 5 * ch
            center > range.lowMhz && center < range.highMhz && WifiChannels.isDfs(ch)
        }
    }

    private fun recommend(
        band: WifiBand,
        scores: List<ChannelScore>,
        currentChannel: Int?,
        currentScore: ChannelScore?,
        widthMhz: Int,
    ): ChannelScore? {
        if (currentChannel == null || currentScore == null) return null
        val pool = when (band) {
            WifiBand.GHZ_2_4 -> scores.filter { it.channel in WifiChannels.NON_OVERLAPPING_2_4 }
            // One representative (the lowest primary) per bonded block.
            WifiBand.GHZ_5 -> scores.distinctBy { WifiChannels.range5Ghz(it.channel, widthMhz) }
            else -> scores
        }
        // Prefer non-DFS channels when scores are close: DFS channels can be unavailable or
        // force the router to hop when it detects radar.
        val best = pool.minWithOrNull(compareBy<ChannelScore>({ it.score + if (it.isDfs) 0.15 else 0.0 }, { it.channel }))
            ?: return null
        if (band == WifiBand.GHZ_5 &&
            WifiChannels.range5Ghz(best.channel, widthMhz) == WifiChannels.range5Ghz(currentChannel, widthMhz)
        ) return null
        if (best.channel == currentChannel) return null

        // On 2.4 GHz anything other than 1, 6 or 11 straddles two of them, so always move.
        val offGrid = band == WifiBand.GHZ_2_4 && currentChannel !in WifiChannels.NON_OVERLAPPING_2_4
        val gain = currentScore.score - best.score
        val worthIt = gain >= MIN_ABSOLUTE_GAIN && gain >= currentScore.score * MIN_RELATIVE_GAIN
        return if (offGrid || worthIt) best else null
    }
}
