package com.networkanalyst.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.networkanalyst.app.data.ScanOptions
import com.networkanalyst.app.ui.connect.ConnectActions
import com.networkanalyst.app.ui.connect.ConnectScreen
import com.networkanalyst.app.ui.devices.DevicesActions
import com.networkanalyst.app.ui.devices.DevicesScreen
import com.networkanalyst.app.ui.scan.ScanActions
import com.networkanalyst.app.ui.scan.ScanScreen
import com.networkanalyst.app.ui.speed.SpeedActions
import com.networkanalyst.app.ui.speed.SpeedScreen
import com.networkanalyst.app.ui.theme.NetworkAnalystTheme
import com.networkanalyst.app.ui.wifi.WifiActions
import com.networkanalyst.app.ui.wifi.WifiScreen
import com.networkanalyst.core.model.ConnectionInfo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w412dp-h915dp-420dpi"

/** An unrealistically tall screen so a whole scrolling report fits in one image. */
private const val TALL = "w412dp-h3400dp-420dpi"

/**
 * Renders every screen with sample data and writes the images to build/screenshots.
 * Run with `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ScreenshotTest {
    /**
     * A still image has no use for motion, and an indeterminate spinner never comes to rest:
     * the capture then waits minutes for a screen that will not settle. With the system's
     * animation scale at zero, Compose ends repeating animations at once.
     */
    @Before
    fun stillAnimations() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private fun shot(name: String, tab: Tab, dark: Boolean = true, content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
        captureRoboImage("build/screenshots/$name.png") {
            NetworkAnalystTheme(darkTheme = dark) { AppScaffold(selected = tab, onSelect = {}, content = content) }
        }
    }

    @Composable
    private fun Scan(state: ScanUiState, padding: androidx.compose.foundation.layout.PaddingValues, connection: ConnectionInfo = SampleData.wifiConnection, permissions: PermissionsState = SampleData.permissionsGranted) {
        ScanScreen(state, connection, "Home", permissions, ScanOptions(), ScanActions(), padding)
    }

    @Test
    fun scanIdle() = shot("01_scan_idle", Tab.SCAN) { Scan(ScanUiState.Idle, it) }

    @Test
    fun scanIdleNeedsPermissions() = shot("02_scan_idle_permissions", Tab.SCAN) {
        Scan(ScanUiState.Idle, it, permissions = PermissionsState(locationGranted = false, locationEnabled = true, localNetworkGranted = false))
    }

    @Test
    fun scanRunning() = shot("03_scan_running", Tab.SCAN) { Scan(SampleData.runningSpeed, it) }

    @Test
    @Config(qualifiers = TALL)
    fun scanResultTroubled() = shot("04_scan_result_problems", Tab.SCAN) { Scan(SampleData.troubled, it) }

    @Test
    fun scanResultTroubledFirstScreen() = shot("05_scan_result_problems_top", Tab.SCAN) { Scan(SampleData.troubled, it) }

    @Test
    @Config(qualifiers = TALL)
    fun scanResultHealthy() = shot("06_scan_result_healthy", Tab.SCAN) { Scan(SampleData.healthy, it) }

    @Test
    @Config(qualifiers = TALL)
    fun scanResultLight() = shot("07_scan_result_light", Tab.SCAN, dark = false) { Scan(SampleData.troubled, it) }

    @Test
    @Config(qualifiers = TALL)
    fun wifi() = shot("08_wifi", Tab.WIFI) { WifiScreen(SampleData.wifiState, true, SampleData.permissionsGranted, WifiActions(), it) }

    @Test
    fun wifiNoPermission() = shot("09_wifi_no_permission", Tab.WIFI) {
        WifiScreen(SampleData.wifiState.copy(scan = null, bands = emptyList()), true, PermissionsState(), WifiActions(), it)
    }

    @Test
    fun speedLive() = shot("10_speed_live", Tab.SPEED) { SpeedScreen(SampleData.speedLive, connected = true, metered = false, SpeedActions(), it) }

    @Test
    @Config(qualifiers = TALL)
    fun speedResult() = shot("11_speed_result", Tab.SPEED) { SpeedScreen(SampleData.speedResult, connected = true, metered = true, SpeedActions(), it) }

    @Test
    fun speedEmpty() = shot("12_speed_empty", Tab.SPEED, dark = false) { SpeedScreen(SpeedUiState(), connected = true, metered = false, SpeedActions(), it) }

    @Test
    fun devices() = shot("13_devices", Tab.DEVICES) { DevicesScreen(SampleData.devices, true, SampleData.permissionsGranted, DevicesActions(), it) }

    @Test
    fun devicesEmpty() = shot("14_devices_empty", Tab.DEVICES) { DevicesScreen(DevicesUiState(), true, SampleData.permissionsGranted, DevicesActions(), it) }

    @Composable
    private fun Connect(state: ConnectUiState, watch: WatchUiState, padding: androidx.compose.foundation.layout.PaddingValues, permissions: PermissionsState = SampleData.permissionsGranted) {
        ConnectScreen(state, watch, permissions, scanning = false, ConnectActions(), padding)
    }

    @Test
    fun connectSetupFirstScreen() = shot("15_connect_setup_top", Tab.CONNECT) { Connect(SampleData.connectSetup, SampleData.watchQuiet, it) }

    @Test
    @Config(qualifiers = TALL)
    fun connectSetup() = shot("16_connect_setup", Tab.CONNECT) { Connect(SampleData.connectSetup, SampleData.watchQuiet, it) }

    @Test
    fun connectRunning() = shot("17_connect_running", Tab.CONNECT) { Connect(SampleData.connectRunning, SampleData.watchQuiet, it) }

    @Test
    @Config(qualifiers = TALL)
    fun connectResult() = shot("18_connect_result", Tab.CONNECT) { Connect(SampleData.connectResult, SampleData.watchBusy, it) }

    @Test
    fun connectResultFirstScreen() = shot("22_connect_result_top", Tab.CONNECT) { Connect(SampleData.connectResult, SampleData.watchBusy, it) }

    @Test
    fun connectWatch() = shot("23_connect_watch", Tab.CONNECT) {
        // No list of access points yet, so the watch is what the screen has to show.
        Connect(ConnectUiState(), SampleData.watchBusy, it)
    }

    @Test
    @Config(qualifiers = TALL)
    fun connectResultLight() = shot("19_connect_result_light", Tab.CONNECT, dark = false) { Connect(SampleData.connectResult, SampleData.watchBusy, it) }

    @Test
    @Config(qualifiers = TALL)
    fun connectAllGood() = shot("20_connect_all_good", Tab.CONNECT) { Connect(SampleData.connectAllGood, SampleData.watchQuiet, it) }

    @Test
    fun connectNoPermission() = shot("21_connect_no_permission", Tab.CONNECT) {
        Connect(ConnectUiState(), SampleData.watchQuiet, it, permissions = PermissionsState())
    }
}
