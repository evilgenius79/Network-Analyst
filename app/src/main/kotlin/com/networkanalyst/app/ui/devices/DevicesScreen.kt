package com.networkanalyst.app.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.networkanalyst.app.ui.DevicesUiState
import com.networkanalyst.app.ui.PermissionsState
import com.networkanalyst.app.ui.components.Fmt
import com.networkanalyst.app.ui.components.Notice
import com.networkanalyst.app.ui.components.SectionCard
import com.networkanalyst.core.model.DeviceType
import com.networkanalyst.core.model.LanDevice

class DevicesActions(
    val onScan: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRequestPermissions: () -> Unit = {},
)

fun deviceIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.ROUTER -> Icons.Filled.Router
    DeviceType.THIS_DEVICE, DeviceType.PHONE -> Icons.Filled.PhoneAndroid
    DeviceType.COMPUTER -> Icons.Filled.Computer
    DeviceType.TV -> Icons.Filled.Tv
    DeviceType.SPEAKER -> Icons.Filled.Speaker
    DeviceType.PRINTER -> Icons.Filled.Print
    DeviceType.GAME_CONSOLE -> Icons.Filled.SportsEsports
    DeviceType.NAS -> Icons.Filled.Storage
    DeviceType.CAMERA -> Icons.Filled.CameraAlt
    DeviceType.SMART_HOME -> Icons.Filled.Home
    DeviceType.UNKNOWN -> Icons.Filled.DeviceUnknown
}

@Composable
fun DevicesScreen(
    state: DevicesUiState,
    onWifi: Boolean,
    permissions: PermissionsState,
    actions: DevicesActions,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val result = state.result
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                if (result != null) {
                    Text("${result.devices.size} devices on your network", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        (result.subnet ?: "") + if (result.truncated) "  ·  large network, ${result.scannedHosts} addresses around this phone checked" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Devices on your network", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "See what is sharing your Wi-Fi. Every connected device takes a share of the airtime and the internet line.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
                if (state.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = actions.onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                } else {
                    Button(
                        onClick = actions.onScan,
                        enabled = onWifi && permissions.localNetworkGranted,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(if (result == null) "Scan for devices" else "Scan again", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }

        when {
            !onWifi -> item {
                Notice(Icons.Filled.WifiOff, "Connect to Wi-Fi first", "Devices can only be found on a Wi-Fi network you are connected to.", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !permissions.localNetworkGranted -> item {
                Notice(
                    Icons.Filled.Lan,
                    "Allow access to nearby devices",
                    "Android needs your permission before an app may talk to other devices on your network.",
                ) { FilledTonalButton(onClick = actions.onRequestPermissions) { Text("Allow") } }
            }
            state.unavailable -> item {
                Notice(Icons.Filled.Lan, "The scan could not run", "The phone has no usable address on this Wi-Fi network yet. Wait a moment and try again.", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (result != null && result.devices.isNotEmpty()) {
            item {
                SectionCard {
                    result.devices.forEachIndexed { index, device ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DeviceRow(device)
                    }
                }
            }
            item {
                Text(
                    "Names come from what devices announce about themselves. Android does not let apps read hardware (MAC) addresses, so a device that announces nothing shows only its address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: LanDevice) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(42.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    deviceIcon(device.type),
                    contentDescription = device.type.label,
                    tint = if (device.isSelf || device.isGateway) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (device.isSelf || device.isGateway) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val description = listOfNotNull(
                device.type.label.takeIf { device.type != DeviceType.UNKNOWN || device.displayName == device.ip },
                listOfNotNull(device.manufacturer, device.model).joinToString(" ").takeIf { it.isNotBlank() && it != device.displayName },
                device.ip.takeIf { it != device.displayName },
            ).joinToString("  ·  ")
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        device.pingMs?.let {
            Spacer(Modifier.width(8.dp))
            Text(Fmt.ms(it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
