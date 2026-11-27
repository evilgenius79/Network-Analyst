package com.networkanalyst.core.connect

import com.networkanalyst.core.model.AccessPoint
import com.networkanalyst.core.model.WifiSecurity

/** A Wi-Fi network in range, with the access point radios of it that can be tested. */
data class TestNetwork(
    val ssid: String,
    val security: WifiSecurity,
    /** Strongest signal first. */
    val targets: List<TestTarget>,
    /** True when the phone is on this network right now. */
    val connected: Boolean,
) {
    /** Open networks are joined without a password. */
    val needsPassword: Boolean get() = security != WifiSecurity.OPEN && security != WifiSecurity.OWE

    /** Networks that log in with a user name (802.1X) or use WEP cannot be requested by an app. */
    val testable: Boolean get() = security != WifiSecurity.ENTERPRISE && security != WifiSecurity.WEP
}

/** Turns a Wi-Fi scan into the networks and access point radios the connection test can use. */
object TargetPicker {
    /** Radios ticked to begin with; more makes the test long. */
    const val MAX_PRESELECTED = 6

    /** Below this a failure to connect says more about distance than about the network. */
    const val USABLE_RSSI_DBM = -80

    /**
     * Groups the scan by network name. Hidden networks (no name) are left out because a
     * connection cannot be requested without one. The connected network comes first, then
     * the rest by strongest signal.
     */
    fun networks(accessPoints: List<AccessPoint>, connectedSsid: String? = null): List<TestNetwork> =
        accessPoints
            .filter { it.ssid.isNotBlank() && it.bssid.isNotBlank() }
            .groupBy { it.ssid }
            .map { (ssid, radios) ->
                // The same radio can appear twice in a scan; keep its strongest reading.
                val unique = radios.groupBy { it.bssid.lowercase() }.map { (_, same) -> same.maxBy { it.rssiDbm } }
                    .sortedWith(compareByDescending<AccessPoint> { it.rssiDbm }.thenBy { it.bssid.lowercase() })
                // Radios of one network normally agree; if not, go with the majority, then the strongest.
                val security = unique.groupingBy { it.security }.eachCount().let { counts ->
                    val most = counts.values.max()
                    unique.first { counts.getValue(it.security) == most }.security
                }
                TestNetwork(
                    ssid = ssid,
                    security = security,
                    targets = unique.map { TestTarget(ssid, it.bssid.lowercase(), it.frequencyMhz, it.rssiDbm) },
                    connected = ssid == connectedSsid || radios.any { it.isConnected },
                )
            }
            .sortedWith(compareByDescending<TestNetwork> { it.connected }.thenByDescending { it.targets.first().rssiDbm }.thenBy { it.ssid })

    /**
     * Which radios to tick to begin with: the strongest on each band first, so bands can be
     * compared, then the next strongest up to [MAX_PRESELECTED]. Radios too weak to judge are
     * left unticked, unless nothing better is in range.
     */
    fun defaultSelection(targets: List<TestTarget>): Set<String> {
        val byStrength = targets.sortedByDescending { it.rssiDbm }
        val usable = byStrength.filter { it.rssiDbm >= USABLE_RSSI_DBM }
        if (usable.isEmpty()) return setOfNotNull(byStrength.firstOrNull()?.bssid)
        val picked = LinkedHashSet<String>()
        usable.groupBy { it.band }.values.forEach { picked += it.first().bssid }
        for (target in usable) {
            if (picked.size >= MAX_PRESELECTED) break
            picked += target.bssid
        }
        return picked
    }
}
