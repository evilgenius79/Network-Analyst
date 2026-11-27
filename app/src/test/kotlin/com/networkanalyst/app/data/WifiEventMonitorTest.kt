package com.networkanalyst.app.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import com.networkanalyst.core.connect.ConnectionTimeline
import com.networkanalyst.core.connect.EventLogStore
import com.networkanalyst.core.connect.FailedStage
import com.networkanalyst.core.connect.WifiEvent
import com.networkanalyst.core.connect.WifiEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo
import java.io.File

/**
 * Feeds the monitor the broadcasts Android sends and checks what it records. The broadcasts
 * are built by hand here; whether a given phone really sends them can only be seen on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WifiEventMonitorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var clock = 1_000L

    private fun monitor(file: File = File(context.cacheDir, "events-${System.nanoTime()}.json")): WifiEventMonitor {
        val connectivity = ConnectivityCollector(context)
        return WifiEventMonitor(context, connectivity, WifiCollector(context, connectivity), EventLogStore(file), now = { clock++ })
    }

    private fun supplicant(state: SupplicantState, error: Int? = null, reason: Int? = null) =
        Intent(WifiEventMonitor.ACTION_SUPPLICANT_STATE).apply {
            putExtra(WifiEventMonitor.EXTRA_NEW_STATE, state as Parcelable)
            error?.let { putExtra(WifiEventMonitor.EXTRA_SUPPLICANT_ERROR, it) }
            reason?.let { putExtra(WifiEventMonitor.EXTRA_SUPPLICANT_ERROR_REASON, it) }
        }

    @Suppress("DEPRECATION")
    private fun network(state: NetworkInfo.DetailedState) = Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION).apply {
        val info = ShadowNetworkInfo.newInstance(state, ConnectivityManager.TYPE_WIFI, 0, true, state == NetworkInfo.DetailedState.CONNECTED)
        putExtra(WifiManager.EXTRA_NETWORK_INFO, info as Parcelable)
    }

    private fun wifiState(state: Int) = Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).putExtra(WifiManager.EXTRA_WIFI_STATE, state)

    private fun List<WifiEvent>.brief() = map { "${it.kind}:${it.value}" }

    @Test
    fun `the action and extra names match the platform's`() {
        // The app spells these out because the SDK marks them deprecated or hides them.
        @Suppress("DEPRECATION")
        assertEquals(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION, WifiEventMonitor.ACTION_SUPPLICANT_STATE)
        @Suppress("DEPRECATION")
        assertEquals(WifiManager.EXTRA_NEW_STATE, WifiEventMonitor.EXTRA_NEW_STATE)
        @Suppress("DEPRECATION")
        assertEquals(WifiManager.EXTRA_SUPPLICANT_ERROR, WifiEventMonitor.EXTRA_SUPPLICANT_ERROR)
        @Suppress("DEPRECATION")
        assertEquals(WifiManager.ERROR_AUTHENTICATING, WifiEventMonitor.ERROR_AUTHENTICATING)
    }

    @Test
    fun `supplicant steps are recorded by name`() {
        val monitor = monitor()
        monitor.handle(supplicant(SupplicantState.ASSOCIATING))
        monitor.handle(supplicant(SupplicantState.FOUR_WAY_HANDSHAKE))
        assertEquals(listOf("SUPPLICANT:ASSOCIATING", "SUPPLICANT:FOUR_WAY_HANDSHAKE"), monitor.events.value.brief())
        assertEquals(listOf(1_000L, 1_001L), monitor.events.value.map { it.timeMs })
    }

    @Test
    fun `an authentication error is recorded with its reason ahead of the state change`() {
        val monitor = monitor()
        monitor.handle(supplicant(SupplicantState.DISCONNECTED, error = WifiEventMonitor.ERROR_AUTHENTICATING, reason = 2))
        monitor.handle(supplicant(SupplicantState.DISCONNECTED, error = WifiEventMonitor.ERROR_AUTHENTICATING, reason = 1))
        monitor.handle(supplicant(SupplicantState.DISCONNECTED, error = WifiEventMonitor.ERROR_AUTHENTICATING))
        assertEquals(
            listOf(
                "AUTH_ERROR:WRONG_PASSWORD", "SUPPLICANT:DISCONNECTED",
                "AUTH_ERROR:TIMEOUT", "SUPPLICANT:DISCONNECTED",
                "AUTH_ERROR:UNSPECIFIED", "SUPPLICANT:DISCONNECTED",
            ),
            monitor.events.value.brief(),
        )
    }

    @Test
    fun `network states and the Wi-Fi switch are recorded`() {
        val monitor = monitor()
        @Suppress("DEPRECATION")
        monitor.handle(network(NetworkInfo.DetailedState.CONNECTING))
        @Suppress("DEPRECATION")
        monitor.handle(network(NetworkInfo.DetailedState.OBTAINING_IPADDR))
        @Suppress("DEPRECATION")
        monitor.handle(network(NetworkInfo.DetailedState.DISCONNECTED))
        monitor.handle(wifiState(WifiManager.WIFI_STATE_DISABLED))
        monitor.handle(wifiState(WifiManager.WIFI_STATE_ENABLING))
        monitor.handle(wifiState(WifiManager.WIFI_STATE_ENABLED))
        assertEquals(
            listOf("NETWORK:CONNECTING", "NETWORK:OBTAINING_IPADDR", "NETWORK:DISCONNECTED", "WIFI_SWITCH:off", "WIFI_SWITCH:on"),
            monitor.events.value.brief(),
        )
    }

    @Test
    fun `broadcasts without their payload are ignored`() {
        val monitor = monitor()
        monitor.handle(Intent(WifiEventMonitor.ACTION_SUPPLICANT_STATE))
        monitor.handle(Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION))
        monitor.handle(Intent("something.else"))
        assertTrue(monitor.events.value.isEmpty())
    }

    @Test
    fun `recorded broadcasts add up to a failed attempt at the right stage`() {
        val monitor = monitor()
        listOf(SupplicantState.SCANNING, SupplicantState.ASSOCIATING, SupplicantState.ASSOCIATED, SupplicantState.FOUR_WAY_HANDSHAKE, SupplicantState.DISCONNECTED)
            .forEach { monitor.handle(supplicant(it)) }
        val summary = ConnectionTimeline.summarize(monitor.events.value)
        assertTrue(summary.detailed)
        assertEquals(listOf(FailedStage.KEY_EXCHANGE), summary.failures.map { it.failedAt })
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for: $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun `once started it hears real broadcasts, and the log survives a restart`() {
        val file = File(context.cacheDir, "events-restart.json").apply { delete() }
        val first = monitor(file)
        first.start()
        // Not on Wi-Fi in the simulated phone, so the log opens with a remark saying so.
        awaitUntil("opening remark") { first.events.value.any { it.kind == WifiEventKind.NOTE } }

        context.sendBroadcast(supplicant(SupplicantState.ASSOCIATING))
        awaitUntil("broadcast to arrive") { first.events.value.any { it.value == "ASSOCIATING" } }

        first.stop()
        context.sendBroadcast(supplicant(SupplicantState.COMPLETED))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("nothing is recorded after stopping", first.events.value.none { it.value == "COMPLETED" })

        val second = monitor(file)
        second.start()
        awaitUntil("saved log to load") { second.events.value.any { it.value == "ASSOCIATING" } }
        second.clear()
        assertTrue(second.events.value.isEmpty())
        second.stop()
    }

    @Test
    fun `an attempt in progress is recorded with the access point being tried`() {
        val monitor = monitor()
        val wifiManager = context.getSystemService(WifiManager::class.java)
        // What Android's Wi-Fi status holds while the phone is joining an access point.
        val joining = ShadowWifiInfo.newInstance()
        shadowOf(joining).setBSSID("A4:11:22:33:45:87")
        // The platform adds the quotes around a network name itself.
        shadowOf(joining).setSSID("Home")
        shadowOf(joining).setFrequency(5560)
        shadowOf(wifiManager).setConnectionInfo(joining)
        monitor.handle(supplicant(SupplicantState.ASSOCIATING))

        // And once the attempt has ended, when Android clears it again.
        shadowOf(wifiManager).setConnectionInfo(ShadowWifiInfo.newInstance())
        monitor.handle(supplicant(SupplicantState.DISCONNECTED))

        val (trying, ended) = monitor.events.value
        assertEquals("a4:11:22:33:45:87", trying.bssid)
        assertEquals("Home", trying.ssid)
        assertEquals(5560, trying.frequencyMhz)
        assertEquals(null, ended.bssid)

        val failed = ConnectionTimeline.summarize(monitor.events.value).failures.single()
        assertEquals("a4:11:22:33:45:87", failed.bssid)
        assertEquals(FailedStage.JOINING, failed.failedAt)
    }
}
