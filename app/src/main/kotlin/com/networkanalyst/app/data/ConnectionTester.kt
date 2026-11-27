package com.networkanalyst.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import com.networkanalyst.core.connect.ConnectFailure
import com.networkanalyst.core.connect.ConnectTrial
import com.networkanalyst.core.connect.TargetResult
import com.networkanalyst.core.connect.TestTarget
import com.networkanalyst.core.connect.TrialOutcome
import com.networkanalyst.core.connect.WifiEvent
import com.networkanalyst.core.connect.WifiEventKind
import com.networkanalyst.core.model.WifiSecurity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/** Where a running connection test has got to. */
data class ConnectTestProgress(
    val message: String,
    /** Requests finished so far and in total. */
    val done: Int,
    val total: Int,
    val results: List<TargetResult>,
)

/** The test could not be run at all. The message is written for the user. */
class ConnectionTestUnavailable(message: String) : Exception(message)

/**
 * Asks Android to connect to chosen access points, one at a time, and records how each
 * request went.
 *
 * This uses the Wi-Fi network request API ([WifiNetworkSpecifier]). Android shows its own
 * dialog the first time an app asks to join a network; after that, requests for access points
 * of the same password-protected network go through without asking again. The connection made
 * is private to this app and carries no internet traffic, and it is dropped as soon as the
 * request is released. On most phones the normal Wi-Fi connection is interrupted meanwhile.
 *
 * From Android 14 the system also says at which stage a failed request stopped.
 */
class ConnectionTester(
    context: Context,
    private val monitor: WifiEventMonitor,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)

    /** The network request API exists from Android 10. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Android reports the failing stage from Android 14. */
    val reportsStage: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** True when the phone can make the test connection alongside its normal one. */
    val keepsNormalConnection: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { wifiManager?.isStaConcurrencyForLocalOnlyConnectionsSupported == true }.getOrDefault(false)

    /**
     * Runs [trialsPerTarget] connection requests against each target in turn.
     *
     * @throws ConnectionTestUnavailable when the test cannot start
     */
    suspend fun run(
        targets: List<TestTarget>,
        security: WifiSecurity,
        password: String?,
        trialsPerTarget: Int,
        onProgress: (ConnectTestProgress) -> Unit,
    ): List<TargetResult> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw ConnectionTestUnavailable("The connection test needs Android 10 or newer.")
        if (targets.isEmpty()) throw ConnectionTestUnavailable("Choose at least one access point to test.")
        if (wifiManager?.isWifiEnabled != true) throw ConnectionTestUnavailable("Turn Wi-Fi on first.")
        when (security) {
            WifiSecurity.WEP, WifiSecurity.ENTERPRISE ->
                throw ConnectionTestUnavailable("${security.label} networks cannot be tested this way.")
            WifiSecurity.OPEN, WifiSecurity.OWE -> Unit
            else -> {
                if (password == null || password.length !in 8..63) {
                    throw ConnectionTestUnavailable("Enter the Wi-Fi password (8 to 63 characters).")
                }
                // Android's request API takes ASCII passwords only and throws on anything else.
                if (!isAscii(password)) throw ConnectionTestUnavailable(NON_ASCII_PASSWORD)
            }
        }

        val trials = LinkedHashMap<String, MutableList<ConnectTrial>>()
        targets.forEach { trials[it.bssid] = ArrayList() }
        fun results() = targets.map { TargetResult(it, trials.getValue(it.bssid).toList()) }

        val reported = AtomicReference<ConnectFailure?>(null)
        val failureListener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) registerFailureListener(reported) else null
        val total = targets.size * trialsPerTarget
        var done = 0
        var approvalPending = true
        // Android remembers an approval for every access point of a password-protected network,
        // but for open networks only for the one access point that was approved.
        val approvalPerTarget = security == WifiSecurity.OPEN || security == WifiSecurity.OWE
        monitor.record(WifiEvent(now(), WifiEventKind.TEST_START, targets.first().ssid))
        try {
            outer@ for (target in targets) {
                if (approvalPerTarget) approvalPending = true
                for (attempt in 1..trialsPerTarget) {
                    onProgress(
                        ConnectTestProgress(
                            if (approvalPending) {
                                "Asking Android to connect to ${target.radioLabel}. If a dialog appears, choose the network and allow it."
                            } else {
                                "Connecting to ${target.radioLabel}, attempt $attempt of $trialsPerTarget"
                            },
                            done, total, results(),
                        ),
                    )
                    reported.set(null)
                    val trial = trial(target, security, password, reported, approvalPending)
                    trials.getValue(target.bssid) += trial
                    done++
                    if (trial.failure == ConnectFailure.DECLINED) {
                        // Do not keep putting dialogs in front of someone who said no.
                        onProgress(ConnectTestProgress("The request was not allowed.", done, total, results()))
                        break@outer
                    }
                    if (trial.failure == ConnectFailure.REJECTED) {
                        // Every further request would be turned down the same way and tell nothing.
                        onProgress(ConnectTestProgress("Android did not accept the request.", done, total, results()))
                        break@outer
                    }
                    // Any request that got as far as connecting proves approval was given.
                    if (trial.success || trial.failure !in setOf(ConnectFailure.TIMED_OUT, ConnectFailure.NOT_FOUND, ConnectFailure.UNKNOWN)) {
                        approvalPending = false
                    }
                    onProgress(ConnectTestProgress("Finished ${target.radioLabel}, attempt $attempt of $trialsPerTarget", done, total, results()))
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (failureListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) unregisterFailureListener(failureListener)
                monitor.record(WifiEvent(now(), WifiEventKind.TEST_END))
            }
        }
        return results()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun trial(
        target: TestTarget,
        security: WifiSecurity,
        password: String?,
        reported: AtomicReference<ConnectFailure?>,
        approvalPending: Boolean,
    ): ConnectTrial {
        val request = try {
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // Required: a network made this way never carries internet traffic.
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier(target, security, password))
                .build()
        } catch (_: RuntimeException) {
            // The builders throw on values they cannot use (a malformed address, say).
            return ConnectTrial(target.bssid, success = false, failure = ConnectFailure.UNKNOWN, durationMs = 0)
        }
        val outcome = CompletableDeferred<Boolean>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                outcome.complete(true)
            }

            override fun onUnavailable() {
                outcome.complete(false)
            }
        }
        // The first request waits on the person; later ones only on Android's own retries.
        val timeoutMs = if (approvalPending) APPROVAL_TIMEOUT_MS else CONNECT_TIMEOUT_MS
        val startedAt = now()
        val startNanos = System.nanoTime()
        var registered = false
        try {
            try {
                connectivity.requestNetwork(request, callback, timeoutMs)
                registered = true
            } catch (_: RuntimeException) {
                // Missing permission or too many requests outstanding.
                return ConnectTrial(target.bssid, success = false, failure = ConnectFailure.UNKNOWN, durationMs = 0)
            }
            val available = withTimeoutOrNull(timeoutMs + 5_000L) { outcome.await() }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            if (available == true) {
                // Snapshot before releasing: the disconnect that release causes is not a failure.
                val result = TrialOutcome.success(monitor.since(startedAt), target.bssid)
                delay(HOLD_MS)
                return ConnectTrial(target.bssid, success = true, durationMs = elapsedMs, hiddenFailures = result.hiddenFailures)
            }
            // The stage arrives through a separate callback; give it a moment to land.
            if (reportsStage) {
                withTimeoutOrNull(STAGE_WAIT_MS) { while (reported.get() == null) delay(25) }
            }
            val result = TrialOutcome.failure(
                reported = reported.get(),
                timedOut = available == null || elapsedMs >= timeoutMs,
                windowEvents = monitor.since(startedAt),
                approvalExpected = approvalPending,
                stageReporting = reportsStage,
                elapsedMs = elapsedMs,
                targetBssid = target.bssid,
            )
            return ConnectTrial(target.bssid, success = false, failure = result.failure, durationMs = elapsedMs, hiddenFailures = result.hiddenFailures)
        } finally {
            withContext(NonCancellable) {
                if (registered) {
                    val releasedAt = now()
                    runCatching { connectivity.unregisterNetworkCallback(callback) }
                    awaitTeardown(releasedAt)
                }
            }
        }
    }

    /**
     * Waits for the test connection to be gone before the next request, so each request starts
     * from scratch. Kept short on purpose: the sooner the next request follows, the less chance
     * the phone has to start rejoining its saved network in between.
     */
    private suspend fun awaitTeardown(releasedAt: Long) {
        withTimeoutOrNull(TEARDOWN_MAX_MS) {
            while (true) {
                val gone = monitor.since(releasedAt).any {
                    (it.kind == WifiEventKind.SUPPLICANT || it.kind == WifiEventKind.NETWORK) && it.value == "DISCONNECTED"
                }
                if (gone) break
                delay(50)
            }
        }
        delay(TEARDOWN_SETTLE_MS)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun specifier(target: TestTarget, security: WifiSecurity, password: String?): WifiNetworkSpecifier {
        val builder = WifiNetworkSpecifier.Builder()
            .setSsid(target.ssid)
            // Naming one access point exactly is what lets each radio be tested on its own.
            .setBssid(MacAddress.fromString(target.bssid))
        when (security) {
            WifiSecurity.OPEN -> Unit
            WifiSecurity.OWE -> builder.setIsEnhancedOpen(true)
            WifiSecurity.WPA3 -> builder.setWpa3Passphrase(password.orEmpty())
            else -> builder.setWpa2Passphrase(password.orEmpty())
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerFailureListener(reported: AtomicReference<ConnectFailure?>): Any? {
        val listener = WifiManager.LocalOnlyConnectionFailureListener { _, reason -> reported.set(mapReason(reason)) }
        return try {
            wifiManager?.addLocalOnlyConnectionFailureListener(DIRECT_EXECUTOR, listener)
            listener
        } catch (_: RuntimeException) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregisterFailureListener(listener: Any) {
        runCatching { wifiManager?.removeLocalOnlyConnectionFailureListener(listener as WifiManager.LocalOnlyConnectionFailureListener) }
    }

    companion object {
        // Backstops only: Android ends a request itself once its own retries are used up.
        // They are generous because a failure at the IP address step is slow, and Android
        // goes through it up to four times before giving up.
        private const val APPROVAL_TIMEOUT_MS = 180_000
        private const val CONNECT_TIMEOUT_MS = 150_000
        private const val HOLD_MS = 400L
        private const val STAGE_WAIT_MS = 600L
        private const val TEARDOWN_MAX_MS = 1_500L
        private const val TEARDOWN_SETTLE_MS = 150L
        private val DIRECT_EXECUTOR = Executor { it.run() }

        const val NON_ASCII_PASSWORD = "This password has characters Android's connection request cannot carry. Only plain letters, digits and symbols work here."

        /** Android accepts a password in a connection request only if it is plain ASCII. */
        fun isAscii(text: String): Boolean = text.all { it.code < 128 }

        /** Maps WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_* (0 to 6) to the app's reasons. */
        fun mapReason(status: Int): ConnectFailure = when (status) {
            1 -> ConnectFailure.ASSOCIATION
            2 -> ConnectFailure.AUTHENTICATION
            3 -> ConnectFailure.IP_ADDRESS
            4 -> ConnectFailure.NOT_FOUND
            5 -> ConnectFailure.NO_RESPONSE
            6 -> ConnectFailure.DECLINED
            else -> ConnectFailure.UNKNOWN
        }
    }
}
