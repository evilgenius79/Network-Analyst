package com.networkanalyst.app.ui

import android.Manifest
import android.app.Application
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.networkanalyst.core.connect.WifiEventKind
import com.networkanalyst.core.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Connect tab's logic end to end inside a simulated phone: a Wi-Fi scan goes in, and the
 * networks, the ticked access points and the conditions for starting come out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val wifiManager = app.getSystemService(WifiManager::class.java)
    private val connectivityManager = app.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    private fun scanResult(ssid: String, bssid: String, level: Int, frequency: Int, capabilities: String = "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]") =
        ScanResult().apply {
            SSID = ssid
            BSSID = bssid
            this.level = level
            this.frequency = frequency
            this.capabilities = capabilities
        }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        shadowOf(wifiManager).setScanResults(
            listOf(
                scanResult("Neighbour", "10:00:00:00:00:01", -41, 2412),
                scanResult("Home", "a4:11:22:33:44:50", -52, 2437),
                scanResult("Home", "a4:11:22:33:44:51", -58, 5180),
                scanResult("Home", "a4:11:22:33:55:61", -86, 5745),
                scanResult("Cafe", "10:00:00:00:00:09", -70, 2462, "[ESS]"),
            ),
        )
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for: $what")
            Thread.sleep(10)
        }
    }

    private fun viewModel(): MainViewModel = MainViewModel(app).also { vm ->
        awaitUntil("scan to reach the Connect tab") { vm.connect.value.networks.isNotEmpty() }
    }

    @Test
    fun `scan results become networks with the usable access points ticked`() {
        val state = viewModel().connect.value
        // Strongest network first when the phone is not connected to any of them.
        assertEquals(listOf("Neighbour", "Home", "Cafe"), state.networks.map { it.ssid })
        assertEquals("Neighbour", state.ssid)
        assertEquals(WifiSecurity.WPA2, state.networks[1].security)
        assertEquals(WifiSecurity.OPEN, state.networks[2].security)
        assertEquals(setOf("10:00:00:00:00:01"), state.selected)
        assertTrue(state.supported)
        assertFalse("a password is needed first", state.canStart)
    }

    @Test
    fun `choosing a network, access points and a password leads to being able to start`() {
        val vm = viewModel()
        vm.selectConnectNetwork("Home")
        var state = vm.connect.value
        assertEquals("Home", state.ssid)
        // Both strong radios are ticked; the one at -86 dBm is too weak to judge and is not.
        assertEquals(setOf("a4:11:22:33:44:50", "a4:11:22:33:44:51"), state.selected)
        assertEquals(6, state.requestCount)
        assertFalse(state.canStart)

        vm.setConnectPassword("short")
        assertEquals("Wi-Fi passwords are 8 to 63 characters.", vm.connect.value.passwordProblem)
        vm.setConnectPassword("café au lait")
        assertFalse("Android only takes plain ASCII passwords in a request", vm.connect.value.canStart)
        vm.setConnectPassword("correct horse")
        assertTrue(vm.connect.value.canStart)

        vm.toggleConnectTarget("a4:11:22:33:55:61")
        vm.toggleConnectTarget("a4:11:22:33:44:50")
        vm.setConnectTrials(5)
        state = vm.connect.value
        assertEquals(setOf("a4:11:22:33:44:51", "a4:11:22:33:55:61"), state.selected)
        assertEquals(10, state.requestCount)

        // A fresh scan must not undo what the user ticked.
        vm.rescanWifi()
        awaitUntil("rescan to finish") { !vm.wifiState.value.scanning }
        assertEquals(setOf("a4:11:22:33:44:51", "a4:11:22:33:55:61"), vm.connect.value.selected)
        assertEquals("correct horse", vm.connect.value.password)

        vm.toggleConnectTarget("a4:11:22:33:44:51")
        vm.toggleConnectTarget("a4:11:22:33:55:61")
        assertFalse("nothing ticked", vm.connect.value.canStart)

        // Another network has another password.
        vm.selectConnectNetwork("Cafe")
        state = vm.connect.value
        assertEquals("", state.password)
        assertTrue("an open network needs no password", state.canStart)
    }

    @Test
    fun `a test that cannot start says why and one that is stopped cleans up`() {
        val vm = viewModel()
        vm.selectConnectNetwork("Home")
        vm.setConnectPassword("correct horse")

        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = false
        vm.startConnectTest()
        awaitUntil("refusal") { vm.connect.value.error != null }
        assertEquals("Turn Wi-Fi on first.", vm.connect.value.error)
        assertFalse(vm.connect.value.running)

        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = true
        val before = shadowOf(connectivityManager).networkCallbacks.toSet()
        vm.startConnectTest()
        awaitUntil("request to be made") { runCatching { shadowOf(connectivityManager).networkCallbacks.any { it !in before } }.getOrDefault(false) }
        assertTrue(vm.connect.value.running)
        assertNull(vm.connect.value.error)
        assertTrue("other tests are held off meanwhile", vm.isBusy)

        vm.cancelConnectTest()
        awaitUntil("test to stop") { !vm.connect.value.running && !vm.isBusy }
        assertNull("nothing was learned, so there is no report", vm.connect.value.report)
        assertEquals(before, shadowOf(connectivityManager).networkCallbacks.toSet())

        // The watch log shows the test window, and the share text can be built from it.
        awaitUntil("watch state") { vm.watch.value.recent.any { it.kind == WifiEventKind.TEST_END } }
        assertTrue(vm.connectReportText().contains("Connection test finished"))
        vm.clearWatchLog()
        awaitUntil("log to clear") { vm.watch.value.total == 0 }
    }

    @Test
    fun `the background switch starts and stops the watch service`() {
        val vm = viewModel()
        vm.setBackgroundWatch(true)
        assertEquals("com.networkanalyst.app.data.WatchService", shadowOf(app).nextStartedService.component?.className)
        vm.setBackgroundWatch(false)
        assertEquals("com.networkanalyst.app.data.WatchService", shadowOf(app).nextStoppedService.component?.className)
    }
}
