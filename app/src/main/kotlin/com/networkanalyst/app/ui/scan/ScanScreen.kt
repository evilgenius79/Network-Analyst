package com.networkanalyst.app.ui.scan

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.networkanalyst.app.data.ScanOptions
import com.networkanalyst.app.data.ScanProgress
import com.networkanalyst.app.data.ScanStep
import com.networkanalyst.app.data.StepStatus
import com.networkanalyst.app.ui.PermissionsState
import com.networkanalyst.app.ui.ScanUiState
import com.networkanalyst.app.ui.components.DetailRow
import com.networkanalyst.app.ui.components.FindingCard
import com.networkanalyst.app.ui.components.Fmt
import com.networkanalyst.app.ui.components.HealthRing
import com.networkanalyst.app.ui.components.Notice
import com.networkanalyst.app.ui.components.SectionCard
import com.networkanalyst.app.ui.components.Sparkline
import com.networkanalyst.app.ui.components.SpeedGauge
import com.networkanalyst.app.ui.components.StatTile
import com.networkanalyst.app.ui.theme.LocalStatusColors
import com.networkanalyst.core.analysis.Diagnosis
import com.networkanalyst.core.analysis.Severity
import com.networkanalyst.core.analysis.UseCaseVerdict
import com.networkanalyst.core.model.ConnectionInfo
import com.networkanalyst.core.model.NetworkSnapshot
import com.networkanalyst.core.model.Transport
import com.networkanalyst.core.speed.BufferbloatGrade
import com.networkanalyst.core.speed.SpeedPhase
import com.networkanalyst.core.wifi.WifiChannels

/** What the screen can ask the app to do. */
class ScanActions(
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    /** Leave the report and return to the start view with its options. */
    val onBackToStart: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onOptionsChange: (ScanOptions) -> Unit = {},
    val onRequestPermissions: () -> Unit = {},
    val onOpenLocationSettings: () -> Unit = {},
)

@Composable
fun ScanScreen(
    state: ScanUiState,
    connection: ConnectionInfo,
    wifiName: String?,
    permissions: PermissionsState,
    options: ScanOptions,
    actions: ScanActions,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    when (state) {
        ScanUiState.Idle -> IdleContent(connection, wifiName, permissions, options, actions, contentPadding)
        is ScanUiState.Running -> RunningContent(state.progress, actions, contentPadding)
        is ScanUiState.Done -> ResultContent(state.snapshot, state.diagnosis, actions, contentPadding)
    }
}

private val screenPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

@Composable
private fun ScreenColumn(contentPadding: PaddingValues, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = screenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

// ---------------------------------------------------------------------- idle

@Composable
private fun IdleContent(
    connection: ConnectionInfo,
    wifiName: String?,
    permissions: PermissionsState,
    options: ScanOptions,
    actions: ScanActions,
    contentPadding: PaddingValues,
) {
    val onWifi = connection.transport == Transport.WIFI
    ScreenColumn(contentPadding) {
        item {
            SectionCard {
                Text("Find out what is slowing your network", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "A full scan checks the Wi-Fi signal and channel, the link to your router, the internet line, DNS, speed and lag under load, then tells you what to fix first.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                ConnectionLine(connection, wifiName)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = actions.onStart,
                    enabled = connection.transport != Transport.NONE,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Run full scan", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Takes about a minute. Keep the app open and the phone where you normally use it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (onWifi && !permissions.locationGranted) {
            item {
                Notice(
                    Icons.Filled.LocationOn,
                    "Allow location to analyse Wi-Fi channels",
                    "Android only shows nearby Wi-Fi networks to apps with the Location permission. It is used to see which channels your neighbours occupy; your location is not read or stored.",
                ) { FilledTonalButton(onClick = actions.onRequestPermissions) { Text("Allow") } }
            }
        } else if (onWifi && !permissions.locationEnabled) {
            item {
                Notice(
                    Icons.Filled.LocationOn,
                    "Turn on Location for channel analysis",
                    "Android hides nearby Wi-Fi networks while Location is switched off in the phone's settings.",
                ) { FilledTonalButton(onClick = actions.onOpenLocationSettings) { Text("Open settings") } }
            }
        }
        if (onWifi && !permissions.localNetworkGranted) {
            item {
                Notice(
                    Icons.Filled.Lan,
                    "Allow access to nearby devices",
                    "Needed to reach your router and count the devices sharing your network. Without it only the internet side can be tested.",
                ) { FilledTonalButton(onClick = actions.onRequestPermissions) { Text("Allow") } }
            }
        }

        item {
            SectionCard("Include in the scan") {
                OptionRow(
                    title = "Speed test",
                    subtitle = if (connection.isMetered) {
                        "Uses up to about 200 MB on this metered connection."
                    } else {
                        "Measures download, upload and lag under load. Uses a lot of data on fast lines."
                    },
                    checked = options.speedTest,
                    onChange = { actions.onOptionsChange(options.copy(speedTest = it)) },
                )
                OptionRow(
                    title = "Device scan",
                    subtitle = "Finds the devices sharing your Wi-Fi network.",
                    checked = options.deviceScan,
                    enabled = onWifi,
                    onChange = { actions.onOptionsChange(options.copy(deviceScan = it)) },
                )
            }
        }
    }
}

@Composable
private fun ConnectionLine(connection: ConnectionInfo, wifiName: String?) {
    val (icon, text) = when (connection.transport) {
        Transport.WIFI -> Icons.Filled.Wifi to ("Connected to " + (wifiName ?: "Wi-Fi"))
        Transport.CELLULAR -> Icons.Filled.SignalCellularAlt to "On mobile data"
        Transport.ETHERNET -> Icons.Filled.Lan to "Connected by cable"
        Transport.OTHER -> Icons.Filled.NetworkCheck to "Connected"
        Transport.NONE -> Icons.Filled.WifiOff to "Not connected to any network"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text + if (connection.isVpn) " (VPN on)" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

// ---------------------------------------------------------------------- running

@Composable
private fun RunningContent(progress: ScanProgress, actions: ScanActions, contentPadding: PaddingValues) {
    ScreenColumn(contentPadding) {
        item {
            SectionCard {
                Text("Scanning", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    progress.message.ifBlank { "Starting" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                ScanStep.entries.forEach { step -> StepRow(step, progress.steps[step] ?: StepStatus.PENDING) }
            }
        }
        progress.speed?.let { live ->
            if (progress.steps[ScanStep.SPEED] == StepStatus.RUNNING) {
                item {
                    SectionCard {
                        val label = when (live.phase) {
                            SpeedPhase.LATENCY -> "Measuring latency" + (live.latencyMs?.let { "  ${Fmt.ms(it)}" } ?: "")
                            SpeedPhase.DOWNLOAD -> "Download"
                            SpeedPhase.UPLOAD -> "Upload"
                            SpeedPhase.DONE -> "Finishing"
                        }
                        SpeedGauge(live.currentMbps, label)
                        if (live.series.size >= 2) {
                            Spacer(Modifier.height(8.dp))
                            Sparkline(live.series, Modifier.fillMaxWidth().height(36.dp))
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = actions.onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun StepRow(step: ScanStep, status: StepStatus) {
    val statusColors = LocalStatusColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
            StepStatus.DONE -> Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = statusColors.good, modifier = Modifier.size(20.dp))
            StepStatus.SKIPPED -> Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Skipped", tint = statusColors.neutral, modifier = Modifier.size(20.dp))
            StepStatus.PENDING -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = "Waiting", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            step.title + if (status == StepStatus.SKIPPED) " (skipped)" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = if (status == StepStatus.PENDING || status == StepStatus.SKIPPED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (status == StepStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ---------------------------------------------------------------------- result

@Composable
private fun ResultContent(snapshot: NetworkSnapshot, diagnosis: Diagnosis, actions: ScanActions, contentPadding: PaddingValues) {
    val problems = diagnosis.findings.filter { it.severity == Severity.CRITICAL || it.severity == Severity.WARNING }
    val notes = diagnosis.findings.filter { it.severity == Severity.INFO }
    val healthy = diagnosis.findings.filter { it.severity == Severity.GOOD }
    ScreenColumn(contentPadding) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HealthRing(diagnosis.healthScore)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(diagnosis.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(diagnosis.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (snapshot.speed?.downloadMbps != null || snapshot.speed?.uploadMbps != null) {
            item { SpeedSummary(snapshot) }
        }

        if (diagnosis.useCases.isNotEmpty()) {
            item {
                SectionCard("What this connection is good for") {
                    diagnosis.useCases.forEach { UseCaseRow(it) }
                }
            }
        }

        if (problems.isNotEmpty()) {
            item { SectionHeader("Fix these first", "${problems.size}") }
            items(problems, key = { it.id }) { finding -> FindingCard(finding, initiallyExpanded = finding == problems.first()) }
        }
        if (notes.isNotEmpty()) {
            item { SectionHeader("Worth knowing", "${notes.size}") }
            items(notes, key = { it.id }) { finding -> FindingCard(finding) }
        }
        if (healthy.isNotEmpty()) {
            item { SectionHeader("Checked and fine", "${healthy.size}") }
            items(healthy, key = { it.id }) { finding -> FindingCard(finding) }
        }

        item { Measurements(snapshot) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = actions.onStart, modifier = Modifier.weight(1f)) { Text("Scan again") }
                FilledTonalButton(onClick = actions.onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share report")
                }
            }
        }
        item {
            TextButton(onClick = actions.onBackToStart, modifier = Modifier.fillMaxWidth()) { Text("Change scan options") }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: String) {
    Row(Modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(count, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun SpeedSummary(snapshot: NetworkSnapshot) {
    val speed = snapshot.speed ?: return
    val colors = LocalStatusColors.current
    SectionCard {
        Row(Modifier.fillMaxWidth()) {
            StatTile("Download", Fmt.speed(speed.downloadMbps), Modifier.weight(1f))
            StatTile("Upload", Fmt.speed(speed.uploadMbps), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            StatTile("Latency", Fmt.ms(speed.idleLatencyMs), Modifier.weight(1f), caption = speed.idleJitterMs?.let { "jitter ${Fmt.ms(it)}" })
            val added = speed.bufferbloatMs
            if (added != null) {
                val grade = BufferbloatGrade.of(added)
                StatTile(
                    "Lag under load", "Grade ${grade.label}", Modifier.weight(1f),
                    valueColor = when {
                        added >= 200 -> colors.critical
                        added >= 60 -> colors.warning
                        else -> colors.good
                    },
                    caption = "+${Fmt.ms(added)}",
                )
            } else {
                StatTile("Lag under load", "–", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun UseCaseRow(verdict: UseCaseVerdict) {
    val color = LocalStatusColors.current.of(verdict.rating)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(verdict.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(verdict.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f)) {
            Text(verdict.rating.label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun Measurements(snapshot: NetworkSnapshot) {
    SectionCard("Measurements") {
        val connection = snapshot.connection
        DetailRow("Connection", connection.transport.label + if (connection.isVpn) " via VPN" else "")
        snapshot.wifiLink?.let { link ->
            val channel = WifiChannels.channelOf(link.frequencyMhz)
            DetailRow("Wi-Fi network", link.ssid ?: "(name hidden without location)")
            DetailRow(
                "Band and channel",
                WifiChannels.bandOf(link.frequencyMhz).label + (channel?.let { ", channel $it" } ?: "") + (link.channelWidthMhz?.let { ", $it MHz" } ?: ""),
            )
            DetailRow("Signal", "${link.rssiDbm} dBm")
            DetailRow("Wi-Fi standard", link.standard.label)
            val rates = listOfNotNull(link.rxLinkSpeedMbps?.let { "$it Mbps down" }, link.txLinkSpeedMbps?.let { "$it Mbps up" })
            if (rates.isNotEmpty()) DetailRow("Link rate", rates.joinToString(", "))
        }
        connection.ipv4Addresses.firstOrNull()?.let { DetailRow("This phone", it) }
        connection.gatewayV4?.let { DetailRow("Router", it) }
        snapshot.gatewayPing?.let { ping ->
            DetailRow("Ping to router", if (ping.reachable) "${Fmt.ms(ping.medianMs)}, ${Fmt.percent(ping.lossPercent)} loss" else "no reply")
        }
        snapshot.internetPings.forEach { ping ->
            DetailRow("Ping ${ping.label.substringBefore(" (")}", if (ping.reachable) "${Fmt.ms(ping.medianMs)}, jitter ${Fmt.ms(ping.jitterMs)}, ${Fmt.percent(ping.lossPercent)} loss" else "no reply")
        }
        snapshot.dns?.servers?.forEach { server ->
            DetailRow("DNS ${server.name}", if (server.usable) "${Fmt.ms(server.medianMs)}" + (if (server.failures > 0) ", ${server.failures}/${server.queries} failed" else "") else "no answer")
        }
        snapshot.gateway?.let { gateway ->
            listOfNotNull(gateway.manufacturer, gateway.modelName).joinToString(" ").takeIf { it.isNotBlank() }?.let { DetailRow("Router model", it) }
            gateway.externalIp?.let { DetailRow("Router's public address", it) }
        }
        snapshot.speed?.let { speed ->
            speed.serverLocation?.let { DetailRow("Speed test server", it) }
            if (speed.bytesDownloaded + speed.bytesUploaded > 0) DetailRow("Data used by test", Fmt.bytes(speed.bytesDownloaded + speed.bytesUploaded))
        }
        snapshot.devices?.let { DetailRow("Devices found", it.size.toString()) }
        DetailRow("Scanned", Fmt.dateTime(snapshot.timestampMs))
    }
}
