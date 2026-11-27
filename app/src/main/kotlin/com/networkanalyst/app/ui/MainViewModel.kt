package com.networkanalyst.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.networkanalyst.app.NetworkAnalystApp
import com.networkanalyst.app.data.ConnectTestProgress
import com.networkanalyst.app.data.ConnectionTestUnavailable
import com.networkanalyst.app.data.ConnectionTester
import com.networkanalyst.app.data.ScanCoordinator
import com.networkanalyst.app.data.ScanOptions
import com.networkanalyst.app.data.ScanProgress
import com.networkanalyst.app.data.SpeedLive
import com.networkanalyst.app.data.WatchService
import com.networkanalyst.core.analysis.Diagnosis
import com.networkanalyst.core.analysis.DiagnosticEngine
import com.networkanalyst.core.connect.ConnectReportText
import com.networkanalyst.core.connect.ConnectionTestAnalyzer
import com.networkanalyst.core.connect.ConnectionTestReport
import com.networkanalyst.core.connect.TargetPicker
import com.networkanalyst.core.connect.TestNetwork
import com.networkanalyst.core.connect.WatchAnalyzer
import com.networkanalyst.core.connect.WatchReport
import com.networkanalyst.core.connect.WifiEvent
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** The connection test: what can be tested, what was chosen, and how it went. */
data class ConnectUiState(
    /** False below Android 10, where apps cannot request a connection to a chosen access point. */
    val supported: Boolean = true,
    /** Android names the failing stage itself (Android 14 and newer). */
    val reportsStage: Boolean = true,
    /** The phone can run the test alongside its normal Wi-Fi connection. */
    val keepsNormalConnection: Boolean = false,
    /** Networks in range, from the latest Wi-Fi scan. */
    val networks: List<TestNetwork> = emptyList(),
    /** Name of the network chosen for testing. */
    val ssid: String? = null,
    /** Access point radios (by address) ticked for testing. */
    val selected: Set<String> = emptySet(),
    /** Held in memory only for the test; never written to storage. */
    val password: String = "",
    val trialsPerTarget: Int = DEFAULT_TRIALS,
    val running: Boolean = false,
    val progress: ConnectTestProgress? = null,
    val report: ConnectionTestReport? = null,
    /** Why the test could not start, written for the user. */
    val error: String? = null,
) {
    val network: TestNetwork? get() = networks.firstOrNull { it.ssid == ssid }

    /** What is wrong with the password as typed, or null when it can be used (or is not needed). */
    val passwordProblem: String?
        get() = when {
            network?.needsPassword != true -> null
            !ConnectionTester.isAscii(password) -> ConnectionTester.NON_ASCII_PASSWORD
            // WPA2 and WPA3 passwords are 8 to 63 characters.
            password.length !in 8..63 -> "Wi-Fi passwords are 8 to 63 characters."
            else -> null
        }

    val passwordOk: Boolean get() = passwordProblem == null

    val canStart: Boolean
        get() = supported && !running && network?.testable == true && passwordOk &&
            network?.targets.orEmpty().any { it.bssid in selected }

    /** Connection requests the test will make with the current choices. */
    val requestCount: Int get() = network?.targets.orEmpty().count { it.bssid in selected } * trialsPerTarget

    companion object {
        const val DEFAULT_TRIALS = 3
        val TRIAL_CHOICES = listOf(1, 3, 5)
    }
}

/** What the connection watch has recorded. */
data class WatchUiState(
    val report: WatchReport = WatchAnalyzer.analyze(emptyList()),
    /** Latest events, newest first. */
    val recent: List<WifiEvent> = emptyList(),
    /** Events held in the log altogether. */
    val total: Int = 0,
    /** True while the background service keeps the watch running off screen. */
    val background: Boolean = false,
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // Shared with the background watch service, so both see one network state and one log.
    private val graph = (application as NetworkAnalystApp).graph
    private val connectivity = graph.connectivity
    private val wifi = graph.wifi
    private val coordinator = graph.coordinator
    private val history = graph.history
    private val monitor = graph.monitor
    private val tester = graph.tester

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

    private val _connect = MutableStateFlow(
        ConnectUiState(supported = tester.supported, reportsStage = tester.reportsStage, keepsNormalConnection = tester.keepsNormalConnection),
    )
    val connect: StateFlow<ConnectUiState> = _connect.asStateFlow()

    private val _watch = MutableStateFlow(WatchUiState())
    val watch: StateFlow<WatchUiState> = _watch.asStateFlow()

    private var scanJob: Job? = null
    private var speedJob: Job? = null
    private var devicesJob: Job? = null
    private var connectJob: Job? = null
    private var wifiMonitorJob: Job? = null

    /** True once the user has ticked or unticked an access point, so their choice is kept. */
    private var connectSelectionEdited = false

    init {
        refreshPermissions()
        viewModelScope.launch(Dispatchers.IO) {
            val entries = history.load()
            _speed.update { it.copy(history = entries.asReversed()) }
        }
        viewModelScope.launch {
            combine(monitor.events, graph.watchingInBackground, graph.watchError) { events, background, error -> Triple(events, background, error) }
                .collectLatest { (events, background, error) ->
                    val report = withContext(Dispatchers.Default) { WatchAnalyzer.analyze(events) }
                    _watch.value = WatchUiState(report, events.takeLast(RECENT_EVENTS).asReversed(), events.size, background, error)
                }
        }
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

    val isBusy: Boolean
        get() = scanJob?.isActive == true || speedJob?.isActive == true || devicesJob?.isActive == true || connectJob?.isActive == true

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
                // What the connection watch has seen lately belongs in the same report.
                val watched = listOfNotNull(WatchAnalyzer.recentFinding(monitor.events.value, System.currentTimeMillis()))
                val diagnosis = DiagnosticEngine.diagnose(snapshot, HistoryStore.baseline(entries, key.first), watched)
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
        applyConnectNetworks(TargetPicker.networks(scan.accessPoints, link?.ssid))
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

    // ------------------------------------------------------------------ connect tab

    /** Brings the list of testable networks up to date with a scan, keeping the user's choices. */
    private fun applyConnectNetworks(networks: List<TestNetwork>) {
        _connect.update { state ->
            when {
                // The test is working through the list it started with; leave it alone.
                state.running -> state
                // The phone sees nothing while it is between networks, as it is right after a
                // test. An empty list then is not news; keep what was there.
                networks.isEmpty() && state.networks.isNotEmpty() -> state
                else -> {
                    val chosen = networks.firstOrNull { it.ssid == state.ssid } ?: networks.firstOrNull()
                    val present = chosen?.targets.orEmpty().map { it.bssid }.toSet()
                    val kept = state.selected intersect present
                    val keep = chosen?.ssid == state.ssid && connectSelectionEdited && kept.isNotEmpty()
                    if (!keep) connectSelectionEdited = false
                    state.copy(
                        networks = networks,
                        ssid = chosen?.ssid,
                        selected = if (keep) kept else TargetPicker.defaultSelection(chosen?.targets.orEmpty()),
                    )
                }
            }
        }
    }

    /** Makes sure the Connect tab has access points to offer; called when the tab opens. */
    fun loadConnectTargets() {
        if (_connect.value.running || !_permissions.value.canScanWifi) return
        if (_connect.value.networks.isEmpty()) rescanWifi() else refreshWifi()
    }

    fun selectConnectNetwork(ssid: String) {
        connectSelectionEdited = false
        _connect.update { state ->
            val network = state.networks.firstOrNull { it.ssid == ssid }
            if (state.running || network == null || ssid == state.ssid) {
                state
            } else {
                // A different network has a different password and its own results.
                state.copy(ssid = ssid, selected = TargetPicker.defaultSelection(network.targets), password = "", report = null, error = null)
            }
        }
    }

    fun toggleConnectTarget(bssid: String) {
        connectSelectionEdited = true
        _connect.update { state ->
            if (state.running) state else state.copy(selected = if (bssid in state.selected) state.selected - bssid else state.selected + bssid)
        }
    }

    fun setConnectPassword(password: String) {
        // The field reports its text whenever it appears, not only on edits; an unchanged
        // password must not wipe a message the user has yet to read.
        _connect.update { if (it.password == password) it else it.copy(password = password, error = null) }
    }

    fun setConnectTrials(count: Int) {
        _connect.update { if (it.running) it else it.copy(trialsPerTarget = count.coerceIn(1, MAX_CONNECT_TRIALS)) }
    }

    fun startConnectTest() {
        if (isBusy) return
        val state = _connect.value
        val network = state.network ?: return
        val targets = network.targets.filter { it.bssid in state.selected }
        val total = targets.size * state.trialsPerTarget
        connectJob = viewModelScope.launch {
            _connect.update { it.copy(running = true, error = null, report = null, progress = ConnectTestProgress("Starting", 0, total, emptyList())) }
            try {
                val results = withContext(Dispatchers.Default) {
                    tester.run(targets, network.security, state.password.takeIf { network.needsPassword }, state.trialsPerTarget) { progress ->
                        _connect.update { it.copy(progress = progress) }
                    }
                }
                _connect.update { it.copy(running = false, progress = null, report = ConnectionTestAnalyzer.analyze(results)) }
            } catch (e: ConnectionTestUnavailable) {
                _connect.update { it.copy(running = false, progress = null, error = e.message) }
            } catch (e: CancellationException) {
                // Keep what was learned before the test was stopped.
                val partial = _connect.value.progress?.results.orEmpty()
                _connect.update {
                    it.copy(running = false, progress = null, report = if (partial.any { r -> r.trials.isNotEmpty() }) ConnectionTestAnalyzer.analyze(partial) else null)
                }
                throw e
            }
        }
    }

    fun cancelConnectTest() {
        connectJob?.cancel()
    }

    /** Plain-text version of the connection test and the watch log, for the share sheet. */
    fun connectReportText(): String = ConnectReportText.build(_connect.value.report, monitor.events.value)

    fun clearWatchLog() {
        monitor.clear()
    }

    /**
     * Starts or stops the service that keeps the watch recording while the app is off screen.
     * Must be called while the app is on screen; Android refuses to start it otherwise.
     */
    fun setBackgroundWatch(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled) {
            try {
                WatchService.start(app)
            } catch (e: RuntimeException) {
                graph.watchError.value = "Android would not start background watching: ${e.message ?: e.javaClass.simpleName}"
            }
        } else {
            WatchService.stop(app)
        }
    }

    companion object {
        /** On metered connections each direction of the speed test stops after this much data. */
        const val METERED_BYTE_CAP = 100_000_000L
        private const val RSSI_HISTORY_POINTS = 90
        private const val RECENT_EVENTS = 40
        private const val MAX_CONNECT_TRIALS = 5
    }
}
