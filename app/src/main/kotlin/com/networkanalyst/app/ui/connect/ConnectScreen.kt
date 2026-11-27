package com.networkanalyst.app.ui.connect

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.networkanalyst.app.data.ConnectTestProgress
import com.networkanalyst.app.ui.ConnectUiState
import com.networkanalyst.app.ui.PermissionsState
import com.networkanalyst.app.ui.WatchUiState
import com.networkanalyst.app.ui.components.FindingCard
import com.networkanalyst.app.ui.components.Fmt
import com.networkanalyst.app.ui.components.Notice
import com.networkanalyst.app.ui.components.SectionCard
import com.networkanalyst.app.ui.components.StatTile
import com.networkanalyst.app.ui.theme.LocalStatusColors
import com.networkanalyst.core.connect.ConnectionTestReport
import com.networkanalyst.core.connect.TargetPicker
import com.networkanalyst.core.connect.TargetResult
import com.networkanalyst.core.connect.TestTarget
import com.networkanalyst.core.connect.WatchLogText
import com.networkanalyst.core.connect.WifiEvent
import java.util.Locale

class ConnectActions(
    val onSelectNetwork: (String) -> Unit = {},
    val onToggleTarget: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onTrialsChange: (Int) -> Unit = {},
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRescan: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onBackgroundWatchChange: (Boolean) -> Unit = {},
    val onClearLog: () -> Unit = {},
    val onRequestPermissions: () -> Unit = {},
    val onOpenLocationSettings: () -> Unit = {},
)

/** Networks offered as chips; a scan in a block of flats can return dozens. */
private const val MAX_NETWORK_CHIPS = 8

@Composable
fun ConnectScreen(
    state: ConnectUiState,
    watch: WatchUiState,
    permissions: PermissionsState,
    /** A Wi-Fi scan is under way, so the list of access points is about to change. */
    scanning: Boolean,
    actions: ConnectActions,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val report = state.report
    // Once there is a result it leads the screen, and the form moves below it for a re-run.
    val showResults = report != null && !state.running
    val canSetUp = state.supported && permissions.canScanWifi && state.networks.isNotEmpty()
    LazyColumn(
        // The password field must stay above the keyboard; the insets already used for the
        // bars are marked as consumed so they are not added a second time.
        modifier = Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding).imePadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (report != null && showResults) {
            item { ResultsCard(report, actions.onShare) }
            items(report.findings, key = { "test-${it.id}" }) { finding ->
                FindingCard(finding, initiallyExpanded = finding == report.findings.first())
            }
        }

        if (!showResults || canSetUp) {
            item {
                SectionCard(title = if (showResults) "Run the test again" else null) {
                    if (!showResults) {
                        Text("Why won't it connect?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Asks each access point of a network to take this phone, several times over, and shows which ones refuse and at which step.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val progress = state.progress
                    when {
                        state.running && progress != null -> RunningTest(state, progress, actions)
                        canSetUp -> TestSetup(state, scanning, actions, topSpace = !showResults)
                    }
                }
            }
        }

        when {
            !state.supported -> item {
                Notice(
                    Icons.Filled.PhoneAndroid,
                    "Needs Android 10 or newer",
                    "Older versions of Android do not let an app ask for a connection to a chosen access point. The connection watch below still works.",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            !permissions.locationGranted -> item {
                Notice(
                    Icons.Filled.LocationOn,
                    "Allow location to list access points",
                    "Android only lists nearby Wi-Fi access points for apps with the Location permission (set to Precise). Your location is not read or stored.",
                ) { FilledTonalButton(onClick = actions.onRequestPermissions) { Text("Allow") } }
            }
            !permissions.locationEnabled -> item {
                Notice(
                    Icons.Filled.LocationOn,
                    "Turn on Location to list access points",
                    "Android hides nearby Wi-Fi access points while Location is switched off in the phone's settings.",
                ) { FilledTonalButton(onClick = actions.onOpenLocationSettings) { Text("Open settings") } }
            }
            state.networks.isEmpty() && !state.running -> item {
                Notice(
                    Icons.Filled.WifiFind,
                    if (scanning) "Looking for networks" else "No networks found yet",
                    "The test needs a list of the access points in range. Make sure Wi-Fi is on, then scan.",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                ) { FilledTonalButton(onClick = actions.onRescan, enabled = !scanning) { Text(if (scanning) "Scanning" else "Scan") } }
            }
        }

        item { WatchCard(watch, actions) }
        items(watch.report.findings, key = { "watch-${it.id}" }) { finding -> FindingCard(finding) }
        if (watch.recent.isNotEmpty()) {
            item { RecentEvents(watch, actions) }
        }
        item {
            Text(
                "What Android lets an app see differs between phones. Where a phone does not report the individual steps, a failure can only be placed roughly, and the results say so.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------- test setup

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
private fun TestSetup(state: ConnectUiState, scanning: Boolean, actions: ConnectActions, topSpace: Boolean) {
    val network = state.network
    if (topSpace) Spacer(Modifier.height(16.dp))
    Label("Network")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.networks.take(MAX_NETWORK_CHIPS).forEach { candidate ->
            FilterChip(
                selected = candidate.ssid == state.ssid,
                onClick = { actions.onSelectNetwork(candidate.ssid) },
                label = { Text(candidate.ssid, maxLines = 1) },
                leadingIcon = if (candidate.connected) {
                    { Icon(Icons.Filled.Wifi, contentDescription = "Connected", modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
            )
        }
    }
    if (network == null) return

    if (!network.testable) {
        Spacer(Modifier.height(8.dp))
        Text(
            "\"${network.ssid}\" uses ${network.security.label}. Android does not let an app request a connection to networks of that kind, so it cannot be tested here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    if (network.needsPassword) {
        Spacer(Modifier.height(8.dp))
        PasswordField(state, actions.onPasswordChange)
    }

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Access points to test", Modifier.weight(1f))
        TextButton(onClick = actions.onRescan, enabled = !scanning) { Text(if (scanning) "Scanning" else "Rescan") }
    }
    network.targets.forEach { target ->
        TargetRow(target, checked = target.bssid in state.selected, onToggle = { actions.onToggleTarget(target.bssid) })
    }
    if (network.targets.size == 1) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Only one access point radio of this network is in range here. On a mesh, run the test from different rooms to reach the other units.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))
    Label("Attempts per access point")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectUiState.TRIAL_CHOICES.forEach { count ->
            FilterChip(selected = state.trialsPerTarget == count, onClick = { actions.onTrialsChange(count) }, label = { Text(count.toString()) })
        }
    }

    state.error?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = actions.onStart,
        enabled = state.canStart,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text(if (state.report == null) "Start connection test" else "Test again", style = MaterialTheme.typography.titleMedium) }
    Spacer(Modifier.height(8.dp))
    Text(
        buildString {
            if (state.requestCount > 0) {
                append("${state.requestCount} connection request${if (state.requestCount == 1) "" else "s"}. ")
                append("Each takes a few seconds when it connects and can take a couple of minutes when it fails. ")
            }
            append(
                if (state.keepsNormalConnection) {
                    "This phone can hold the test connection next to its normal one, so Wi-Fi may stay up meanwhile. "
                } else {
                    "Wi-Fi drops off the network while the test runs and normally rejoins by itself afterwards. "
                },
            )
            append("Android asks once for permission to connect. Keep the app open until it finishes. ")
            append("The router may list the test as a separate device, because Android normally gives it a randomized hardware address.")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PasswordField(state: ConnectUiState, onChange: (String) -> Unit) {
    // A fresh field for each network. The text lives in memory only (here and in the view
    // model), never in saved state: the password should not be written anywhere.
    val field = remember(state.ssid) { TextFieldState(state.password) }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { onChange(it) } }
    var visible by remember { mutableStateOf(false) }
    // An empty field is not an error yet; the start button simply stays off.
    val problem = state.passwordProblem.takeIf { state.password.isNotEmpty() }
    OutlinedSecureTextField(
        state = field,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Wi-Fi password") },
        isError = problem != null,
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
        supportingText = {
            Text(problem ?: "Used for this test only. It is not stored.")
        },
    )
}

@Composable
private fun TargetRow(target: TestTarget, checked: Boolean, onToggle: () -> Unit) {
    val weak = target.rssiDbm < TargetPicker.USABLE_RSSI_DBM
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(target.radioLabel, style = MaterialTheme.typography.bodyLarge)
            Text(target.bssid, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "${target.rssiDbm} dBm" + if (weak) " · weak" else "",
            style = MaterialTheme.typography.labelLarge,
            color = if (weak) LocalStatusColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------- running

@Composable
private fun RunningTest(state: ConnectUiState, progress: ConnectTestProgress, actions: ConnectActions) {
    Spacer(Modifier.height(16.dp))
    Label("Testing \"${state.ssid.orEmpty()}\"  ·  ${progress.done} of ${progress.total} requests done")
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(progress.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val started = progress.results.filter { it.trials.isNotEmpty() }
    if (started.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        started.forEachIndexed { index, result ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ResultRow(result)
        }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = actions.onCancel, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
}

// ---------------------------------------------------------------------------- results

@Composable
private fun ResultsCard(report: ConnectionTestReport, onShare: () -> Unit) {
    val tested = report.results.filter { it.trials.isNotEmpty() }
    SectionCard(title = "Test result" + (tested.firstOrNull()?.target?.ssid?.let { " for \"$it\"" } ?: "")) {
        Text(report.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (tested.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            tested.forEachIndexed { index, result ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ResultRow(result)
            }
        }
        report.stoppedBy?.let {
            Spacer(Modifier.height(6.dp))
            Text("The test stopped early. ${it.explanation}", style = MaterialTheme.typography.bodyMedium, color = LocalStatusColors.current.warning)
        }
        val skipped = report.results.size - tested.size
        if (skipped > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "$skipped access point radio${if (skipped == 1) " was" else "s were"} not reached before the test stopped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("Share results and log") }
    }
}

/** How one access point radio did: a verdict icon, the count, and what went wrong if anything. */
@Composable
private fun ResultRow(result: TargetResult) {
    val status = LocalStatusColors.current
    val (icon, tint, verdict) = when {
        result.dead -> Triple(Icons.Filled.Cancel, status.critical, "Never connected")
        result.clean -> Triple(Icons.Filled.CheckCircle, status.good, "Connected every time")
        result.failures == 0 -> Triple(Icons.Filled.Warning, status.warning, "Connected, but only after retries")
        else -> Triple(Icons.Filled.Warning, status.warning, "Connected some of the time")
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        StatusIcon(icon, tint, verdict)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${result.target.radioLabel}  ·  ${result.target.rssiDbm} dBm", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(verdict, style = MaterialTheme.typography.bodyMedium, color = tint)
            val details = resultDetails(result)
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(result.target.bssid, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text("${result.successes}/${result.trials.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color, description: String) {
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.padding(top = 2.dp).size(22.dp))
}

/** "typically 1.8 s to connect · 2 silent retries (2 refused when joining) · Refused when joining ×1" */
internal fun resultDetails(result: TargetResult): String = listOfNotNull(
    result.medianConnectMs?.let { "typically ${String.format(Locale.US, "%.1f", it / 1000)} s to connect" },
    result.hiddenFailureSummary,
    result.failureReasons.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.first.label} ×${it.second}" },
).joinToString("  ·  ")

// ---------------------------------------------------------------------------- watch

@Composable
private fun WatchCard(watch: WatchUiState, actions: ConnectActions) {
    val summary = watch.report.summary
    val attempts = summary.attempts.filter { !it.roam }
    val failed = attempts.count { !it.success }
    val status = LocalStatusColors.current
    SectionCard(title = "Connection watch") {
        Text(watch.report.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Records each step this phone's own Wi-Fi takes: every attempt to join, where a failed one stopped, and every drop. Leave it on and come back after the next \"Connection failed\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            StatTile("Attempts", attempts.size.toString(), Modifier.weight(1f))
            StatTile("Failed", failed.toString(), Modifier.weight(1f), valueColor = if (failed > 0) status.critical else MaterialTheme.colorScheme.onSurface)
            StatTile("Drops", summary.drops.size.toString(), Modifier.weight(1f), valueColor = if (summary.drops.isNotEmpty()) status.warning else MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = watch.background, role = Role.Switch, onValueChange = actions.onBackgroundWatchChange)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Keep watching in the background", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (watch.background) {
                        "On. Recording continues while the app is closed; a notification shows while it runs."
                    } else {
                        "Off. Android only reports Wi-Fi steps to this app while it is open on screen."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = watch.background, onCheckedChange = null)
        }
        watch.error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun RecentEvents(watch: WatchUiState, actions: ConnectActions) {
    SectionCard(
        title = "Recent Wi-Fi activity",
        trailing = {
            Text(
                if (watch.total > watch.recent.size) "latest ${watch.recent.size} of ${watch.total}" else "${watch.total} recorded",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        watch.recent.forEachIndexed { index, event ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EventRow(event)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = actions.onShare, modifier = Modifier.weight(1f)) { Text("Share log") }
            OutlinedButton(onClick = actions.onClearLog, modifier = Modifier.weight(1f)) { Text("Clear log") }
        }
    }
}

@Composable
private fun EventRow(event: WifiEvent) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(WatchLogText.plain(event), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                Fmt.logTime(event.timeMs),
                event.ssid,
                event.bssid,
                event.rssiDbm?.let { "$it dBm" },
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
