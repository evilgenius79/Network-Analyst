package com.networkanalyst.app.ui.wifi

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.networkanalyst.app.ui.PermissionsState
import com.networkanalyst.app.ui.WifiUiState
import com.networkanalyst.app.ui.components.DetailRow
import com.networkanalyst.app.ui.components.Meter
import com.networkanalyst.app.ui.components.Notice
import com.networkanalyst.app.ui.components.SectionCard
import com.networkanalyst.app.ui.components.Sparkline
import com.networkanalyst.app.ui.theme.LocalStatusColors
import com.networkanalyst.app.ui.theme.StatusColors
import com.networkanalyst.core.model.AccessPoint
import com.networkanalyst.core.model.ScanUnavailableReason
import com.networkanalyst.core.model.WifiLink
import com.networkanalyst.core.model.WifiSecurity
import com.networkanalyst.core.wifi.BandAnalysis
import com.networkanalyst.core.wifi.WifiChannels

class WifiActions(
    val onRescan: () -> Unit = {},
    val onRequestPermissions: () -> Unit = {},
    val onOpenLocationSettings: () -> Unit = {},
)

/** Words for a signal level, matching the thresholds the analysis uses. */
fun signalQuality(rssiDbm: Int): String = when {
    rssiDbm >= -55 -> "Excellent"
    rssiDbm >= -67 -> "Good"
    rssiDbm >= -75 -> "Weak"
    else -> "Very weak"
}

fun signalColor(rssiDbm: Int, colors: StatusColors): Color = when {
    rssiDbm >= -67 -> colors.good
    rssiDbm >= -75 -> colors.warning
    else -> colors.critical
}

/** Maps -95..-35 dBm onto 0..1 for meters. */
fun signalFraction(rssiDbm: Int): Float = ((rssiDbm + 95) / 60f).coerceIn(0f, 1f)

@Composable
fun WifiScreen(
    state: WifiUiState,
    onWifi: Boolean,
    permissions: PermissionsState,
    actions: WifiActions,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val accessPoints = state.scan?.accessPoints.orEmpty().sortedByDescending { it.rssiDbm }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!onWifi || state.link == null) {
            item {
                Notice(Icons.Filled.WifiOff, "Not connected to Wi-Fi", "Join a Wi-Fi network to see its signal, speed and channel.", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            item { LinkCard(state.link, state.rssiHistory) }
        }

        when {
            !permissions.locationGranted -> item {
                Notice(
                    Icons.Filled.LocationOn,
                    "Allow location to see nearby networks",
                    "Android only lists nearby Wi-Fi networks for apps with the Location permission (set to Precise). It is used to show which channels are crowded; your location is not read or stored.",
                ) { FilledTonalButton(onClick = actions.onRequestPermissions) { Text("Allow") } }
            }
            !permissions.locationEnabled -> item {
                Notice(
                    Icons.Filled.LocationOn,
                    "Turn on Location to see nearby networks",
                    "Android hides nearby Wi-Fi networks while Location is switched off in the phone's settings.",
                ) { FilledTonalButton(onClick = actions.onOpenLocationSettings) { Text("Open settings") } }
            }
            else -> {
                state.bands.forEach { band ->
                    item(key = "band-${band.band}") { ChannelCard(band) }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                        Text("Nearby networks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (state.scanning) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                        } else {
                            FilledTonalButton(onClick = actions.onRescan) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Rescan")
                            }
                        }
                    }
                }
                if (accessPoints.isEmpty()) {
                    item {
                        val reason = when (state.scan?.unavailableReason) {
                            ScanUnavailableReason.THROTTLED -> "Android limits apps to four Wi-Fi scans every two minutes. Try again shortly."
                            ScanUnavailableReason.NOT_ON_WIFI -> "Wi-Fi is switched off."
                            else -> "No networks listed yet. Tap Rescan."
                        }
                        Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                } else {
                    item {
                        SectionCard {
                            accessPoints.forEachIndexed { index, ap ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                AccessPointRow(ap)
                            }
                        }
                    }
                    if (state.scan?.fresh == false) {
                        item {
                            Text(
                                "Showing Android's most recent scan results. Tap Rescan for a fresh list.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkCard(link: WifiLink, history: List<Int>) {
    val colors = LocalStatusColors.current
    val rssi = history.lastOrNull() ?: link.rssiDbm
    val channel = WifiChannels.channelOf(link.frequencyMhz)
    SectionCard(link.ssid ?: "Connected Wi-Fi") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$rssi", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = signalColor(rssi, colors))
            Spacer(Modifier.width(6.dp))
            Text("dBm", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.width(12.dp))
            Text(signalQuality(rssi), style = MaterialTheme.typography.titleMedium, color = signalColor(rssi, colors), modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Meter(signalFraction(rssi), signalColor(rssi, colors))
        if (history.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Text("Signal over the last ${history.size} seconds", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Sparkline(
                history.map { it.toDouble() },
                Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "Signal strength history, currently $rssi dBm" },
                color = signalColor(rssi, colors),
                range = -95.0..-30.0,
            )
        }
        Spacer(Modifier.height(12.dp))
        DetailRow("Band", WifiChannels.bandOf(link.frequencyMhz).label)
        DetailRow("Channel", (channel?.toString() ?: "?") + (link.channelWidthMhz?.let { " ($it MHz wide)" } ?: "") + "  ·  ${link.frequencyMhz} MHz")
        DetailRow("Standard", link.standard.label)
        DetailRow("Security", link.security.label)
        val rates = listOfNotNull(link.rxLinkSpeedMbps?.let { "$it Mbps down" }, link.txLinkSpeedMbps?.let { "$it Mbps up" })
        if (rates.isNotEmpty()) DetailRow("Link rate", rates.joinToString(", "))
        val maxRates = listOfNotNull(link.maxRxLinkSpeedMbps?.let { "$it Mbps down" }, link.maxTxLinkSpeedMbps?.let { "$it Mbps up" })
        if (maxRates.isNotEmpty()) DetailRow("Best possible here", maxRates.joinToString(", "))
        link.bssid?.let { DetailRow("Access point", it) }
    }
}

@Composable
private fun ChannelCard(analysis: BandAnalysis) {
    val colors = LocalStatusColors.current
    val scores = analysis.scores
    if (scores.isEmpty()) return
    val maxScore = (scores.maxOf { it.score }).coerceAtLeast(1.0)
    val current = analysis.currentChannel
    val recommended = analysis.recommendedChannel
    val primary = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceContainerHighest
    val busy = MaterialTheme.colorScheme.outline
    SectionCard(
        "${analysis.band.label} channels",
        trailing = { Text("${analysis.networkCount} networks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        val summary = buildString {
            append("${analysis.band.label} channel congestion. ")
            if (current != null) append("Your router is on channel $current. ")
            if (recommended != null) append("Channel $recommended is quieter.")
        }
        Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = summary }) {
            val gap = 3.dp.toPx()
            val barWidth = (size.width - gap * (scores.size - 1)) / scores.size
            scores.forEachIndexed { index, score ->
                val fraction = (score.score / maxScore).toFloat().coerceIn(0f, 1f)
                // A minimum height keeps empty channels visible as a baseline.
                val barHeight = (size.height * fraction).coerceAtLeast(3.dp.toPx())
                val color = when (score.channel) {
                    current -> primary
                    recommended -> colors.good
                    else -> if (score.score > 0) busy else idle
                }
                drawRoundRect(
                    color,
                    topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            // Label every channel when they fit, otherwise every fourth.
            val every = if (scores.size <= 13) 1 else 4
            scores.forEachIndexed { index, score ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (index % every == 0 || score.channel == current || score.channel == recommended) {
                        Text(
                            score.channel.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                            color = when (score.channel) {
                                current -> primary
                                recommended -> colors.good
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (score.channel == current || score.channel == recommended) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (current != null) LegendDot(primary, "Your channel ($current)")
            if (recommended != null) LegendDot(colors.good, "Quieter ($recommended)")
            LegendDot(busy, "Neighbours")
        }
        Spacer(Modifier.height(8.dp))
        val advice = when {
            current == null -> "Taller bars mean more interference from neighbouring networks on that channel."
            recommended != null -> "Channel $recommended is clearly quieter than your channel $current here. Changing it in the router's Wi-Fi settings should help."
            (analysis.currentScore?.score ?: 0.0) >= 1.2 -> "Your channel is busy, but no other channel is clearly better."
            else -> "Your channel is as quiet as any other here."
        }
        Text(advice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(10.dp), shape = RoundedCornerShape(3.dp), color = color) {}
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccessPointRow(ap: AccessPoint) {
    val colors = LocalStatusColors.current
    val channel = WifiChannels.channelOf(ap.frequencyMhz)
    val open = ap.security == WifiSecurity.OPEN
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ap.ssid.ifEmpty { "(hidden network)" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (ap.isConnected) FontWeight.Bold else FontWeight.Normal,
                    color = if (ap.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (open) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    contentDescription = if (open) "No password" else "Secured",
                    tint = if (open) colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                listOfNotNull(
                    WifiChannels.bandOf(ap.frequencyMhz).label,
                    channel?.let { "ch $it" },
                    "${ap.channelWidthMhz} MHz",
                    ap.standard.takeIf { it.generation > 0 }?.let { "Wi-Fi ${it.generation}" },
                    if (ap.isConnected) "connected" else null,
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(72.dp)) {
            Text("${ap.rssiDbm} dBm", style = MaterialTheme.typography.labelLarge, color = signalColor(ap.rssiDbm, colors))
            Spacer(Modifier.height(4.dp))
            Meter(signalFraction(ap.rssiDbm), signalColor(ap.rssiDbm, colors))
        }
    }
}
