package com.networkanalyst.core.model

import kotlinx.serialization.Serializable

enum class WifiBand(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
    GHZ_60("60 GHz"),
    UNKNOWN("Unknown band"),
}

/** Wi-Fi generation. [generation] is the Wi-Fi Alliance number, 0 when there is none. */
enum class WifiStandard(val label: String, val generation: Int) {
    LEGACY("802.11a/b/g", 0),
    N("Wi-Fi 4 (802.11n)", 4),
    AC("Wi-Fi 5 (802.11ac)", 5),
    AX("Wi-Fi 6 (802.11ax)", 6),
    AD("WiGig (802.11ad)", 0),
    BE("Wi-Fi 7 (802.11be)", 7),
    UNKNOWN("Unknown", 0),
}

enum class WifiSecurity(val label: String) {
    OPEN("Open (no password)"),
    OWE("Enhanced Open (OWE)"),
    WEP("WEP"),
    WPA("WPA (TKIP)"),
    WPA2("WPA2"),
    WPA2_WPA3("WPA2/WPA3"),
    WPA3("WPA3"),
    ENTERPRISE("Enterprise (802.1X)"),
    UNKNOWN("Unknown"),
}

/** One access point radio as seen in a Wi-Fi scan. */
@Serializable
data class AccessPoint(
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    /** Centre of the primary 20 MHz channel, in MHz. */
    val frequencyMhz: Int,
    /** Total channel width in MHz: 20, 40, 80, 160 or 320. */
    val channelWidthMhz: Int = 20,
    /** Centre of the whole (bonded) channel in MHz, when the scan reports it. */
    val centerFreq0Mhz: Int? = null,
    val centerFreq1Mhz: Int? = null,
    val standard: WifiStandard = WifiStandard.UNKNOWN,
    val security: WifiSecurity = WifiSecurity.UNKNOWN,
    /** True when this is the radio the phone is currently connected to. */
    val isConnected: Boolean = false,
    /** Android's raw description of security and features, e.g. "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]". */
    val capabilities: String? = null,
    /** True when the radio is one link of a Wi-Fi 7 multi-link access point. */
    val multiLink: Boolean = false,
)

/** The phone's current Wi-Fi association. */
@Serializable
data class WifiLink(
    val ssid: String? = null,
    val bssid: String? = null,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    /** Negotiated transmit rate (phone to router), Mbps. */
    val txLinkSpeedMbps: Int? = null,
    /** Negotiated receive rate (router to phone), Mbps. */
    val rxLinkSpeedMbps: Int? = null,
    /** Highest rates this phone and this router could negotiate in ideal conditions. */
    val maxTxLinkSpeedMbps: Int? = null,
    val maxRxLinkSpeedMbps: Int? = null,
    val standard: WifiStandard = WifiStandard.UNKNOWN,
    val security: WifiSecurity = WifiSecurity.UNKNOWN,
    /** Channel width, filled in from the matching scan entry when one is available. */
    val channelWidthMhz: Int? = null,
    /** Signal readings taken over a few seconds, used to judge stability. */
    val rssiSamplesDbm: List<Int> = emptyList(),
)

/** What the phone's own radio supports; lets the analysis tell router limits from phone limits. */
@Serializable
data class WifiCapabilities(
    val supports5Ghz: Boolean? = null,
    val supports6Ghz: Boolean? = null,
    val supportsWifi5: Boolean? = null,
    val supportsWifi6: Boolean? = null,
    val supportsWifi7: Boolean? = null,
)

/** Why a Wi-Fi scan could not be used, so the UI can say what to fix. */
enum class ScanUnavailableReason {
    NOT_ON_WIFI,
    LOCATION_PERMISSION_MISSING,
    LOCATION_SERVICES_OFF,
    THROTTLED,
    FAILED,
}

@Serializable
data class WifiScan(
    val accessPoints: List<AccessPoint> = emptyList(),
    /** True when the list came from a scan requested just now rather than cached results. */
    val fresh: Boolean = false,
    val unavailableReason: ScanUnavailableReason? = null,
)
