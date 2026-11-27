package com.networkanalyst.app.data

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.networkanalyst.core.model.AccessPoint
import com.networkanalyst.core.model.ScanUnavailableReason
import com.networkanalyst.core.model.WifiCapabilities
import com.networkanalyst.core.model.WifiLink
import com.networkanalyst.core.model.WifiScan
import com.networkanalyst.core.model.WifiSecurity
import com.networkanalyst.core.model.WifiStandard
import com.networkanalyst.core.wifi.WifiSecurityParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Reads the current Wi-Fi association and the list of networks in range. */
class WifiCollector(context: Context, private val connectivity: ConnectivityCollector) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager?.isLocationEnabled == true
        } else {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        }
    } catch (_: RuntimeException) {
        false
    }

    /**
     * The live Wi-Fi association. `WifiManager.getConnectionInfo()` is deprecated but is the
     * only call that returns the signal strength as last measured (it is refreshed every few
     * seconds); the copy attached to network capabilities can be older.
     */
    @Suppress("DEPRECATION")
    private fun liveInfo(): WifiInfo? = try {
        wifiManager?.connectionInfo
    } catch (_: RuntimeException) {
        null
    }

    /** Current link, or null when the phone is not associated to an access point. */
    fun currentLink(scan: List<AccessPoint> = emptyList(), rssiSamples: List<Int> = emptyList()): WifiLink? {
        if (connectivity.wifiNetwork() == null) return null
        val live = liveInfo()
        val fromCapabilities = connectivity.wifiInfoFromCapabilities()
        val info = live?.takeIf { it.frequency > 0 } ?: fromCapabilities ?: return null

        // Name and access point address are hidden without location permission; whichever
        // source has them wins.
        val ssid = cleanSsid(live?.ssid) ?: cleanSsid(fromCapabilities?.ssid)
        val bssid = cleanBssid(live?.bssid) ?: cleanBssid(fromCapabilities?.bssid)
        val matching = bssid?.let { b -> scan.firstOrNull { it.bssid.equals(b, ignoreCase = true) } }

        val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) mapStandard(info.wifiStandard) else matching?.standard ?: WifiStandard.UNKNOWN
        val security = matching?.security?.takeIf { it != WifiSecurity.UNKNOWN }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) mapSecurityType(info.currentSecurityType) else WifiSecurity.UNKNOWN

        return WifiLink(
            ssid = ssid,
            bssid = bssid,
            rssiDbm = info.rssi,
            frequencyMhz = info.frequency,
            txLinkSpeedMbps = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.txLinkSpeedMbps else info.linkSpeed).takeIf { it > 0 },
            rxLinkSpeedMbps = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.rxLinkSpeedMbps else info.linkSpeed).takeIf { it > 0 },
            maxTxLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.maxSupportedTxLinkSpeedMbps.takeIf { it > 0 } else null,
            maxRxLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.maxSupportedRxLinkSpeedMbps.takeIf { it > 0 } else null,
            standard = standard,
            security = security,
            channelWidthMhz = matching?.channelWidthMhz,
            rssiSamplesDbm = rssiSamples,
        )
    }

    /** Signal strength right now in dBm, or null when not on Wi-Fi. */
    fun currentRssi(): Int? {
        if (connectivity.wifiNetwork() == null) return null
        // -127 is the framework's "no reading" value.
        return liveInfo()?.rssi?.takeIf { it > -127 && it < 0 }
    }

    /** Reads the signal [count] times, [intervalMs] apart. */
    suspend fun sampleRssi(count: Int, intervalMs: Long): List<Int> {
        val samples = ArrayList<Int>(count)
        repeat(count) { index ->
            currentRssi()?.let(samples::add)
            if (index < count - 1) delay(intervalMs)
        }
        return samples
    }

    fun capabilities(): WifiCapabilities {
        val manager = wifiManager ?: return WifiCapabilities()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WifiCapabilities(
                    supports5Ghz = manager.is5GHzBandSupported,
                    supports6Ghz = manager.is6GHzBandSupported,
                    supportsWifi5 = manager.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AC),
                    supportsWifi6 = manager.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX),
                    supportsWifi7 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) manager.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE) else null,
                )
            } else {
                WifiCapabilities(supports5Ghz = manager.is5GHzBandSupported)
            }
        } catch (_: RuntimeException) {
            WifiCapabilities()
        }
    }

    /**
     * Lists the access points in range. Requests a fresh scan and waits for it; if Android
     * refuses (apps may scan four times in two minutes) the most recent results are used.
     */
    suspend fun scan(timeoutMs: Long = 10_000): WifiScan {
        val manager = wifiManager ?: return WifiScan(unavailableReason = ScanUnavailableReason.FAILED)
        if (!manager.isWifiEnabled) return WifiScan(unavailableReason = ScanUnavailableReason.NOT_ON_WIFI)
        if (!hasLocationPermission()) return WifiScan(unavailableReason = ScanUnavailableReason.LOCATION_PERMISSION_MISSING)
        if (!isLocationEnabled()) return WifiScan(unavailableReason = ScanUnavailableReason.LOCATION_SERVICES_OFF)

        val finished = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                finished.complete(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
            }
        }
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            @Suppress("DEPRECATION")
            val accepted = try {
                manager.startScan()
            } catch (_: RuntimeException) {
                false
            }
            val fresh = accepted && withTimeoutOrNull(timeoutMs) { finished.await() } == true
            val results = try {
                manager.scanResults.orEmpty()
            } catch (_: SecurityException) {
                return WifiScan(unavailableReason = ScanUnavailableReason.LOCATION_PERMISSION_MISSING)
            }
            val connectedBssid = cleanBssid(liveInfo()?.bssid) ?: cleanBssid(connectivity.wifiInfoFromCapabilities()?.bssid)
            val accessPoints = results.mapNotNull { toAccessPoint(it, connectedBssid) }
            val reason = when {
                accessPoints.isNotEmpty() -> null
                !accepted -> ScanUnavailableReason.THROTTLED
                else -> ScanUnavailableReason.FAILED
            }
            return WifiScan(accessPoints, fresh, reason)
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    /** Networks from the last scan Android has, without asking for a new one. */
    fun cachedScan(): WifiScan {
        val manager = wifiManager ?: return WifiScan(unavailableReason = ScanUnavailableReason.FAILED)
        if (!hasLocationPermission()) return WifiScan(unavailableReason = ScanUnavailableReason.LOCATION_PERMISSION_MISSING)
        if (!isLocationEnabled()) return WifiScan(unavailableReason = ScanUnavailableReason.LOCATION_SERVICES_OFF)
        return try {
            val connectedBssid = cleanBssid(liveInfo()?.bssid) ?: cleanBssid(connectivity.wifiInfoFromCapabilities()?.bssid)
            WifiScan(manager.scanResults.orEmpty().mapNotNull { toAccessPoint(it, connectedBssid) }, fresh = false)
        } catch (_: SecurityException) {
            // Permission was withdrawn between the check above and the call.
            WifiScan(unavailableReason = ScanUnavailableReason.LOCATION_PERMISSION_MISSING)
        } catch (_: RuntimeException) {
            WifiScan(unavailableReason = ScanUnavailableReason.FAILED)
        }
    }

    private fun toAccessPoint(result: ScanResult, connectedBssid: String?): AccessPoint? {
        val bssid = result.BSSID ?: return null
        if (result.frequency <= 0) return null
        @Suppress("DEPRECATION")
        val ssid = result.SSID.orEmpty()
        return AccessPoint(
            ssid = ssid,
            bssid = bssid,
            rssiDbm = result.level,
            frequencyMhz = result.frequency,
            channelWidthMhz = when (result.channelWidth) {
                ScanResult.CHANNEL_WIDTH_20MHZ -> 20
                ScanResult.CHANNEL_WIDTH_40MHZ -> 40
                ScanResult.CHANNEL_WIDTH_80MHZ -> 80
                ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
                ScanResult.CHANNEL_WIDTH_320MHZ -> 320
                else -> 20
            },
            centerFreq0Mhz = result.centerFreq0.takeIf { it > 0 },
            centerFreq1Mhz = result.centerFreq1.takeIf { it > 0 },
            standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) mapStandard(result.wifiStandard) else WifiStandard.UNKNOWN,
            security = WifiSecurityParser.fromCapabilities(result.capabilities),
            isConnected = connectedBssid != null && bssid.equals(connectedBssid, ignoreCase = true),
        )
    }

    companion object {
        /** Address Android substitutes when the real one is withheld for privacy. */
        private const val REDACTED_BSSID = "02:00:00:00:00:00"
        private const val UNKNOWN_SSID = "<unknown ssid>"

        /** Android reports network names wrapped in double quotes. */
        fun cleanSsid(raw: String?): String? {
            if (raw.isNullOrEmpty() || raw == UNKNOWN_SSID) return null
            val unquoted = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
            return unquoted.takeIf { it.isNotEmpty() }
        }

        fun cleanBssid(raw: String?): String? = raw?.takeIf { it.isNotEmpty() && it != REDACTED_BSSID }

        fun mapStandard(value: Int): WifiStandard = when (value) {
            ScanResult.WIFI_STANDARD_LEGACY -> WifiStandard.LEGACY
            ScanResult.WIFI_STANDARD_11N -> WifiStandard.N
            ScanResult.WIFI_STANDARD_11AC -> WifiStandard.AC
            ScanResult.WIFI_STANDARD_11AX -> WifiStandard.AX
            ScanResult.WIFI_STANDARD_11AD -> WifiStandard.AD
            ScanResult.WIFI_STANDARD_11BE -> WifiStandard.BE
            else -> WifiStandard.UNKNOWN
        }

        /** Coarse mapping used only when the access point was not found in a scan. */
        fun mapSecurityType(value: Int): WifiSecurity = when (value) {
            WifiInfo.SECURITY_TYPE_OPEN -> WifiSecurity.OPEN
            WifiInfo.SECURITY_TYPE_OWE -> WifiSecurity.OWE
            WifiInfo.SECURITY_TYPE_WEP -> WifiSecurity.WEP
            WifiInfo.SECURITY_TYPE_PSK -> WifiSecurity.WPA2
            WifiInfo.SECURITY_TYPE_SAE -> WifiSecurity.WPA3
            WifiInfo.SECURITY_TYPE_EAP, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> WifiSecurity.ENTERPRISE
            else -> WifiSecurity.UNKNOWN
        }
    }
}
