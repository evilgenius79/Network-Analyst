package com.networkanalyst.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.networkanalyst.core.connect.EventLogStore
import com.networkanalyst.core.connect.WifiEvent
import com.networkanalyst.core.connect.WifiEventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records what the phone's Wi-Fi does: every step of every connection attempt the system
 * makes, connections coming up and going away, and Wi-Fi being switched.
 *
 * The steps come from two system broadcasts. `NETWORK_STATE_CHANGED_ACTION` gives the coarse
 * ones. `SUPPLICANT_STATE_CHANGED_ACTION` gives the fine ones (associating, key handshake),
 * plus a flag when authentication failed; Android's documentation marks it deprecated, but
 * the platform still sends it. A phone that does not is simply recorded more coarsely.
 *
 * Android queues these broadcasts while an app is in the background, so the log is only
 * live while the app is on screen or [WatchService] is running.
 */
class WifiEventMonitor(
    context: Context,
    private val connectivity: ConnectivityCollector,
    private val wifi: WifiCollector,
    private val store: EventLogStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableStateFlow<List<WifiEvent>>(emptyList())
    val events: StateFlow<List<WifiEvent>> = _events.asStateFlow()

    @Volatile private var lastRssi: Int? = null
    @Volatile private var dirty = false
    private var started = false
    private var housekeeping: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // On registering, Android replays the last value of each of these. That is the
            // state we start from, not something that just happened.
            if (isInitialStickyBroadcast) return
            handle(intent)
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val saved = store.load()
            _events.update { current -> (saved + current).sortedBy { it.timeMs }.takeLast(MAX_EVENTS) }
            record(baseline())
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SUPPLICANT_STATE)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        // System broadcasts reach receivers that are not exported.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        connectivity.wifiLinkListener = { up -> onLink(up) }
        housekeeping = scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                wifi.currentRssi()?.let { lastRssi = it }
                if (dirty) {
                    dirty = false
                    store.save(_events.value)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { appContext.unregisterReceiver(receiver) }
        connectivity.wifiLinkListener = null
        housekeeping?.cancel()
        store.save(_events.value)
    }

    /** Adds an event to the log. Also used by the connection test to mark its own activity. */
    fun record(event: WifiEvent) {
        _events.update { (it + event).takeLast(MAX_EVENTS) }
        dirty = true
    }

    fun note(text: String) = record(WifiEvent(now(), WifiEventKind.NOTE, text))

    fun clear() {
        _events.value = emptyList()
        scope.launch { store.clear() }
    }

    /** Events at or after [sinceMs], for looking at what happened during one operation. */
    fun since(sinceMs: Long): List<WifiEvent> = _events.value.filter { it.timeMs >= sinceMs }

    /** Where things stand when watching begins. */
    private fun baseline(): WifiEvent {
        val link = runCatching { wifi.currentLink() }.getOrNull()
        return if (link != null) {
            lastRssi = link.rssiDbm
            WifiEvent(now(), WifiEventKind.LINK_UP, "Already connected when watching started", link.ssid, link.bssid, link.rssiDbm, link.frequencyMhz)
        } else {
            WifiEvent(now(), WifiEventKind.NOTE, "Watching started; Wi-Fi is not connected")
        }
    }

    private fun onLink(up: Boolean) {
        if (up) {
            // The link details settle a moment after the network appears.
            scope.launch {
                delay(300)
                val link = runCatching { wifi.currentLink() }.getOrNull()
                link?.rssiDbm?.let { lastRssi = it }
                record(WifiEvent(now(), WifiEventKind.LINK_UP, "", link?.ssid, link?.bssid, link?.rssiDbm, link?.frequencyMhz))
            }
        } else {
            record(WifiEvent(now(), WifiEventKind.LINK_DOWN, "", rssiDbm = lastRssi))
        }
    }

    internal fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_SUPPLICANT_STATE -> {
                val state = IntentCompat.getParcelableExtra(intent, EXTRA_NEW_STATE, SupplicantState::class.java) ?: return
                if (intent.getIntExtra(EXTRA_SUPPLICANT_ERROR, 0) == ERROR_AUTHENTICATING) {
                    record(WifiEvent(now(), WifiEventKind.AUTH_ERROR, authReason(intent.getIntExtra(EXTRA_SUPPLICANT_ERROR_REASON, 0))))
                }
                val settled = state == SupplicantState.COMPLETED
                val link = if (settled) runCatching { wifi.currentLink() }.getOrNull() else null
                record(
                    WifiEvent(
                        now(), WifiEventKind.SUPPLICANT, state.name,
                        ssid = link?.ssid, bssid = link?.bssid,
                        // The last signal reading before a disconnect says whether range caused it.
                        rssiDbm = link?.rssiDbm ?: lastRssi.takeIf { state == SupplicantState.DISCONNECTED },
                        frequencyMhz = link?.frequencyMhz,
                    ),
                )
            }
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                @Suppress("DEPRECATION")
                val info = IntentCompat.getParcelableExtra(intent, WifiManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java) ?: return
                @Suppress("DEPRECATION")
                val state = info.detailedState
                @Suppress("DEPRECATION")
                val connected = state == NetworkInfo.DetailedState.CONNECTED
                val link = if (connected) runCatching { wifi.currentLink() }.getOrNull() else null
                record(WifiEvent(now(), WifiEventKind.NETWORK, state.name, link?.ssid, link?.bssid, link?.rssiDbm, link?.frequencyMhz))
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)) {
                WifiManager.WIFI_STATE_ENABLED -> record(WifiEvent(now(), WifiEventKind.WIFI_SWITCH, "on"))
                WifiManager.WIFI_STATE_DISABLED -> record(WifiEvent(now(), WifiEventKind.WIFI_SWITCH, "off"))
            }
        }
    }

    companion object {
        private const val MAX_EVENTS = 3_000
        private const val SAMPLE_INTERVAL_MS = 5_000L

        // These constants are deprecated or hidden in the SDK, so their documented values are
        // spelled out here (android.net.wifi.WifiManager).
        const val ACTION_SUPPLICANT_STATE = "android.net.wifi.supplicant.STATE_CHANGE"
        const val EXTRA_NEW_STATE = "newState"
        const val EXTRA_SUPPLICANT_ERROR = "supplicantError"
        const val EXTRA_SUPPLICANT_ERROR_REASON = "supplicantErrorReason"
        const val ERROR_AUTHENTICATING = 1

        /** Names for WifiManager.ERROR_AUTH_FAILURE_* (0 none, 1 timeout, 2 wrong password, 3 EAP). */
        fun authReason(code: Int): String = when (code) {
            1 -> "TIMEOUT"
            2 -> "WRONG_PASSWORD"
            3 -> "EAP"
            else -> "UNSPECIFIED"
        }
    }
}
