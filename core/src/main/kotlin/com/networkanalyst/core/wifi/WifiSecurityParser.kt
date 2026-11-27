package com.networkanalyst.core.wifi

import com.networkanalyst.core.model.WifiSecurity

/**
 * Reads the security of an access point from the capabilities string Android attaches to
 * scan results, for example "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]".
 */
object WifiSecurityParser {
    fun fromCapabilities(capabilities: String?): WifiSecurity {
        if (capabilities == null) return WifiSecurity.UNKNOWN
        val caps = capabilities.uppercase()
        // Each bracketed token describes one security suite, e.g. "WPA2-PSK-TKIP+CCMP".
        val suites = Regex("\\[([^\\]]+)]").findAll(caps).map { it.groupValues[1] }
            .filter { it.startsWith("WPA") || it.startsWith("RSN") || it.startsWith("WEP") || it.startsWith("OWE") }
            .toList()
        if (suites.any { it.startsWith("WEP") }) return WifiSecurity.WEP
        if (suites.isEmpty()) return WifiSecurity.OPEN

        val keyed = suites.filter { it.startsWith("WPA") || it.startsWith("RSN") }
        val hasEap = keyed.any { "EAP" in it }
        val hasSae = keyed.any { "SAE" in it }
        val hasPsk = keyed.any { "PSK" in it }
        val hasOwe = suites.any { "OWE" in it }
        // AES (CCMP/GCMP) on any suite means fast rates are allowed; TKIP alone does not.
        val hasAes = keyed.any { "CCMP" in it || "GCMP" in it }
        val hasModernSuite = keyed.any { it.startsWith("WPA2") || it.startsWith("RSN") || it.startsWith("WPA3") }

        return when {
            hasEap -> WifiSecurity.ENTERPRISE
            hasSae && hasPsk -> WifiSecurity.WPA2_WPA3
            hasSae -> WifiSecurity.WPA3
            hasPsk && (!hasModernSuite || !hasAes) -> WifiSecurity.WPA
            hasPsk -> WifiSecurity.WPA2
            hasOwe -> WifiSecurity.OWE
            else -> WifiSecurity.UNKNOWN
        }
    }
}
