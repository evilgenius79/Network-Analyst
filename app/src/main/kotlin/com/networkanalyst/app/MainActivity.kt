package com.networkanalyst.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkanalyst.app.data.ScanCoordinator
import com.networkanalyst.app.ui.AppScaffold
import com.networkanalyst.app.ui.MainViewModel
import com.networkanalyst.app.ui.ScanUiState
import com.networkanalyst.app.ui.Tab
import com.networkanalyst.app.ui.devices.DevicesActions
import com.networkanalyst.app.ui.devices.DevicesScreen
import com.networkanalyst.app.ui.scan.ScanActions
import com.networkanalyst.app.ui.scan.ScanScreen
import com.networkanalyst.app.ui.speed.SpeedActions
import com.networkanalyst.app.ui.speed.SpeedScreen
import com.networkanalyst.app.ui.theme.NetworkAnalystTheme
import com.networkanalyst.app.ui.wifi.WifiActions
import com.networkanalyst.app.ui.wifi.WifiScreen
import com.networkanalyst.core.model.Transport

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NetworkAnalystTheme { App(viewModel, ::shareReport, ::openLocationSettings) }
        }
    }

    private fun shareReport(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Network Analyst report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share report"))
    }

    private fun openLocationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    }
}

/** Permissions the scan can use on this version of Android. */
private fun runtimePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= ScanCoordinator.API_LOCAL_NETWORK_PERMISSION) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
}.toTypedArray()

@Composable
private fun App(viewModel: MainViewModel, onShare: (String) -> Unit, onOpenLocationSettings: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.SCAN) }
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val wifi by viewModel.wifiState.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val onWifi = connection.transport == Transport.WIFI

    // Permission dialogs. Whatever the answer, re-read the state; the scan copes without them.
    var startScanAfterPermissions by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshPermissions()
        if (startScanAfterPermissions) {
            startScanAfterPermissions = false
            viewModel.startScan()
        }
    }
    val requestPermissions = { permissionLauncher.launch(runtimePermissions()) }

    // The user may have changed permissions or Location in system settings while away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }

    // Signal monitoring only runs while the Wi-Fi tab is in front.
    if (tab == Tab.WIFI) {
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.startWifiMonitor() }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.stopWifiMonitor() }
        DisposableEffect(Unit) {
            viewModel.startWifiMonitor()
            onDispose { viewModel.stopWifiMonitor() }
        }
    }

    // Tests need the screen on: Android only honours the low-latency Wi-Fi mode while it is.
    val busy = scan is ScanUiState.Running || speed.running || devices.scanning
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    AppScaffold(selected = tab, onSelect = { tab = it }) { padding ->
        when (tab) {
            Tab.SCAN -> ScanScreen(
                state = scan,
                connection = connection,
                wifiName = wifi.link?.ssid,
                permissions = permissions,
                options = options,
                actions = ScanActions(
                    onStart = {
                        // Ask once up front for anything missing, then scan with what was granted.
                        val missing = onWifi && (!permissions.locationGranted || !permissions.localNetworkGranted)
                        if (missing) {
                            startScanAfterPermissions = true
                            requestPermissions()
                        } else {
                            viewModel.startScan()
                        }
                    },
                    onCancel = viewModel::cancelScan,
                    onBackToStart = viewModel::clearScan,
                    onShare = { viewModel.reportText()?.let(onShare) },
                    onOptionsChange = viewModel::setOptions,
                    onRequestPermissions = requestPermissions,
                    onOpenLocationSettings = onOpenLocationSettings,
                ),
                contentPadding = padding,
            )
            Tab.WIFI -> WifiScreen(
                state = wifi,
                onWifi = onWifi,
                permissions = permissions,
                actions = WifiActions(
                    onRescan = viewModel::rescanWifi,
                    onRequestPermissions = requestPermissions,
                    onOpenLocationSettings = onOpenLocationSettings,
                ),
                contentPadding = padding,
            )
            Tab.SPEED -> SpeedScreen(
                state = speed,
                connected = connection.transport != Transport.NONE,
                metered = connection.isMetered,
                actions = SpeedActions(
                    onStart = viewModel::startSpeedTest,
                    onCancel = viewModel::cancelSpeedTest,
                    onClearHistory = viewModel::clearHistory,
                ),
                contentPadding = padding,
            )
            Tab.DEVICES -> DevicesScreen(
                state = devices,
                onWifi = onWifi,
                permissions = permissions,
                actions = DevicesActions(
                    onScan = viewModel::startDeviceScan,
                    onCancel = viewModel::cancelDeviceScan,
                    onRequestPermissions = requestPermissions,
                ),
                contentPadding = padding,
            )
        }
    }
}
