package com.networkanalyst.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.networkanalyst.app.data.ConnectivityCollector
import com.networkanalyst.app.data.ScanCoordinator
import com.networkanalyst.app.data.ScanOptions
import com.networkanalyst.app.data.ScanProgress
import com.networkanalyst.app.data.SpeedLive
import com.networkanalyst.app.data.WifiCollector
import com.networkanalyst.core.analysis.Diagnosis
import com.networkanalyst.core.analysis.DiagnosticEngine
import com.networkanalyst.core.discovery.LanScanResult
import com.networkanalyst.core.history.HistoryEntry
import com.networkanalyst.core.history.HistoryStore
import com.networkanalyst.core.model.ConnectionInfo
import com.networkanalyst.core.model.NetworkSnapshot
import com.networkanalyst.core.model.SpeedTestResult
import com.networkanalyst.core.model.Transport
import com.networkanalyst.core.model.WifiLink
import com.networkanalyst.core.model.WifiScan
import com.networkanalyst.core.report.TextReport
import com.networkanalyst.core.wifi.BandAnalysis
import com.networkanalyst.core.wifi.ChannelAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Which permissions and switches the scan depends on, as they stand right now. */
data class PermissionsState(
    val locationGranted: Boolean = false,
    val locationEnabled: Boolean = false,
    /** Always true below Android 17, where the permission does not exist. */
    val localNetworkGranted: Boolean = true,
) {
    val canScanWifi: Boolean get() = locationGranted && locationEnabled
}

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Running(val progress: ScanProgress) : ScanUiState
    data class Done(val snapshot: NetworkSnapshot, val diagnosis: Diagnosis) : ScanUiState
}

data class WifiUiState(
    val link: WifiLink? = null,
    /** Signal readings, one per second, oldest first. */
    val rssiHistory: List<Int> = emptyList(),
    val scan: WifiScan? = null,
    val bands: List<BandAnalysis> = emptyList(),
    val scanning: Boolean = false,
)

data class SpeedUiState(
    val running: Boolean = false,
    val live: SpeedLive? = null,
    val result: SpeedTestResult? = null,
    val history: List<HistoryEntry> = emptyList(),
)

data class DevicesUiState(
    val scanning: Boolean = false,
    val message: String = "",
    val result: LanScanResult? = null,
    /** True when a scan was attempted but is not possible (not on Wi-Fi, no permission). */
    val unavailable: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val connectivity = ConnectivityCollector(application)
    private val wifi = WifiCollector(application, connectivity)
    private val coordinator = ScanCoordinator(application, connectivity, wifi)
    private val history = HistoryStore(File(application.filesDir, "history.json"))

    val connection: StateFlow<ConnectionInfo> = connectivity.connection

    private val _permissions = MutableStateFlow(PermissionsState())
    val permissions: StateFlow<PermissionsState> = _permissions.asStateFlow()

    private val _options = MutableStateFlow(ScanOptions())
    val options: StateFlow<ScanOptions> = _options.asStateFlow()

    private val _scan = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val scan: StateFlow<ScanUiState> = _scan.asStateFlow()

    private val _wifi = MutableStateFlow(WifiUiState())
    val wifiState: StateFlow<WifiUiState> = _wifi.asStateFlow()

    private val _speed = MutableStateFlow(SpeedUiState())
    val speed: StateFlow<SpeedUiState> = _speed.asStateFlow()

    private val _devices = MutableStateFlow(DevicesUiState())
    val devices: StateFlow<DevicesUiState> = _devices.asStateFlow()

    private var scanJob: Job? = null
    private var speedJob: Job? = null
    private var devicesJob: Job? = null
    private var wifiMonitorJob: Job? = null

    init {
        connectivity.start()
        refreshPermissions()
        viewModelScope.launch(Dispatchers.IO) {
            val entries = history.load()
            _speed.update { it.copy(history = entries.asReversed()) }
        }
    }

    override fun onCleared() {
        connectivity.stop()
    }

    /** Re-reads permission and location state; call when the app returns to the foreground. */
    fun refreshPermissions() {
        val app = getApplication<Application>()
        _permissions.value = PermissionsState(
            locationGranted = wifi.hasLocationPermission(),
            locationEnabled = wifi.isLocationEnabled(),
            localNetworkGranted = Build.VERSION.SDK_INT < ScanCoordinator.API_LOCAL_NETWORK_PERMISSION ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED,
        )
        refreshWifi()
    }

    fun setOptions(options: ScanOptions) {
        _options.value = options
    }

    // ------------------------------------------------------------------ full scan

    val isBusy: Boolean get() = scanJob?.isActive == true || speedJob?.isActive == true || devicesJob?.isActive == true

    fun startScan() {
        if (isBusy) return
        scanJob = viewModelScope.launch {
            _scan.value = ScanUiState.Running(ScanProgress())
            try {
                val metered = connectivity.snapshot().isMetered
                val options = _options.value.let { if (metered) it.copy(maxSpeedTestBytes = METERED_BYTE_CAP) else it }
                val outcome = withContext(Dispatchers.Default) {
                    coordinator.run(options) { progress -> _scan.value = ScanUiState.Running(progress) }
                }
                val snapshot = outcome.snapshot
                val key = networkKey(snapshot.connection, snapshot.wifiLink)
                val entries = withContext(Dispatchers.IO) { history.load() }
                val diagnosis = DiagnosticEngine.diagnose(snapshot, HistoryStore.baseline(entries, key.first))
                recordHistory(key, snapshot.speed, diagnosis.healthScore)
                // Share what the scan learned with the other tabs.
                outcome.lan?.let { lan -> _devices.value = DevicesUiState(result = lan) }
                snapshot.speed?.let { result -> _speed.update { it.copy(result = result, live = null) } }
                snapshot.wifiScan?.let { applyWifiScan(it) }
                _scan.value = ScanUiState.Done(snapshot, diagnosis)
            } catch (e: CancellationException) {
                _scan.value = ScanUiState.Idle
                throw e
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    fun clearScan() {
        if (scanJob?.isActive != true) _scan.value = ScanUiState.Idle
    }

    /** Plain-text version of the last scan, for the share sheet. */
    fun reportText(): String? = (_scan.value as? ScanUiState.Done)?.let { TextReport.build(it.snapshot, it.diagnosis) }

    // ------------------------------------------------------------------ speed test

    fun startSpeedTest() {
        if (isBusy) return
        speedJob = viewModelScope.launch {
            _speed.update { it.copy(running = true, live = SpeedLive(), result = null) }
            try {
                val connection = connectivity.snapshot()
                val cap = if (connection.isMetered) METERED_BYTE_CAP else Long.MAX_VALUE
                val result = withContext(Dispatchers.Default) {
                    coordinator.runSpeedTest(cap) { live -> _speed.update { it.copy(live = live) } }
                }
                recordHistory(networkKey(connection, wifi.currentLink()), result, healthScore = null)
                _speed.update { it.copy(running = false, live = null, result = result) }
            } catch (e: CancellationException) {
                _speed.update { it.copy(running = false, live = null) }
                throw e
            }
        }
    }

    fun cancelSpeedTest() {
        speedJob?.cancel()
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            history.clear()
            _speed.update { it.copy(history = emptyList()) }
        }
    }

    private suspend fun recordHistory(key: Pair<String, String>, result: SpeedTestResult?, healthScore: Int?) {
        if (result == null || (result.downloadMbps == null && result.uploadMbps == null)) return
        val entry = HistoryEntry(
            timestampMs = System.currentTimeMillis(),
            networkKey = key.first,
            networkLabel = key.second,
            downloadMbps = result.downloadMbps,
            uploadMbps = result.uploadMbps,
            idleLatencyMs = result.idleLatencyMs,
            bufferbloatMs = result.bufferbloatMs,
            healthScore = healthScore,
        )
        val entries = withContext(Dispatchers.IO) { history.add(entry) }
        _speed.update { it.copy(history = entries.asReversed()) }
    }

    /** Key and display name of the network a result belongs to, so like is compared with like. */
    private fun networkKey(connection: ConnectionInfo, link: WifiLink?): Pair<String, String> = when (connection.transport) {
        Transport.WIFI -> (link?.ssid ?: "unnamed").let { "wifi:$it" to (link?.ssid ?: "Wi-Fi") }
        Transport.CELLULAR -> "mobile" to "Mobile data"
        Transport.ETHERNET -> "ethernet" to "Ethernet"
        else -> "other" to connection.transport.label
    }

    // ------------------------------------------------------------------ Wi-Fi tab

    /** Follows the signal once a second while the Wi-Fi tab is on screen. */
    fun startWifiMonitor() {
        if (wifiMonitorJob?.isActive == true) return
        wifiMonitorJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val rssi = wifi.currentRssi()
                _wifi.update { state ->
                    state.copy(
                        link = wifi.currentLink(state.scan?.accessPoints.orEmpty()),
                        rssiHistory = if (rssi != null) (state.rssiHistory + rssi).takeLast(RSSI_HISTORY_POINTS) else emptyList(),
                    )
                }
                delay(1_000)
            }
        }
    }

    fun stopWifiMonitor() {
        wifiMonitorJob?.cancel()
        wifiMonitorJob = null
    }

    /** Shows the networks Android already knows about, without triggering a new scan. */
    private fun refreshWifi() {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.Default) { wifi.cachedScan() }
            if (_wifi.value.scan == null || cached.accessPoints.isNotEmpty()) applyWifiScan(cached)
        }
    }

    fun rescanWifi() {
        if (_wifi.value.scanning) return
        viewModelScope.launch {
            _wifi.update { it.copy(scanning = true) }
            try {
                applyWifiScan(withContext(Dispatchers.Default) { wifi.scan() })
            } finally {
                _wifi.update { it.copy(scanning = false) }
            }
        }
    }

    private fun applyWifiScan(scan: WifiScan) {
        val link = wifi.currentLink(scan.accessPoints)
        val connected = scan.accessPoints.firstOrNull { it.isConnected }
        _wifi.update { it.copy(scan = scan, link = link, bands = ChannelAnalyzer.analyze(scan.accessPoints, connected)) }
    }

    // ------------------------------------------------------------------ devices tab

    fun startDeviceScan() {
        if (isBusy) return
        devicesJob = viewModelScope.launch {
            _devices.update { it.copy(scanning = true, message = "Looking for devices", unavailable = false) }
            try {
                val result = withContext(Dispatchers.Default) {
                    coordinator.scanDevices { message -> _devices.update { it.copy(message = message) } }
                }
                _devices.update { DevicesUiState(result = result ?: it.result, unavailable = result == null) }
            } catch (e: CancellationException) {
                _devices.update { it.copy(scanning = false) }
                throw e
            }
        }
    }

    fun cancelDeviceScan() {
        devicesJob?.cancel()
    }

    companion object {
        /** On metered connections each direction of the speed test stops after this much data. */
        const val METERED_BYTE_CAP = 100_000_000L
        private const val RSSI_HISTORY_POINTS = 90
    }
}
