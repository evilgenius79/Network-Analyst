package com.networkanalyst.app.ui

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import com.networkanalyst.app.data.ScanOptions
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
}
