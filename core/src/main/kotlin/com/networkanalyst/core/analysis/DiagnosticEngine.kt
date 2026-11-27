package com.networkanalyst.core.analysis

import com.networkanalyst.core.model.AccessPoint
import com.networkanalyst.core.model.NetworkSnapshot
import com.networkanalyst.core.model.PingResult
import com.networkanalyst.core.model.ScanUnavailableReason
import com.networkanalyst.core.model.Transport
import com.networkanalyst.core.model.WifiBand
import com.networkanalyst.core.model.WifiSecurity
import com.networkanalyst.core.model.WifiStandard
import com.networkanalyst.core.speed.BufferbloatGrade
import com.networkanalyst.core.util.AddressScope
import com.networkanalyst.core.util.IpUtils
import com.networkanalyst.core.util.Stats
import com.networkanalyst.core.wifi.ChannelAnalyzer
import com.networkanalyst.core.wifi.WifiChannels
import java.util.Locale

/** Typical speeds seen on this network before, used to notice "slower than usual". */
data class SpeedBaseline(val downloadMbps: Double? = null, val uploadMbps: Double? = null)

/**
 * Turns the raw measurements of a scan into findings a person can act on.
 *
 * The rules compare numbers against thresholds chosen from common networking practice
 * (for example -67 dBm as the floor for reliable real-time traffic). They are judgement
 * calls, not standards; each rule states the measurement it is based on so the reader can
 * weigh it.
 */
object DiagnosticEngine {
    // Signal strength bands, dBm.
    private const val RSSI_GOOD = -67
    private const val RSSI_WEAK = -75

    // Packet loss, percent, after discounting a single lost probe (see PingResult).
    private const val LOSS_NOTABLE = 2.5
    private const val LOSS_SEVERE = 10.0

    fun diagnose(snapshot: NetworkSnapshot, baseline: SpeedBaseline? = null): Diagnosis {
        val findings = ArrayList<Finding>()
        val context = Context(snapshot, baseline, findings)

        context.connection()
        if (snapshot.connection.transport != Transport.NONE) {
            context.wifiSignal()
            context.wifiRoaming()
            context.wifiBand()
            context.wifiStandardAndSecurity()
            context.wifiWidth()
            context.wifiLinkRate()
            context.wifiChannel()
            context.localNetwork()
            context.internetLine()
            context.natLayers()
            context.router()
            context.dns()
            context.speed()
            context.bufferbloat()
            context.devices()
            // Depends on what the other rules found, so it runs last.
            context.routerUptime()
        }

        val sorted = findings.sortedWith(compareBy<Finding>({ it.severity.ordinal }, { it.category.ordinal }))
        val bottleneck = context.bottleneck(sorted)
        val (headline, summary) = context.headline(bottleneck, sorted)
        return Diagnosis(
            findings = sorted,
            healthScore = healthScore(sorted, snapshot),
            bottleneck = bottleneck,
            headline = headline,
            summary = summary,
            useCases = context.useCases(),
        )
    }

    /**
     * Each finding takes a share of what is left rather than a fixed number of points, so one
     * serious problem costs a lot while a long list of findings still leaves a meaningful
     * score instead of bottoming out.
     */
    private fun healthScore(findings: List<Finding>, snapshot: NetworkSnapshot): Int {
        if (snapshot.connection.transport == Transport.NONE) return 0
        var remaining = 1.0
        for (finding in findings) {
            remaining *= when (finding.severity) {
                Severity.CRITICAL -> 0.70
                Severity.WARNING -> 0.88
                Severity.INFO -> 0.99
                Severity.GOOD -> 1.0
            }
        }
        return Math.round(remaining * 100).toInt().coerceIn(1, 100)
    }

    private fun Double.fmt(decimals: Int = 0): String = String.format(Locale.US, "%.${decimals}f", this)

    private fun ms(value: Double): String = if (value < 10) "${value.fmt(1)} ms" else "${value.fmt(0)} ms"

    internal fun speedText(mbps: Double): String = when {
        mbps >= 1000 -> "${(mbps / 1000).fmt(2)} Gbps"
        mbps >= 10 -> "${mbps.fmt(0)} Mbps"
        else -> "${mbps.fmt(1)} Mbps"
    }

    private class Context(
        val snapshot: NetworkSnapshot,
        val baseline: SpeedBaseline?,
        val findings: MutableList<Finding>,
    ) {
        val connection = snapshot.connection
        val link = snapshot.wifiLink
        val onWifi = connection.transport == Transport.WIFI
        val accessPoints: List<AccessPoint> = snapshot.wifiScan?.accessPoints.orEmpty()
        val connectedAp: AccessPoint? = accessPoints.firstOrNull { it.isConnected }
            ?: link?.bssid?.let { bssid -> accessPoints.firstOrNull { it.bssid.equals(bssid, ignoreCase = true) } }
        val band: WifiBand? = link?.let { WifiChannels.bandOf(it.frequencyMhz) }

        /** Typical signal during the scan: the median of the samples, or the single reading. */
        val rssi: Int? = link?.let { l ->
            Stats.median(l.rssiSamplesDbm.map { it.toDouble() })?.toInt() ?: l.rssiDbm
        }
        val gatewayPing: PingResult? = snapshot.gatewayPing
        val internetPing: PingResult? = snapshot.internetPings.filter { it.reachable }.minByOrNull { it.medianMs ?: Double.MAX_VALUE }
        val speed = snapshot.speed
        val ssidLabel: String = link?.ssid?.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "this network"

        fun add(id: String, category: Category, severity: Severity, title: String, detail: String, fixes: List<String> = emptyList()) {
            findings += Finding(id, category, severity, title, detail, fixes)
        }

        fun has(idPrefix: String, atLeast: Severity = Severity.WARNING): Boolean =
            findings.any { it.id.startsWith(idPrefix) && it.severity.ordinal <= atLeast.ordinal }

        // ------------------------------------------------------------------ connection

        fun connection() {
            if (connection.transport == Transport.NONE) {
                add(
                    "connection.none", Category.CONNECTION, Severity.CRITICAL, "No network connection",
                    "The phone is not connected to Wi-Fi or mobile data, so nothing could be tested.",
                    listOf(
                        "Turn on Wi-Fi and join your network, or turn on mobile data.",
                        "If Wi-Fi will not connect, restart the router and try again.",
                    ),
                )
                return
            }
            if (connection.hasCaptivePortal) {
                add(
                    "connection.captive", Category.CONNECTION, Severity.WARNING, "This network needs a sign-in",
                    "The network is holding traffic until you sign in on its welcome page (common on hotel, café and guest networks). Results below may reflect the blocked state.",
                    listOf("Open the sign-in notification, or any web page, and complete the sign-in. Then scan again."),
                )
            } else if (!connection.isValidated) {
                val routerAnswers = gatewayPing?.reachable == true
                add(
                    "connection.nointernet", Category.CONNECTION, Severity.CRITICAL, "Connected, but no internet access",
                    if (routerAnswers) {
                        "The phone reaches your router (${ms(gatewayPing.medianMs!!)}), but Android cannot reach the internet through it. The fault is beyond the router: the modem, the line, or your provider."
                    } else {
                        "Android reports that this network has no working internet access."
                    },
                    listOf(
                        "Unplug the modem and router for 30 seconds. Plug the modem in first, wait for its lights to settle, then the router.",
                        "Check that the cable from the wall to the modem and from the modem to the router are firmly seated.",
                        "Look at the modem's status lights or its status page for a line or signal error.",
                        "Check your provider's outage page using mobile data, and contact them if it persists.",
                    ),
                )
            }
            if (connection.isVpn) {
                add(
                    "connection.vpn", Category.CONNECTION, Severity.INFO, "A VPN is active",
                    "All traffic is going through a VPN, which adds distance and encryption overhead. The speed and latency in this report are those of the VPN path, not of your line on its own.",
                    listOf(
                        "Pause the VPN and scan again to see what your line can really do.",
                        "If the VPN is much slower, choose a VPN server closer to you or a faster protocol such as WireGuard.",
                    ),
                )
            }
            if (connection.transport == Transport.CELLULAR) {
                add(
                    "connection.cellular", Category.CONNECTION, Severity.INFO,
                    "On mobile data" + (connection.cellularGeneration?.let { " ($it)" } ?: ""),
                    "The phone is using the mobile network, so the Wi-Fi and home-network checks were skipped. Mobile speeds depend on signal and on how busy the cell is.",
                    listOf("Connect to your Wi-Fi network and scan again to analyse the home network."),
                )
            }
            if (connection.batterySaverOn) {
                add(
                    "connection.batterysaver", Category.CONNECTION, Severity.INFO, "Battery Saver is on",
                    "Battery Saver limits background activity and, on some phones, makes the Wi-Fi radio sleep more aggressively, which can add delay.",
                    listOf("Turn Battery Saver off while testing to rule it out."),
                )
            }
            if (connection.httpProxy != null) {
                add(
                    "connection.proxy", Category.CONNECTION, Severity.INFO, "Traffic goes through a proxy",
                    "This network is configured with a web proxy (${connection.httpProxy}). Web traffic takes a detour through it, which can limit speed.",
                )
            }
        }

        // ------------------------------------------------------------------ Wi-Fi signal

        fun wifiSignal() {
            if (!onWifi || link == null || rssi == null) return
            val weakFixes = buildList {
                add("Move closer to the router, or move the router closer to where you use the connection most.")
                add("Put the router high up and out in the open, near the middle of the home, away from thick walls, metal, mirrors, aquariums and the microwave.")
                if (band == WifiBand.GHZ_5 || band == WifiBand.GHZ_6) {
                    add("${band.label} fades quickly through walls. If the signal cannot be improved here, the 2.4 GHz band reaches further, at a lower top speed.")
                }
                add("For rooms the router cannot reach, add a mesh node or a wired access point. Cheap repeaters halve the speed.")
            }
            when {
                rssi < RSSI_WEAK -> add(
                    "wifi.signal.veryweak", Category.WIFI_SIGNAL, Severity.CRITICAL, "Very weak Wi-Fi signal",
                    "The signal from the router is $rssi dBm. Below about $RSSI_WEAK dBm the connection drops to its slowest rates and loses packets, so everything feels slow and calls break up.",
                    weakFixes,
                )
                rssi < RSSI_GOOD -> add(
                    "wifi.signal.weak", Category.WIFI_SIGNAL, Severity.WARNING, "Weak Wi-Fi signal",
                    "The signal from the router is $rssi dBm. Under about $RSSI_GOOD dBm speeds fall well below what the router can deliver and video calls may stutter.",
                    weakFixes,
                )
                else -> add(
                    "wifi.signal.good", Category.WIFI_SIGNAL, Severity.GOOD,
                    if (rssi >= -55) "Excellent Wi-Fi signal" else "Good Wi-Fi signal",
                    "The signal from the router is $rssi dBm, which is strong enough for full speed.",
                )
            }

            val samples = link.rssiSamplesDbm.map { it.toDouble() }
            val spread = Stats.stdDev(samples)
            if (samples.size >= 5 && spread != null && spread >= 5.0) {
                add(
                    "wifi.signal.unstable", Category.WIFI_SIGNAL, Severity.WARNING, "Wi-Fi signal is fluctuating",
                    "Signal strength swung between ${samples.min().toInt()} and ${samples.max().toInt()} dBm in a few seconds while the phone was still. That usually means interference or something moving between you and the router, and it causes uneven speed.",
                    listOf(
                        "Keep the router away from cordless phone bases, baby monitors, microwaves and Bluetooth hubs.",
                        "Try the other band (5 GHz instead of 2.4 GHz, or the reverse) and see which is steadier here.",
                    ),
                )
            }
        }

        fun wifiRoaming() {
            val current = connectedAp ?: return
            val ssid = link?.ssid?.takeIf { it.isNotBlank() } ?: return
            val better = accessPoints
                .filter { it.ssid == ssid && !ChannelAnalyzer.sameRadio(it, current) }
                .filter { it.rssiDbm >= current.rssiDbm + 12 && it.rssiDbm >= -70 }
                .maxByOrNull { it.rssiDbm } ?: return
            // A stronger radio of the same router on another band is a band choice, covered elsewhere.
            if (current.rssiDbm >= RSSI_GOOD) return
            add(
                "wifi.roaming.sticky", Category.WIFI_SIGNAL, Severity.WARNING, "Connected to a far-away access point",
                "The phone is holding on to an access point at ${current.rssiDbm} dBm while another one for \"$ssid\" is much stronger here (${better.rssiDbm} dBm). Phones often stay with the first access point they joined.",
                listOf(
                    "Turn Wi-Fi off and on again so the phone picks the stronger access point.",
                    "If it keeps happening, enable roaming assistance on your mesh or access points (802.11k/v, sometimes called fast roaming or client steering), or lower the transmit power of the distant one.",
                ),
            )
        }

        fun wifiBand() {
            if (!onWifi || link == null || band != WifiBand.GHZ_2_4) return
            val ssid = link.ssid?.takeIf { it.isNotBlank() }
            val siblings = accessPoints
                .filter { ssid != null && it.ssid == ssid }
                .filter { WifiChannels.bandOf(it.frequencyMhz).let { b -> b == WifiBand.GHZ_5 || b == WifiBand.GHZ_6 } }
            val fasterSibling = siblings.filter { it.rssiDbm >= -72 }.maxByOrNull { it.rssiDbm }
            val weakSibling = siblings.maxByOrNull { it.rssiDbm }
            when {
                fasterSibling != null -> add(
                    "wifi.band.slow", Category.WIFI_SETUP, Severity.WARNING, "On the slow 2.4 GHz band while a faster one is in range",
                    "$ssidLabel also broadcasts on ${WifiChannels.bandOf(fasterSibling.frequencyMhz).label} at ${fasterSibling.rssiDbm} dBm here. That band is typically several times faster and far less crowded than 2.4 GHz.",
                    listOf(
                        "Turn Wi-Fi off and on, or forget the network and rejoin it, so the phone chooses again.",
                        "In the router settings, turn on band steering (often called Smart Connect), or give the 5 GHz band its own network name and connect to that.",
                    ),
                )
                weakSibling != null -> add(
                    "wifi.band.5weak", Category.WIFI_SETUP, Severity.INFO, "On 2.4 GHz because 5 GHz is too weak here",
                    "$ssidLabel also broadcasts on ${WifiChannels.bandOf(weakSibling.frequencyMhz).label}, but only at ${weakSibling.rssiDbm} dBm at this spot, so the phone is right to stay on 2.4 GHz. Closer to the router the faster band becomes usable.",
                    listOf("To get the faster band here, improve coverage: move the router, or add a mesh node or wired access point nearby."),
                )
                snapshot.wifiCapabilities?.supports5Ghz == false -> add(
                    "wifi.band.phone24only", Category.WIFI_SETUP, Severity.INFO, "This phone only supports 2.4 GHz Wi-Fi",
                    "The phone's Wi-Fi radio cannot use 5 GHz, so it is limited to the slower, more crowded 2.4 GHz band regardless of the router.",
                )
                accessPoints.isNotEmpty() -> add(
                    "wifi.band.24only", Category.WIFI_SETUP, Severity.INFO, "Connected on 2.4 GHz",
                    "No 5 GHz signal from $ssidLabel was seen here. Either the router is 2.4 GHz only, its 5 GHz radio is off or named differently, or it does not reach this spot. 2.4 GHz is the slowest and most crowded band.",
                    listOf(
                        "Check in the router settings that the 5 GHz radio is enabled.",
                        "If the 5 GHz network has a different name, join that one when you are near the router.",
                    ),
                )
            }
        }

        fun wifiStandardAndSecurity() {
            if (!onWifi || link == null) return
            val capabilities = snapshot.wifiCapabilities
            when (link.standard) {
                WifiStandard.LEGACY -> add(
                    "wifi.standard.legacy", Category.WIFI_SETUP, Severity.WARNING, "Connected with an outdated Wi-Fi standard",
                    "This connection is using 802.11a/b/g, which tops out at 54 Mbps on paper and far less in practice. Every router made in the last fifteen years can do better.",
                    listOf(
                        "In the router's wireless settings, set the mode to include 802.11n/ac/ax (often \"Auto\" or \"Mixed\").",
                        "Make sure security is WPA2 with AES. WEP and WPA-TKIP force these old, slow rates.",
                        "If the router really is that old, replacing it is the single biggest improvement available.",
                    ),
                )
                WifiStandard.N -> if (band == WifiBand.GHZ_5 && (capabilities?.supportsWifi5 == true || capabilities?.supportsWifi6 == true)) {
                    add(
                        "wifi.standard.router_old", Category.WIFI_SETUP, Severity.INFO, "The router's Wi-Fi is a generation or two behind the phone",
                        "The connection is Wi-Fi 4 (802.11n) although this phone supports newer, faster Wi-Fi. The router is the limiting side.",
                        listOf("Check that the router's 5 GHz mode allows 802.11ac/ax. If it is a Wi-Fi 4 router, a Wi-Fi 6 model would be considerably faster."),
                    )
                }
                else -> Unit
            }

            when (link.security) {
                WifiSecurity.WEP, WifiSecurity.WPA -> add(
                    "wifi.security.legacy", Category.SECURITY, Severity.WARNING, "Outdated Wi-Fi security is also slowing the network",
                    "The network uses ${link.security.label}. It can be broken in minutes, and the Wi-Fi standard does not allow fast (802.11n and newer) rates with it, which caps the link at 54 Mbps.",
                    listOf("Change the router's Wi-Fi security to WPA2 with AES, or WPA2/WPA3 mixed mode, then rejoin the network."),
                )
                WifiSecurity.OPEN -> add(
                    "wifi.security.open", Category.SECURITY, Severity.WARNING, "The network has no password",
                    "Anyone nearby can join, use your bandwidth and observe unencrypted traffic.",
                    listOf(
                        "If this is your network, set WPA2 or WPA3 security with a strong password in the router settings.",
                        "If it is a public network, avoid sensitive use or use a VPN.",
                    ),
                )
                else -> Unit
            }
        }

        fun wifiWidth() {
            if (!onWifi || link == null) return
            val width = link.channelWidthMhz ?: connectedAp?.channelWidthMhz ?: return
            if (band == WifiBand.GHZ_2_4 && width >= 40) {
                add(
                    "wifi.width.24wide", Category.WIFI_SETUP, Severity.WARNING, "40 MHz channel on 2.4 GHz",
                    "The router uses a 40 MHz wide channel on 2.4 GHz. That covers most of the band, so it collides with nearly every neighbouring network and is usually slower and less stable than a 20 MHz channel.",
                    listOf("Set the 2.4 GHz channel width to 20 MHz in the router's wireless settings."),
                )
            } else if (band == WifiBand.GHZ_5 && width == 20 && link.standard.generation >= 5) {
                add(
                    "wifi.width.5narrow", Category.WIFI_SETUP, Severity.INFO, "5 GHz channel is only 20 MHz wide",
                    "Wi-Fi 5 and newer can use 80 MHz channels on 5 GHz, roughly four times the capacity of the 20 MHz channel in use.",
                    listOf("Set the 5 GHz channel width to 80 MHz (or Auto) in the router's wireless settings."),
                )
            }
        }

        fun wifiLinkRate() {
            if (!onWifi || link == null) return
            val rate = listOfNotNull(link.txLinkSpeedMbps, link.rxLinkSpeedMbps).filter { it > 0 }.minOrNull() ?: return
            val signalFine = (rssi ?: -100) >= -65
            if (rate < 30) {
                add(
                    "wifi.linkrate.low", Category.WIFI_SIGNAL, Severity.WARNING, "Very low Wi-Fi link rate ($rate Mbps)",
                    if (signalFine) {
                        "The phone and router have negotiated only $rate Mbps even though the signal is strong. That points to interference on the channel or to the router running in an old compatibility mode."
                    } else {
                        "The phone and router have negotiated only $rate Mbps, which fits the weak signal. Usable speed is about half the link rate."
                    },
                    listOf(
                        "Fix the signal and channel issues listed in this report first.",
                        "Check that the router's wireless mode is not restricted to 802.11b/g.",
                    ),
                )
            }
            val download = speed?.downloadMbps ?: return
            val downRate = (link.rxLinkSpeedMbps ?: rate).takeIf { it > 0 } ?: rate
            // Real throughput is roughly half the negotiated rate, so a result this close to the
            // link rate means the radio link, not the line, was the ceiling.
            if (download >= downRate * 0.4 && downRate < 600) {
                add(
                    "wifi.linkrate.ceiling", Category.WIFI_SIGNAL, if (downRate < 100) Severity.WARNING else Severity.INFO,
                    "Wi-Fi is the speed limit here",
                    "The phone's Wi-Fi link is running at $downRate Mbps, and usable speed is typically around half of that. The ${speedText(download)} measured is about all this Wi-Fi connection can carry right now, so a faster internet plan would not help at this spot.",
                    listOf(
                        "Improve the link: stronger signal, the 5 GHz band, an 80 MHz channel width.",
                        "Use a wired connection for devices that need the full speed of the line.",
                    ),
                )
            }
        }

        fun wifiChannel() {
            if (!onWifi) return
            val scan = snapshot.wifiScan ?: return
            scan.unavailableReason?.let { reason ->
                val (why, fix) = when (reason) {
                    ScanUnavailableReason.LOCATION_PERMISSION_MISSING ->
                        "Android only lets apps see nearby Wi-Fi networks with the Location permission." to "Allow the Location permission (set to Precise) for this app, then scan again."
                    ScanUnavailableReason.LOCATION_SERVICES_OFF ->
                        "Android only lets apps see nearby Wi-Fi networks while Location is switched on." to "Turn on Location in the phone's quick settings, then scan again."
                    ScanUnavailableReason.THROTTLED ->
                        "Android limits how often apps may scan for Wi-Fi networks (four times in two minutes)." to "Wait a couple of minutes and scan again."
                    ScanUnavailableReason.FAILED -> "The Wi-Fi scan did not return any networks." to "Scan again in a moment."
                    ScanUnavailableReason.NOT_ON_WIFI -> return
                }
                if (accessPoints.isEmpty()) {
                    add(
                        "wifi.channel.unavailable", Category.WIFI_CHANNEL, Severity.INFO, "Channel congestion could not be checked",
                        "$why Without that list, crowded channels and neighbouring networks cannot be analysed.",
                        listOf(fix),
                    )
                    return
                }
            }
            val current = connectedAp ?: return
            val analysis = ChannelAnalyzer.analyze(accessPoints, current)
                .firstOrNull { it.band == WifiChannels.bandOf(current.frequencyMhz) } ?: return
            val score = analysis.currentScore ?: return
            val channel = analysis.currentChannel ?: return
            val bandLabel = analysis.band.label
            val neighbours = buildString {
                append("${score.coChannelCount} other network${if (score.coChannelCount == 1) "" else "s"} on the same channel")
                if (score.overlappingCount > 0) append(" and ${score.overlappingCount} overlapping it from next-door channels")
            }
            val recommended = analysis.recommendedChannel
            when {
                recommended != null -> {
                    val offGrid = analysis.band == WifiBand.GHZ_2_4 && channel !in WifiChannels.NON_OVERLAPPING_2_4
                    add(
                        "wifi.channel.crowded", Category.WIFI_CHANNEL, Severity.WARNING,
                        if (offGrid) "Router is on an overlapping Wi-Fi channel" else "Wi-Fi channel $channel is crowded",
                        "Your router is on channel $channel ($bandLabel) with $neighbours. " +
                            (if (offGrid) "Channel $channel sits between the standard channels and interferes with two of them at once. " else "") +
                            "Channel $recommended is much quieter where you are standing.",
                        buildList {
                            add("In the router's wireless settings, change the $bandLabel channel to $recommended.")
                            add("If the channel is set to Auto, restarting the router makes it choose again.")
                            if (analysis.band == WifiBand.GHZ_2_4) add("On 2.4 GHz only use channels 1, 6 or 11. Anything in between overlaps two of them.")
                            if (analysis.recommendedScore?.isDfs == true) {
                                add("Channel $recommended is a DFS channel: the router has to pause and move if it detects radar, and some devices cannot use it. If that causes drop-outs, pick the quietest of 36 to 48 or 149 to 161 instead.")
                            }
                        },
                    )
                }
                score.score >= 1.2 -> add(
                    "wifi.channel.busyband", Category.WIFI_CHANNEL, Severity.INFO, "The whole $bandLabel band is busy here",
                    "Channel $channel has $neighbours, and no other channel is clearly better (${analysis.networkCount} networks seen on $bandLabel). Changing channel will not gain much.",
                    buildList {
                        if (analysis.band == WifiBand.GHZ_2_4) add("Use the 5 GHz band wherever the signal allows; it has many more channels.")
                        add("Connect TVs, consoles and desktop computers by cable so they stop competing for airtime.")
                    },
                )
                else -> add(
                    "wifi.channel.clear", Category.WIFI_CHANNEL, Severity.GOOD, "Wi-Fi channel is clear",
                    "Channel $channel ($bandLabel) has $neighbours and no quieter channel worth moving to.",
                )
            }
        }

        // ------------------------------------------------------------------ phone to router

        fun localNetwork() {
            val ping = gatewayPing ?: return
            if (!onWifi) return
            if (!ping.reachable) {
                if (internetPing != null) {
                    add(
                        "local.noping", Category.LOCAL_NETWORK, Severity.INFO, "Router does not answer ping",
                        "The router (${ping.target}) ignored the test packets, so the quality of the hop between phone and router could not be measured directly. Some routers are set up this way.",
                    )
                }
                return
            }
            val median = ping.medianMs!!
            val loss = ping.significantLossPercent
            val lostText = "${ping.lost} of ${ping.sent} test packets (${loss.fmt(0)}%)"
            val jitter = ping.jitterMs ?: 0.0
            val wifiFixes = listOf(
                "Work through the Wi-Fi signal and channel findings in this report; this hop is the Wi-Fi link itself.",
                "Restart the router if it has been running for a long time.",
                "If many devices are active, connect the stationary ones by cable.",
            )
            when {
                loss >= LOSS_SEVERE -> add(
                    "local.loss.high", Category.LOCAL_NETWORK, Severity.CRITICAL, "Packets are being lost between phone and router",
                    "$lostText to the router (${ping.target}) never came back. Lost packets have to be sent again, which shows up as stalls, buffering and broken calls. This is inside your home, before the internet line.",
                    wifiFixes,
                )
                loss >= LOSS_NOTABLE -> add(
                    "local.loss", Category.LOCAL_NETWORK, Severity.WARNING, "Some packet loss between phone and router",
                    "$lostText to the router (${ping.target}) were lost. A healthy Wi-Fi link loses almost none.",
                    wifiFixes,
                )
            }
            when {
                median >= 100 -> add(
                    "local.latency.high", Category.LOCAL_NETWORK, Severity.CRITICAL, "Very slow response from the router",
                    "The router takes ${ms(median)} to answer from here; it should be a few milliseconds. The delay is in the Wi-Fi link or in an overloaded router, and it is added to everything you do online.",
                    wifiFixes,
                )
                median >= 30 -> add(
                    "local.latency", Category.LOCAL_NETWORK, Severity.WARNING, "Slow response from the router",
                    "The router takes ${ms(median)} to answer from here; a healthy Wi-Fi link manages well under 10 ms.",
                    wifiFixes,
                )
                jitter >= 15 && loss < LOSS_NOTABLE -> add(
                    "local.jitter", Category.LOCAL_NETWORK, Severity.WARNING, "Uneven response from the router",
                    "Replies from the router vary by ${ms(jitter)} from one packet to the next (median ${ms(median)}). That unevenness is typical of a busy or interfered-with Wi-Fi channel and is what makes calls and games stutter.",
                    wifiFixes,
                )
                loss < LOSS_NOTABLE -> add(
                    "local.good", Category.LOCAL_NETWORK, Severity.GOOD, "Link to the router is healthy",
                    "The router answers in ${ms(median)} with no meaningful loss.",
                )
            }
        }

        // ------------------------------------------------------------------ internet line

        fun internetLine() {
            if (snapshot.internetPings.isEmpty()) return
            val best = internetPing
            if (best == null) {
                // Nothing answered. If the line otherwise works, ping is simply being filtered.
                if (connection.isValidated && speed?.downloadMbps != null) {
                    add(
                        "internet.noping", Category.INTERNET, Severity.INFO, "Internet ping test got no replies",
                        "Test packets to public servers were not answered although the connection works, so something on the path filters them (common on VPNs and some mobile networks). Latency is taken from the speed test instead.",
                    )
                }
                return
            }
            val median = best.medianMs!!
            // Loss counts against the line only for the part not already explained by the Wi-Fi hop.
            // Taking the least lossy target means every target must show loss before the line is blamed.
            val localLoss = gatewayPing?.takeIf { it.reachable }?.significantLossPercent ?: 0.0
            val lineLoss = (snapshot.internetPings.filter { it.reachable }.minOf { it.significantLossPercent } - localLoss).coerceAtLeast(0.0)
            val localMedian = gatewayPing?.medianMs ?: 0.0
            val beyondRouter = (median - localMedian).coerceAtLeast(0.0)
            val lineFixes = listOf(
                "Restart the modem (and router): unplug for 30 seconds, modem first.",
                "Check the cables to the modem: coax or phone cables should be tight, undamaged and without unnecessary splitters.",
                "Test at a different time of day. If it is only bad in the evening, the provider's network is congested.",
                "Give these numbers to your provider; loss or high latency past your router is theirs to fix.",
            )
            when {
                lineLoss >= LOSS_SEVERE -> add(
                    "internet.loss.high", Category.INTERNET, Severity.CRITICAL, "Packet loss on the internet line",
                    "${lineLoss.fmt(0)}% of test packets to the internet were lost beyond what the Wi-Fi link loses. The fault is past your router: modem, line or provider.",
                    lineFixes,
                )
                lineLoss >= LOSS_NOTABLE -> add(
                    "internet.loss", Category.INTERNET, Severity.WARNING, "Some packet loss on the internet line",
                    "${lineLoss.fmt(0)}% of test packets to the internet were lost beyond what the Wi-Fi link loses.",
                    lineFixes,
                )
            }
            val mobile = connection.transport == Transport.CELLULAR
            val jitter = best.jitterMs ?: 0.0
            when {
                median >= 250 -> add(
                    "internet.latency.high", Category.INTERNET, Severity.CRITICAL, "Very high internet latency",
                    "Reaching ${best.label} takes ${ms(median)}" + routerShare(localMedian, beyondRouter) + ". At this level pages feel sluggish and real-time use (calls, games) is poor. Satellite links and distant VPN servers behave like this.",
                    if (mobile) emptyList() else lineFixes,
                )
                median >= 100 -> add(
                    "internet.latency", Category.INTERNET, Severity.WARNING, "High internet latency",
                    "Reaching ${best.label} takes ${ms(median)}" + routerShare(localMedian, beyondRouter) + ". Browsing is fine but calls and games will feel delayed.",
                    if (mobile) emptyList() else lineFixes,
                )
                jitter >= 30 && lineLoss < LOSS_NOTABLE -> add(
                    "internet.jitter", Category.INTERNET, Severity.WARNING, "Unsteady internet latency",
                    "Latency to ${best.label} varies by ${ms(jitter)} between packets (median ${ms(median)}). Uneven delay makes voice and video calls choppy.",
                    if (mobile) emptyList() else lineFixes,
                )
                lineLoss < LOSS_NOTABLE -> add(
                    "internet.good", Category.INTERNET, Severity.GOOD, "Internet latency is good",
                    "Reaching ${best.label} takes ${ms(median)} with no meaningful loss.",
                )
            }
        }

        private fun routerShare(localMs: Double, beyondMs: Double): String =
            if (localMs > 0) " (${ms(localMs)} to your router, ${ms(beyondMs)} beyond it)" else ""

        // ------------------------------------------------------------------ NAT layers

        fun natLayers() {
            val external = snapshot.gateway?.externalIp
            val externalScope = external?.let(IpUtils::scopeOfIpv4)
            // Private addresses seen on the path after the first hop also reveal a second router.
            val privateHops = snapshot.path.mapNotNull { it.address }.distinct()
                .filter { IpUtils.scopeOfIpv4(it) == AddressScope.PRIVATE }
            val doubleNat = externalScope == AddressScope.PRIVATE ||
                (externalScope == null && privateHops.size >= 2 && privateHops.first() == connection.gatewayV4)
            if (doubleNat) {
                add(
                    "nat.double", Category.ROUTER, Severity.WARNING, "Two routers in a row (double NAT)",
                    if (external != null) {
                        "Your router's internet-side address is $external, which is itself a private address. So it sits behind another router, usually the provider's modem-router. Traffic is translated twice, which breaks port forwarding, hurts gaming and video calls, and adds a second device that can slow things down."
                    } else {
                        "The path to the internet passes through two private routers (${privateHops.take(2).joinToString(" then ")}). Traffic is translated twice, which breaks port forwarding and hurts gaming and video calls."
                    },
                    listOf(
                        "Put the provider's modem-router into bridge (modem-only) mode so your own router gets the public address.",
                        "Or switch your own router to access-point mode and let the provider's box do the routing.",
                        "Make sure Wi-Fi is turned off on whichever box is not serving your devices, so they do not interfere.",
                    ),
                )
            } else if (externalScope == AddressScope.CGNAT || snapshot.path.any { it.address?.let(IpUtils::scopeOfIpv4) == AddressScope.CGNAT }) {
                add(
                    "nat.cgnat", Category.ROUTER, Severity.INFO, "Your provider uses carrier-grade NAT",
                    "Your router does not have a public address of its own; the provider shares one among many customers. Normal use is unaffected, but hosting games, port forwarding and some peer-to-peer apps will not work well.",
                    listOf("If you need incoming connections, ask the provider for a public IPv4 address, or use IPv6 if it is offered."),
                )
            }
        }

        // ------------------------------------------------------------------ router

        fun router() {
            val gateway = snapshot.gateway ?: return
            val name = listOfNotNull(gateway.manufacturer, gateway.modelName).joinToString(" ").ifBlank { gateway.friendlyName ?: "The router" }

            val background = gateway.backgroundDownBps?.let { it / 1_000_000 }
            val backgroundUp = gateway.backgroundUpBps?.let { it / 1_000_000 }
            val download = speed?.downloadMbps
            val upload = speed?.uploadMbps
            val heavyDown = background != null && background >= 3.0 && (download == null || background >= download * 0.25)
            val heavyUp = backgroundUp != null && backgroundUp >= 1.0 && (upload == null || backgroundUp >= upload * 0.25)
            if (heavyDown || heavyUp) {
                val parts = buildList {
                    if (heavyDown) add("${speedText(background)} down")
                    if (heavyUp) add("${speedText(backgroundUp)} up")
                }
                add(
                    "router.background", Category.ROUTER, Severity.WARNING, "Other devices are using the line right now",
                    "While this phone was idle, the router reported ${parts.joinToString(" and ")} passing through its internet port. That traffic belongs to other devices and leaves less for everyone else" +
                        (if (heavyUp) "; heavy uploads in particular make the whole connection feel laggy." else "."),
                    listOf(
                        "Look for a device that is streaming, downloading, updating or backing up to the cloud, and pause it.",
                        "Schedule backups and large updates for the night.",
                        "Turn on the router's QoS or Smart Queue Management so one device cannot swamp the rest.",
                    ),
                )
            }

            val wanMbps = gateway.maxDownstreamBps?.let { it / 1_000_000.0 }
            if (wanMbps != null && (wanMbps == 100.0 || wanMbps == 10.0) && download != null && download >= wanMbps * 0.8) {
                add(
                    "router.wanlink", Category.ROUTER, Severity.WARNING, "Router's internet port is linked at only ${wanMbps.fmt(0)} Mbps",
                    "$name reports its internet port running at ${wanMbps.fmt(0)} Mbps, and the measured ${speedText(download)} sits right at that ceiling. If your plan is faster, the cable or port between modem and router is the bottleneck.",
                    listOf(
                        "Replace the Ethernet cable between the modem and the router with a Cat 5e or Cat 6 cable; a damaged or four-wire cable falls back to 100 Mbps.",
                        "Try a different port on the modem, and check that both devices have gigabit ports.",
                    ),
                )
            }

        }

        fun routerUptime() {
            val uptimeDays = snapshot.gateway?.uptimeSeconds?.let { it / 86_400 } ?: return
            if (uptimeDays >= 60 && findings.any { it.severity == Severity.CRITICAL || it.severity == Severity.WARNING }) {
                add(
                    "router.uptime", Category.ROUTER, Severity.INFO, "The router has not been restarted for $uptimeDays days",
                    "Consumer routers sometimes slow down after running for months. Given the other problems found, a restart is a cheap thing to try.",
                    listOf("Restart the router, then scan again to see whether anything changes."),
                )
            }
        }

        // ------------------------------------------------------------------ DNS

        fun dns() {
            val dns = snapshot.dns ?: return
            if (dns.servers.isEmpty()) return
            val system = dns.bestSystem
            val fastest = dns.fastestPublic
            val privateDnsNote = if (connection.privateDnsActive) " Android's Private DNS is on, so real lookups are encrypted and take slightly longer than this plain measurement." else ""
            val changeDns = buildList {
                if (fastest != null) add("Use a faster resolver such as ${fastest.name} (${fastest.address}): set it as the DNS server in the router's internet or DHCP settings so every device benefits.")
                add("Or set it on this phone only: Settings, Network & internet, Private DNS, and enter a provider hostname such as one.one.one.one (Cloudflare), dns.google (Google) or dns.quad9.net (Quad9).")
            }
            if (dns.systemServers.isNotEmpty() && system == null) {
                add(
                    "dns.dead", Category.DNS, if (fastest != null) Severity.CRITICAL else Severity.WARNING, "The network's DNS server is not answering",
                    "None of the test lookups sent to the DNS server this network hands out (${dns.systemServers.joinToString { it.address }}) were answered" +
                        (if (fastest != null) ", while public DNS servers answered normally. Names fail or take seconds to resolve, which makes every site slow to start loading." else ". Direct DNS queries may be blocked on this network.") + privateDnsNote,
                    if (fastest != null) changeDns + "Restart the router; its built-in DNS forwarder may have hung." else emptyList(),
                )
                return
            }
            if (system == null) return
            val systemMedian = system.medianMs ?: return
            val worstFailure = dns.systemServers.maxOf { it.failurePercent }
            if (system.failurePercent >= 20.0) {
                add(
                    "dns.failing", Category.DNS, Severity.CRITICAL, "DNS lookups are failing",
                    "${system.failurePercent.fmt(0)}% of test lookups to your DNS server (${system.address}) got no answer. Each failure stalls a page for seconds before the phone retries.$privateDnsNote",
                    changeDns,
                )
                return
            }
            val fastestMedian = fastest?.medianMs
            val muchFaster = fastestMedian != null && fastestMedian < systemMedian * 0.5 && systemMedian - fastestMedian >= 20
            when {
                systemMedian >= 100 -> add(
                    "dns.slow", Category.DNS, Severity.WARNING, "Slow DNS server",
                    "Your DNS server (${system.address}) takes ${ms(systemMedian)} to answer" +
                        (if (fastestMedian != null) ", while ${fastest.name} answers in ${ms(fastestMedian)} from here" else "") +
                        ". Every new site needs several lookups before it can start loading, so this delay is felt on each page.$privateDnsNote",
                    changeDns,
                )
                muchFaster -> add(
                    "dns.faster_available", Category.DNS, Severity.INFO, "A faster DNS server is available",
                    "Your DNS server (${system.address}) answers in ${ms(systemMedian)}; ${fastest.name} answers in ${ms(fastestMedian)} from here. Switching would make pages start loading a little sooner.$privateDnsNote",
                    changeDns,
                )
                worstFailure >= 20.0 -> add(
                    "dns.secondary_failing", Category.DNS, Severity.INFO, "One of your DNS servers is unreliable",
                    "One of the DNS servers this network hands out dropped ${worstFailure.fmt(0)}% of test lookups. The other one works, but lookups that hit the bad one are delayed.",
                    changeDns,
                )
                else -> add(
                    "dns.good", Category.DNS, Severity.GOOD, "DNS is fast",
                    "Your DNS server (${system.address}) answers in ${ms(systemMedian)}.",
                )
            }
        }

        // ------------------------------------------------------------------ speed

        fun speed() {
            val result = speed ?: return
            if (result.downloadMbps == null && result.uploadMbps == null) {
                add(
                    "speed.failed", Category.SPEED, Severity.INFO, "Speed test could not run",
                    result.error ?: "The speed test server could not be reached.",
                    listOf("Check that the connection works at all, then run the speed test again."),
                )
                return
            }
            val wifiHealthy = wifiLooksHealthy()
            val where = when {
                !onWifi -> ""
                wifiHealthy -> " The Wi-Fi link looks healthy, so this is what the internet line itself is delivering to you right now."
                else -> " The Wi-Fi problems in this report are probably part of the reason, so fix those first and test again."
            }
            val planFixes = buildList {
                if (onWifi && wifiHealthy) {
                    add("Compare with the speed you pay for. If it is far below, test again with a computer cabled to the router and give both results to your provider.")
                    add("Restart the modem and router.")
                } else if (onWifi) {
                    add("Test again standing next to the router. If it is much faster there, the Wi-Fi coverage is the problem, not the line.")
                }
            }
            result.downloadMbps?.let { down ->
                when {
                    down < 5 -> add(
                        "speed.download.veryslow", Category.SPEED, Severity.CRITICAL, "Very slow download speed (${speedText(down)})",
                        "At ${speedText(down)} even HD video will buffer and pages load slowly.$where",
                        planFixes,
                    )
                    down < 25 -> add(
                        "speed.download.slow", Category.SPEED, Severity.WARNING, "Slow download speed (${speedText(down)})",
                        "${speedText(down)} is enough for one HD stream and browsing, but it will struggle with 4K video or several people online at once.$where",
                        planFixes,
                    )
                    else -> add(
                        "speed.download.good", Category.SPEED, Severity.GOOD, "Download speed: ${speedText(down)}",
                        if (down >= 100) "Plenty for several 4K streams and large downloads at the same time." else "Enough for 4K streaming and a few people online at once.",
                    )
                }
                val usual = baseline?.downloadMbps
                if (usual != null && usual >= 10 && down < usual * 0.6) {
                    add(
                        "speed.download.belowusual", Category.SPEED, Severity.WARNING, "Download is slower than usual on this network",
                        "Earlier tests here typically measured about ${speedText(usual)}; this one measured ${speedText(down)}. Something has changed: a busy network, a weaker Wi-Fi position, or a problem on the line.",
                        listOf("Compare the Wi-Fi signal and channel findings with where and when you normally test.", "If it stays low at different times of day, restart the modem and router, then contact the provider."),
                    )
                }
            }
            result.uploadMbps?.let { up ->
                when {
                    up < 1 -> add(
                        "speed.upload.veryslow", Category.SPEED, Severity.CRITICAL, "Very slow upload speed (${speedText(up)})",
                        "At ${speedText(up)} video calls will freeze and sending photos or backups will take a very long time, and they will slow everything else while they run.$where",
                        planFixes,
                    )
                    up < 4 -> add(
                        "speed.upload.slow", Category.SPEED, Severity.WARNING, "Slow upload speed (${speedText(up)})",
                        "${speedText(up)} is below what a group video call in HD needs (about 4 Mbps), and one cloud backup can saturate it.$where",
                        planFixes,
                    )
                    else -> add(
                        "speed.upload.good", Category.SPEED, Severity.GOOD, "Upload speed: ${speedText(up)}",
                        if (up >= 20) "Comfortable for HD video calls, cloud backups and sharing large files." else "Enough for HD video calls.",
                    )
                }
            }
            if (result.error != null) {
                add("speed.partial", Category.SPEED, Severity.INFO, "Speed test was incomplete", result.error)
            }
        }

        private fun wifiLooksHealthy(): Boolean {
            if (!onWifi) return false
            val signalOk = (rssi ?: -100) >= RSSI_GOOD
            val ping = gatewayPing?.takeIf { it.reachable }
            val hopOk = ping == null || ((ping.medianMs ?: 0.0) < 30 && ping.significantLossPercent < LOSS_NOTABLE)
            return signalOk && hopOk && !has("wifi.linkrate") && !has("wifi.standard.legacy") && !has("wifi.security.legacy")
        }

        // ------------------------------------------------------------------ bufferbloat

        fun bufferbloat() {
            val result = speed ?: return
            val idle = result.idleLatencyMs ?: return
            val added = result.bufferbloatMs ?: return
            val grade = BufferbloatGrade.of(added)
            val downAdded = result.downloadLoadedLatencyMs?.let { (it - idle).coerceAtLeast(0.0) }
            val upAdded = result.uploadLoadedLatencyMs?.let { (it - idle).coerceAtLeast(0.0) }
            val direction = when {
                upAdded != null && downAdded != null && upAdded >= downAdded * 1.5 -> "uploading"
                upAdded != null && downAdded != null && downAdded >= upAdded * 1.5 -> "downloading"
                else -> "downloading or uploading"
            }
            val detail = "Latency is ${ms(idle)} when the line is idle but rises by ${ms(added)} while $direction at full speed (grade ${grade.label}). " +
                "The modem or router is queueing too much data, so whenever someone streams, downloads or backs up, everyone else's calls, games and page loads lag."
            val fixes = listOf(
                "Turn on Smart Queue Management in the router: it may be called SQM, CAKE, fq_codel, Adaptive QoS or Smart QoS. Set its speed limits to about 90% of the download and upload speeds measured here.",
                "If the router has no such feature, it is the one upgrade that fixes this; many current routers and OpenWrt firmware include it.",
                "Until then, cap the upload rate of cloud backup and sync apps, and avoid large transfers during calls.",
            )
            when {
                added >= 200 -> add("bufferbloat.severe", Category.BUFFERBLOAT, Severity.CRITICAL, "Severe lag whenever the line is busy", detail, fixes)
                added >= 60 -> add("bufferbloat.moderate", Category.BUFFERBLOAT, Severity.WARNING, "Lag when the line is busy", detail, fixes)
                else -> add(
                    "bufferbloat.good", Category.BUFFERBLOAT, Severity.GOOD, "Stays responsive under load (grade ${grade.label})",
                    "Latency rises by only ${ms(added)} while the line is fully loaded, so heavy use by one device does not make others lag.",
                )
            }
        }

        // ------------------------------------------------------------------ devices

        fun devices() {
            if (!snapshot.localNetworkAccess && onWifi) {
                add(
                    "devices.nopermission", Category.DEVICES, Severity.INFO, "Home network checks were skipped",
                    "Android has not given this app access to devices on the local network, so the router could not be pinged or queried and other devices could not be counted.",
                    listOf("Allow the \"Nearby devices\" permission for this app in system settings, then scan again."),
                )
                return
            }
            val devices = snapshot.devices ?: return
            val others = devices.count { !it.isSelf && !it.isGateway }
            when {
                others >= 40 -> add(
                    "devices.many", Category.DEVICES, Severity.WARNING, "$others devices share this network",
                    "That is a lot for a single consumer router. Each device takes airtime even when it is only checking in, and many home routers become unstable past a few dozen clients.",
                    listOf(
                        "Connect stationary devices (TV, console, desktop, NAS) by cable.",
                        "Spread the load with a mesh system or additional wired access points.",
                        "Remove or switch off devices that no longer need to be online.",
                    ),
                )
                others >= 25 -> add(
                    "devices.several", Category.DEVICES, Severity.INFO, "$others devices share this network",
                    "Nothing is wrong with that in itself, but all of them share the same Wi-Fi airtime and internet line.",
                    listOf("If speed drops at busy times, connect stationary devices by cable and enable QoS on the router."),
                )
            }
        }

        // ------------------------------------------------------------------ verdict

        fun bottleneck(sorted: List<Finding>): Bottleneck {
            if (connection.transport == Transport.NONE) return Bottleneck.NO_CONNECTION
            fun any(severity: Severity, vararg prefixes: String) =
                sorted.any { f -> f.severity == severity && prefixes.any { f.id.startsWith(it) } }

            val wifi = arrayOf("wifi.signal.veryweak", "wifi.signal.weak", "wifi.signal.unstable", "wifi.roaming", "wifi.band.slow", "wifi.standard.legacy", "wifi.security.legacy", "wifi.width.24wide", "wifi.linkrate", "wifi.channel.crowded", "local.")
            val line = arrayOf("internet.", "speed.", "connection.nointernet", "router.wanlink", "router.background", "nat.double")
            val candidates = listOf(
                Bottleneck.WIFI to wifi,
                Bottleneck.INTERNET_LINE to line,
                Bottleneck.BUFFERBLOAT to arrayOf("bufferbloat."),
                Bottleneck.DNS to arrayOf("dns."),
            )
            // Slow speed with unhealthy Wi-Fi is blamed on the Wi-Fi, which is listed first.
            for (severity in listOf(Severity.CRITICAL, Severity.WARNING)) {
                for ((bottleneck, prefixes) in candidates) {
                    if (any(severity, *prefixes)) return bottleneck
                }
            }
            return Bottleneck.NONE
        }

        fun headline(bottleneck: Bottleneck, sorted: List<Finding>): Pair<String, String> {
            val problems = sorted.count { it.severity == Severity.CRITICAL || it.severity == Severity.WARNING }
            val top = sorted.firstOrNull { it.severity == Severity.CRITICAL || it.severity == Severity.WARNING }
            val more = if (problems > 1) " ${problems - 1} more thing${if (problems == 2) "" else "s"} to look at below." else ""
            return when (bottleneck) {
                Bottleneck.NO_CONNECTION -> "Not connected" to "Connect to Wi-Fi or mobile data and scan again."
                Bottleneck.WIFI -> "Your Wi-Fi is the weak point" to
                    "The biggest problem is inside your home, between this phone and the router: ${top?.title?.replaceFirstChar { it.lowercase() }}.$more"
                Bottleneck.INTERNET_LINE -> "The internet line is the weak point" to
                    (if (onWifi) "Your Wi-Fi link looks fine. " else "") +
                    "The biggest problem is past the router, on the line or at the provider: ${top?.title?.replaceFirstChar { it.lowercase() }}.$more"
                Bottleneck.BUFFERBLOAT -> "The connection lags when it is busy" to
                    "Latency shoots up whenever the line is in heavy use, so calls, games and page loads lag while anyone is downloading or uploading. A router setting fixes this.$more"
                Bottleneck.DNS -> "Slow name lookups are holding things back" to
                    "The DNS server this network uses is slow or unreliable, which delays the start of every page load.$more"
                Bottleneck.VPN -> "The VPN is the weak point" to "Pause the VPN and scan again."
                Bottleneck.NONE -> if (connection.isVpn) {
                    "No problems found (through the VPN)" to "Nothing is wrong on the path through your VPN. Pause it and scan again to check the line on its own."
                } else {
                    "No significant problems found" to "Everything that was tested looks healthy. If something still feels slow, it is likely the app or the service at the other end."
                }
            }
        }

        // ------------------------------------------------------------------ use cases

        fun useCases(): List<UseCaseVerdict> {
            val result = speed
            val down = result?.downloadMbps
            val up = result?.uploadMbps
            val latency = internetPing?.medianMs ?: result?.idleLatencyMs
            val jitter = internetPing?.jitterMs ?: result?.idleJitterMs
            val loss = internetPing?.significantLossPercent
            val added = result?.bufferbloatMs
            if (down == null && up == null && latency == null) return emptyList()
            val verdicts = ArrayList<UseCaseVerdict>()

            verdicts += when {
                down == null -> UseCaseVerdict("Web browsing", Rating.UNKNOWN, "Needs a speed test.")
                down >= 10 && (latency ?: 0.0) < 150 -> UseCaseVerdict("Web browsing", Rating.GREAT, "Pages load quickly.")
                down >= 3 -> UseCaseVerdict("Web browsing", Rating.OK, "Usable; heavy pages take a moment.")
                else -> UseCaseVerdict("Web browsing", Rating.POOR, "Too slow for comfortable browsing.")
            }

            // Netflix recommends 5 Mbps for 1080p and 15 Mbps for 4K per stream.
            verdicts += when {
                down == null -> UseCaseVerdict("Video streaming", Rating.UNKNOWN, "Needs a speed test.")
                down >= 15 -> {
                    val streams = (down / 15).toInt().coerceAtMost(20)
                    UseCaseVerdict("Video streaming", Rating.GREAT, if (streams >= 2) "Room for about $streams 4K streams at once." else "Enough for one 4K stream.")
                }
                down >= 5 -> UseCaseVerdict("Video streaming", Rating.OK, "HD works; 4K will buffer.")
                else -> UseCaseVerdict("Video streaming", Rating.POOR, "Expect buffering even in HD.")
            }

            // Zoom lists about 3.8 Mbps up and 3.0 Mbps down for 1080p group calls.
            verdicts += when {
                down == null || up == null -> UseCaseVerdict("Video calls", Rating.UNKNOWN, "Needs a speed test.")
                up < 1 || down < 1.5 || (loss ?: 0.0) >= LOSS_SEVERE || (latency ?: 0.0) >= 300 ->
                    UseCaseVerdict("Video calls", Rating.POOR, "Calls will freeze or drop.")
                up >= 3.8 && down >= 3 && (latency ?: 0.0) < 150 && (jitter ?: 0.0) < 30 && (loss ?: 0.0) < LOSS_NOTABLE && (added ?: 0.0) < 200 ->
                    UseCaseVerdict("Video calls", Rating.GREAT, "HD group calls should run smoothly.")
                else -> UseCaseVerdict(
                    "Video calls", Rating.OK,
                    when {
                        up < 3.8 -> "Works, but upload speed limits video quality."
                        (added ?: 0.0) >= 200 -> "Works until someone else uses the line heavily."
                        else -> "Works, with occasional glitches."
                    },
                )
            }

            verdicts += when {
                latency == null -> UseCaseVerdict("Online gaming", Rating.UNKNOWN, "Needs a latency test.")
                latency < 50 && (jitter ?: 0.0) < 15 && (loss ?: 0.0) < LOSS_NOTABLE && (added ?: 0.0) < 60 ->
                    UseCaseVerdict("Online gaming", Rating.GREAT, "Low, steady latency.")
                latency < 100 && (loss ?: 0.0) < LOSS_SEVERE -> UseCaseVerdict(
                    "Online gaming", Rating.OK,
                    if ((added ?: 0.0) >= 60) "Playable, but lags when others use the line." else "Playable; fast-paced games will feel slightly delayed.",
                )
                else -> UseCaseVerdict("Online gaming", Rating.POOR, "Latency or packet loss is too high for real-time games.")
            }
            return verdicts
        }
    }
}
