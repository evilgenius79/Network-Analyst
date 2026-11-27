package com.networkanalyst.core.connect

import com.networkanalyst.core.model.AccessPoint
import com.networkanalyst.core.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetPickerTest {
    private fun ap(ssid: String, bssid: String, rssi: Int, freq: Int, security: WifiSecurity = WifiSecurity.WPA2, connected: Boolean = false) =
        AccessPoint(ssid, bssid, rssi, freq, security = security, isConnected = connected)

    @Test
    fun `groups a mesh into one network with its radios strongest first`() {
        val networks = TargetPicker.networks(
            listOf(
                ap("Neighbour", "10:00:00:00:00:01", -40, 2412),
                ap("Home", "AA:BB:CC:00:00:01", -62, 2437),
                ap("Home", "aa:bb:cc:00:00:02", -55, 5180, connected = true),
                ap("Home", "aa:bb:cc:00:00:03", -78, 5745),
                ap("", "aa:bb:cc:00:00:09", -50, 5180),
            ),
        )
        // Connected network first even though the neighbour is stronger; the hidden one is dropped.
        assertEquals(listOf("Home", "Neighbour"), networks.map { it.ssid })
        val home = networks.first()
        assertTrue(home.connected)
        assertEquals(WifiSecurity.WPA2, home.security)
        assertEquals(listOf("aa:bb:cc:00:00:02", "aa:bb:cc:00:00:01", "aa:bb:cc:00:00:03"), home.targets.map { it.bssid })
        assertEquals(listOf(-55, -62, -78), home.targets.map { it.rssiDbm })
        assertEquals("5 GHz ch 36", home.targets.first().radioLabel)
        assertFalse(networks[1].connected)
    }

    @Test
    fun `connected network is recognised by name when the scan does not mark it`() {
        val networks = TargetPicker.networks(listOf(ap("Cafe", "10:00:00:00:00:01", -40, 2412), ap("Home", "aa:bb:cc:00:00:01", -70, 2437)), connectedSsid = "Home")
        assertEquals("Home", networks.first().ssid)
        assertTrue(networks.first().connected)
    }

    @Test
    fun `a radio listed twice is kept once with its strongest reading`() {
        val home = TargetPicker.networks(listOf(ap("Home", "aa:bb:cc:00:00:01", -70, 2437), ap("Home", "AA:BB:CC:00:00:01", -64, 2437))).single()
        assertEquals(1, home.targets.size)
        assertEquals(-64, home.targets.single().rssiDbm)
    }

    @Test
    fun `security follows the majority of radios and decides what can be tested`() {
        val mixed = TargetPicker.networks(
            listOf(
                ap("Home", "aa:bb:cc:00:00:01", -40, 2437, WifiSecurity.WPA2_WPA3),
                ap("Home", "aa:bb:cc:00:00:02", -50, 5180, WifiSecurity.WPA2),
                ap("Home", "aa:bb:cc:00:00:03", -60, 5745, WifiSecurity.WPA2),
            ),
        ).single()
        assertEquals(WifiSecurity.WPA2, mixed.security)
        assertTrue(mixed.needsPassword)
        assertTrue(mixed.testable)

        val open = TargetPicker.networks(listOf(ap("Guest", "aa:bb:cc:00:00:01", -40, 2437, WifiSecurity.OPEN))).single()
        assertFalse(open.needsPassword)
        assertTrue(open.testable)

        val work = TargetPicker.networks(listOf(ap("Office", "aa:bb:cc:00:00:01", -40, 2437, WifiSecurity.ENTERPRISE))).single()
        assertFalse(work.testable)
    }

    @Test
    fun `preselects the strongest radio of each band, then the next strongest`() {
        val targets = listOf(
            TestTarget("Home", "01", 5180, -45),
            TestTarget("Home", "02", 5745, -50),
            TestTarget("Home", "03", 5500, -52),
            TestTarget("Home", "04", 5200, -56),
            TestTarget("Home", "05", 5220, -58),
            TestTarget("Home", "06", 5240, -60),
            TestTarget("Home", "07", 5260, -61),
            // Weaker than six 5 GHz radios, but the only 2.4 GHz one: it must still be included.
            TestTarget("Home", "08", 2437, -70),
            TestTarget("Home", "09", 2412, -88),
        )
        assertEquals(setOf("01", "08", "02", "03", "04", "05"), TargetPicker.defaultSelection(targets))
    }

    @Test
    fun `weak radios are left out unless nothing better is in range`() {
        val targets = listOf(TestTarget("Home", "01", 5180, -60), TestTarget("Home", "02", 2437, -81), TestTarget("Home", "03", 5745, -90))
        assertEquals(setOf("01"), TargetPicker.defaultSelection(targets))

        val allWeak = listOf(TestTarget("Home", "02", 2437, -84), TestTarget("Home", "03", 5745, -90))
        assertEquals(setOf("02"), TargetPicker.defaultSelection(allWeak))
        assertEquals(emptySet<String>(), TargetPicker.defaultSelection(emptyList()))
    }
}
