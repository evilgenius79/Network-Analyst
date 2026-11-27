package com.networkanalyst.core.wifi

import com.networkanalyst.core.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiSecurityParserTest {
    @Test
    fun `capability strings map to security types`() {
        val cases = mapOf(
            "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]" to WifiSecurity.WPA2,
            "[RSN-PSK-CCMP][ESS]" to WifiSecurity.WPA2,
            "[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][ESS]" to WifiSecurity.WPA2,
            "[WPA-PSK-TKIP][ESS]" to WifiSecurity.WPA,
            // WPA2 restricted to TKIP is just as slow as WPA.
            "[WPA2-PSK-TKIP][ESS]" to WifiSecurity.WPA,
            "[WPA-PSK-CCMP][ESS]" to WifiSecurity.WPA,
            "[RSN-SAE-CCMP][ESS]" to WifiSecurity.WPA3,
            "[RSN-PSK+SAE-CCMP][ESS][MFPC]" to WifiSecurity.WPA2_WPA3,
            "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]" to WifiSecurity.WPA2_WPA3,
            "[WEP][ESS]" to WifiSecurity.WEP,
            "[ESS]" to WifiSecurity.OPEN,
            "" to WifiSecurity.OPEN,
            "[ESS][WPS]" to WifiSecurity.OPEN,
            "[RSN-OWE-CCMP][ESS]" to WifiSecurity.OWE,
            "[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]" to WifiSecurity.ENTERPRISE,
            "[RSN-EAP_SUITE_B_192-GCMP-256][ESS]" to WifiSecurity.ENTERPRISE,
            // Recent Android versions spell out the cipher size.
            "[WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS][WPS]" to WifiSecurity.WPA2,
            "[WPA2-PSK-CCMP-128][RSN-PSK+SAE-CCMP-128][ESS][MFPC]" to WifiSecurity.WPA2_WPA3,
            "[RSN-SAE+FT/SAE-CCMP-128][ESS][MFPR][MFPC]" to WifiSecurity.WPA3,
            "[WPA-PSK-TKIP][WPA2-PSK-TKIP+CCMP-128][RSN-PSK-TKIP+CCMP-128][ESS]" to WifiSecurity.WPA2,
            "[RSN-OWE_TRANSITION-CCMP-128][ESS]" to WifiSecurity.OWE,
            "[WPA2-FT/PSK-CCMP-128][RSN-PSK+FT/PSK-CCMP-128][ESS]" to WifiSecurity.WPA2,
        )
        for ((capabilities, expected) in cases) {
            assertEquals(capabilities, expected, WifiSecurityParser.fromCapabilities(capabilities))
        }
        assertEquals(WifiSecurity.UNKNOWN, WifiSecurityParser.fromCapabilities(null))
    }
}
