package com.networkanalyst.app.data

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import com.networkanalyst.core.model.WifiSecurity
import com.networkanalyst.core.model.WifiStandard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiCollectorTest {
    @Test
    fun `network names are unquoted and placeholders dropped`() {
        assertEquals("Home", WifiCollector.cleanSsid("\"Home\""))
        assertEquals("My \"quoted\" net", WifiCollector.cleanSsid("\"My \"quoted\" net\""))
        // Names that are not valid text arrive as bare hex, without quotes.
        assertEquals("e4b8ade69687", WifiCollector.cleanSsid("e4b8ade69687"))
        assertNull(WifiCollector.cleanSsid("<unknown ssid>"))
        assertNull(WifiCollector.cleanSsid("\"\""))
        assertNull(WifiCollector.cleanSsid(""))
        assertNull(WifiCollector.cleanSsid(null))
    }

    @Test
    fun `redacted access point address is treated as unknown`() {
        assertEquals("a4:11:22:33:44:50", WifiCollector.cleanBssid("a4:11:22:33:44:50"))
        assertNull(WifiCollector.cleanBssid("02:00:00:00:00:00"))
        assertNull(WifiCollector.cleanBssid(null))
    }

    @Test
    fun `platform constants map to the app's types`() {
        assertEquals(WifiStandard.LEGACY, WifiCollector.mapStandard(ScanResult.WIFI_STANDARD_LEGACY))
        assertEquals(WifiStandard.N, WifiCollector.mapStandard(ScanResult.WIFI_STANDARD_11N))
        assertEquals(WifiStandard.AC, WifiCollector.mapStandard(ScanResult.WIFI_STANDARD_11AC))
        assertEquals(WifiStandard.AX, WifiCollector.mapStandard(ScanResult.WIFI_STANDARD_11AX))
        assertEquals(WifiStandard.BE, WifiCollector.mapStandard(ScanResult.WIFI_STANDARD_11BE))
        assertEquals(WifiStandard.UNKNOWN, WifiCollector.mapStandard(ScanResult.WIFI_STANDARD_UNKNOWN))
        assertEquals(WifiSecurity.OPEN, WifiCollector.mapSecurityType(WifiInfo.SECURITY_TYPE_OPEN))
        assertEquals(WifiSecurity.WEP, WifiCollector.mapSecurityType(WifiInfo.SECURITY_TYPE_WEP))
        assertEquals(WifiSecurity.WPA2, WifiCollector.mapSecurityType(WifiInfo.SECURITY_TYPE_PSK))
        assertEquals(WifiSecurity.WPA3, WifiCollector.mapSecurityType(WifiInfo.SECURITY_TYPE_SAE))
        assertEquals(WifiSecurity.ENTERPRISE, WifiCollector.mapSecurityType(WifiInfo.SECURITY_TYPE_EAP))
        assertEquals(WifiSecurity.UNKNOWN, WifiCollector.mapSecurityType(WifiInfo.SECURITY_TYPE_UNKNOWN))
    }
}
