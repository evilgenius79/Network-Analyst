package com.networkanalyst.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import androidx.test.core.app.ApplicationProvider
import com.networkanalyst.core.connect.ConnectFailure
import com.networkanalyst.core.connect.ConnectionTestAnalyzer
import com.networkanalyst.core.connect.EventLogStore
import com.networkanalyst.core.connect.FailedStage
import com.networkanalyst.core.connect.TargetResult
import com.networkanalyst.core.connect.TestTarget
import com.networkanalyst.core.connect.WifiEvent
import com.networkanalyst.core.connect.WifiEventKind
import com.networkanalyst.core.model.WifiSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the connection tester against a simulated Android in which this test plays the part
 * of the system: it answers each connection request and sends the Wi-Fi steps a phone would.
 * That checks the tester's own logic. How a real phone answers can only be seen on one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionTesterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    private val ap24 = TestTarget("Home", "a4:11:22:33:44:50", 2437, -44)
    private val ap5 = TestTarget("Home", "a4:11:22:33:44:51", 5180, -47)

    private val monitor: WifiEventMonitor = run {
        val connectivity = ConnectivityCollector(context)
        // Never started: the test adds the events a phone would broadcast.
        WifiEventMonitor(context, connectivity, WifiCollector(context, connectivity), EventLogStore(File(context.cacheDir, "tester-${System.nanoTime()}.json")))
    }
    private val tester = ConnectionTester(context, monitor)

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun step(state: String, kind: WifiEventKind = WifiEventKind.SUPPLICANT, bssid: String? = null) {
        monitor.record(WifiEvent(System.currentTimeMillis(), kind, state, bssid = bssid))
        Thread.sleep(3)
    }

    private fun callbacks(): List<NetworkCallback> =
        // Read from another thread than the one registering; retry rather than trip over a change in progress.
        runCatching { shadowOf(connectivityManager).networkCallbacks.toList() }.getOrDefault(emptyList())

    private fun awaitRequest(seen: MutableSet<NetworkCallback>): NetworkCallback {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            callbacks().firstOrNull { it !in seen }?.let {
                seen += it
                return it
            }
            Thread.sleep(10)
        }
        fail("The tester made no connection request")
        throw AssertionError()
    }

    private fun awaitReleased(callback: NetworkCallback) {
        val deadline = System.currentTimeMillis() + 15_000
        while (callback in callbacks()) {
            if (System.currentTimeMillis() > deadline) fail("The tester did not release its request")
            Thread.sleep(10)
        }
    }

    private fun start(targets: List<TestTarget>, trials: Int, progress: MutableList<ConnectTestProgress>) =
        executor.submit<List<TargetResult>> {
            runBlocking { tester.run(targets, WifiSecurity.WPA2, "correct horse", trials) { progress += it } }
        }

    @Test
    fun `failure codes match the platform's constants`() {
        assertEquals(ConnectFailure.UNKNOWN, ConnectionTester.mapReason(WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_UNKNOWN))
        assertEquals(ConnectFailure.ASSOCIATION, ConnectionTester.mapReason(WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_ASSOCIATION))
        assertEquals(ConnectFailure.AUTHENTICATION, ConnectionTester.mapReason(WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_AUTHENTICATION))
        assertEquals(ConnectFailure.IP_ADDRESS, ConnectionTester.mapReason(WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_IP_PROVISIONING))
        assertEquals(ConnectFailure.NOT_FOUND, ConnectionTester.mapReason(WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_NOT_FOUND))
        assertEquals(ConnectFailure.NO_RESPONSE, ConnectionTester.mapReason(WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_NO_RESPONSE))
        assertEquals(ConnectFailure.DECLINED, ConnectionTester.mapReason(WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_USER_REJECT))
        assertEquals(ConnectFailure.UNKNOWN, ConnectionTester.mapReason(99))
    }

    @Test
    fun `refuses to start when it cannot work, saying why`() {
        fun refusal(targets: List<TestTarget> = listOf(ap24), security: WifiSecurity = WifiSecurity.WPA2, password: String? = "correct horse"): String? = try {
            runBlocking { tester.run(targets, security, password, 1) {} }
            null
        } catch (e: ConnectionTestUnavailable) {
            e.message
        }
        assertEquals("Choose at least one access point to test.", refusal(targets = emptyList()))
        assertEquals("Enter the Wi-Fi password (8 to 63 characters).", refusal(password = "short"))
        assertEquals("Enter the Wi-Fi password (8 to 63 characters).", refusal(password = null))
        assertEquals(ConnectionTester.NON_ASCII_PASSWORD, refusal(password = "pässword123"))
        assertEquals("Enterprise (802.1X) networks cannot be tested this way.", refusal(security = WifiSecurity.ENTERPRISE))
        assertEquals("WEP networks cannot be tested this way.", refusal(security = WifiSecurity.WEP))
        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = false
        assertEquals("Turn Wi-Fi on first.", refusal())
        // Nothing was requested and nothing was marked in the log.
        assertTrue(callbacks().isEmpty())
        assertTrue(monitor.events.value.isEmpty())
    }

    @Test
    fun `tests each access point and reports what happened behind each request`() {
        val progress = CopyOnWriteArrayList<ConnectTestProgress>()
        val seen = HashSet<NetworkCallback>()
        val run = start(listOf(ap24, ap5), trials = 1, progress)

        // 2.4 GHz: the phone joins first time.
        val first = awaitRequest(seen)
        listOf("ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "COMPLETED").forEach { step(it) }
        first.onAvailable(ShadowNetwork.newInstance(101))
        awaitReleased(first)
        step("DISCONNECTED")

        // 5 GHz: refused three times over, then Android gives up and names the stage.
        val second = awaitRequest(seen)
        repeat(3) {
            step("ASSOCIATING")
            step("DISCONNECTED")
        }
        shadowOf(wifiManager).triggerLocalConnectionFailure(
            WifiNetworkSpecifier.Builder().setSsid("Home").build(),
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_ASSOCIATION,
        )
        second.onUnavailable()

        val results = run.get(30, TimeUnit.SECONDS)
        assertEquals(listOf(ap24, ap5), results.map { it.target })

        val good = results[0].trials.single()
        assertTrue(good.success)
        assertNull(good.failure)
        assertEquals(emptyList<FailedStage>(), good.hiddenFailures)

        val bad = results[1].trials.single()
        assertFalse(bad.success)
        assertEquals(ConnectFailure.ASSOCIATION, bad.failure)
        // Three tries were seen; the last is the reported failure, the two before it were silent.
        assertEquals(listOf(FailedStage.JOINING, FailedStage.JOINING), bad.hiddenFailures)

        assertEquals("Connecting fails on 5 GHz only.", ConnectionTestAnalyzer.analyze(results).headline)

        // Every request was released and the test window is marked in the log.
        assertTrue(callbacks().none { it in seen })
        val marks = monitor.events.value.filter { it.kind == WifiEventKind.TEST_START || it.kind == WifiEventKind.TEST_END }
        assertEquals(listOf(WifiEventKind.TEST_START, WifiEventKind.TEST_END), marks.map { it.kind })
        assertEquals("Home", marks.first().value)

        assertTrue(progress.first().message.contains("If a dialog appears"))
        assertEquals(0, progress.first().done)
        assertEquals(2, progress.last().done)
        assertEquals(2, progress.last().total)
    }

    @Test
    fun `a request turned down in Android's dialog stops the test`() {
        val progress = CopyOnWriteArrayList<ConnectTestProgress>()
        val seen = HashSet<NetworkCallback>()
        val run = start(listOf(ap24, ap5), trials = 2, progress)

        // No connection steps and no failure stage: the request ended before any connecting,
        // after long enough for a person to have read the dialog and said no.
        val request = awaitRequest(seen)
        Thread.sleep(700)
        request.onUnavailable()

        val results = run.get(30, TimeUnit.SECONDS)
        assertEquals(ConnectFailure.DECLINED, results[0].trials.single().failure)
        assertTrue(results[1].trials.isEmpty())
        assertEquals(1, seen.size)
        assertEquals("The request was not allowed.", progress.last().message)
        assertEquals("Nothing was tested because the request was not allowed.", ConnectionTestAnalyzer.analyze(results).headline)
    }

    @Test
    fun `a request Android refuses outright stops the test and is not blamed on the access point`() {
        val progress = CopyOnWriteArrayList<ConnectTestProgress>()
        val seen = HashSet<NetworkCallback>()
        val run = start(listOf(ap24, ap5), trials = 2, progress)

        val first = awaitRequest(seen)
        listOf("ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "COMPLETED").forEach { step(it) }
        first.onAvailable(ShadowNetwork.newInstance(101))
        awaitReleased(first)
        step("DISCONNECTED")

        // The app has left the screen: Android answers the next request at once with a no.
        awaitRequest(seen).onUnavailable()

        val results = run.get(30, TimeUnit.SECONDS)
        assertEquals(listOf(null, ConnectFailure.REJECTED), results[0].trials.map { it.failure })
        assertTrue("the second access point is never asked", results[1].trials.isEmpty())
        assertEquals(2, seen.size)
        assertEquals("Android did not accept the request.", progress.last().message)

        val report = ConnectionTestAnalyzer.analyze(results)
        assertEquals(ConnectFailure.REJECTED, report.stoppedBy)
        assertEquals(1, report.results[0].trials.size)
        assertEquals(1, report.results[0].successes)
    }

    @Test
    fun `android's stage-less failure is named from the steps seen, and the phone's own tries elsewhere are left out`() {
        val progress = CopyOnWriteArrayList<ConnectTestProgress>()
        val seen = HashSet<NetworkCallback>()
        val run = start(listOf(ap5), trials = 1, progress)

        val request = awaitRequest(seen)
        // The phone had just begun rejoining its saved network at the other access point.
        step("ASSOCIATING", bssid = ap24.bssid)
        step("DISCONNECTED")
        // Then the requested access point lets it join but the key exchange fails, twice.
        repeat(2) {
            step("ASSOCIATING", bssid = ap5.bssid)
            step("ASSOCIATED", bssid = ap5.bssid)
            step("FOUR_WAY_HANDSHAKE", bssid = ap5.bssid)
            step("DISCONNECTED")
        }
        // Android gives up and reports a failure with no stage, as a real phone did.
        shadowOf(wifiManager).triggerLocalConnectionFailure(
            WifiNetworkSpecifier.Builder().setSsid("Home").build(),
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_UNKNOWN,
        )
        request.onUnavailable()

        val trial = run.get(30, TimeUnit.SECONDS).single().trials.single()
        assertEquals(ConnectFailure.AUTHENTICATION, trial.failure)
        assertEquals(listOf(FailedStage.KEY_EXCHANGE), trial.hiddenFailures)
    }

    @Test
    fun `stopping midway releases the request and closes the test window`() {
        val seen = HashSet<NetworkCallback>()
        runBlocking {
            val job = launch(Dispatchers.Default) { tester.run(listOf(ap24, ap5), WifiSecurity.WPA2, "correct horse", 3) {} }
            val pending = awaitRequest(seen)
            job.cancelAndJoin()
            assertFalse(pending in callbacks())
        }
        assertEquals(WifiEventKind.TEST_END, monitor.events.value.last().kind)
    }
}
