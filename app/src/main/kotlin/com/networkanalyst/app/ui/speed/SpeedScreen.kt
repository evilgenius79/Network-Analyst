package com.networkanalyst.app.ui.speed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.networkanalyst.app.data.SpeedLive
import com.networkanalyst.app.ui.SpeedUiState
import com.networkanalyst.app.ui.components.DetailRow
import com.networkanalyst.app.ui.components.Fmt
import com.networkanalyst.app.ui.components.Notice
import com.networkanalyst.app.ui.components.SectionCard
import com.networkanalyst.app.ui.components.Sparkline
import com.networkanalyst.app.ui.components.SpeedGauge
import com.networkanalyst.app.ui.components.StatTile
import com.networkanalyst.app.ui.theme.LocalStatusColors
import com.networkanalyst.core.history.HistoryEntry
import com.networkanalyst.core.model.SpeedTestResult
import com.networkanalyst.core.speed.BufferbloatGrade
import com.networkanalyst.core.speed.SpeedPhase

class SpeedActions(
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onClearHistory: () -> Unit = {},
)

@Composable
fun SpeedScreen(
    state: SpeedUiState,
    connected: Boolean,
    metered: Boolean,
    actions: SpeedActions,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                val live = state.live
                if (state.running && live != null) {
                    LiveTest(live)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = actions.onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                } else {
                    val result = state.result
                    if (result != null) ResultNumbers(result) else {
                        Text("Speed test", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Measures download and upload speed, latency, and how much the connection lags while it is busy.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = actions.onStart,
                        enabled = connected,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(if (result == null) "Start speed test" else "Test again", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }

        state.result?.error?.takeIf { !state.running }?.let { error ->
            item { Notice(Icons.Filled.ErrorOutline, "The test did not complete", error, tint = LocalStatusColors.current.warning) }
        }

        if (metered && !state.running) {
            item {
                Notice(
                    Icons.Filled.DataUsage,
                    "This connection is metered",
                    "The test stops each direction after 100 MB to protect your data allowance, so it uses at most about 200 MB.",
                )
            }
        }

        state.result?.takeIf { !state.running }?.let { result ->
            item { ResultDetails(result) }
        }

        if (state.history.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Previous tests",
                    trailing = { TextButton(onClick = actions.onClearHistory) { Text("Clear") } },
                ) {
                    state.history.take(20).forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        HistoryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTest(live: SpeedLive) {
    val label = when (live.phase) {
        SpeedPhase.LATENCY -> "Measuring latency"
        SpeedPhase.DOWNLOAD -> "Download"
        SpeedPhase.UPLOAD -> "Upload"
        SpeedPhase.DONE -> "Finishing"
    }
    val accent = if (live.phase == SpeedPhase.UPLOAD) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    SpeedGauge(live.currentMbps, label, accent = accent)
    Spacer(Modifier.height(8.dp))
    if (live.series.size >= 2) {
        Sparkline(live.series, Modifier.fillMaxWidth().height(40.dp), color = accent)
        Spacer(Modifier.height(8.dp))
    }
    LinearProgressIndicator(progress = { live.fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = accent)
    live.latencyMs?.let {
        Spacer(Modifier.height(8.dp))
        Text("Latency ${Fmt.ms(it)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultNumbers(result: SpeedTestResult) {
    val colors = LocalStatusColors.current
    Row(Modifier.fillMaxWidth()) {
        BigNumber("Download", result.downloadMbps, Modifier.weight(1f))
        BigNumber("Upload", result.uploadMbps, Modifier.weight(1f))
    }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth()) {
        StatTile("Latency", Fmt.ms(result.idleLatencyMs), Modifier.weight(1f), caption = result.idleJitterMs?.let { "jitter ${Fmt.ms(it)}" })
        val added = result.bufferbloatMs
        if (added != null) {
            StatTile(
                "Lag under load", "Grade ${BufferbloatGrade.of(added).label}", Modifier.weight(1f),
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

@Composable
private fun BigNumber(label: String, mbps: Double?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(mbps?.let(Fmt::speedValue) ?: "–", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            if (mbps != null) {
                Spacer(Modifier.width(4.dp))
                Text(Fmt.speedUnit(mbps), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun ResultDetails(result: SpeedTestResult) {
    SectionCard(title = "Details") {
        result.downloadLoadedLatencyMs?.let { DetailRow("Latency while downloading", Fmt.ms(it)) }
        result.uploadLoadedLatencyMs?.let { DetailRow("Latency while uploading", Fmt.ms(it)) }
        result.serverLocation?.let { DetailRow("Test server", it) }
        result.publicIp?.let { DetailRow("Your public address", it) }
        listOfNotNull(result.asn, result.city, result.country).joinToString(", ").takeIf { it.isNotBlank() }?.let { DetailRow("Provider network", it) }
        if (result.bytesDownloaded + result.bytesUploaded > 0) {
            DetailRow("Data used", "${Fmt.bytes(result.bytesDownloaded)} down, ${Fmt.bytes(result.bytesUploaded)} up")
        }
        if (result.timestampMs > 0) DetailRow("Tested", Fmt.dateTime(result.timestampMs))
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.networkLabel, style = MaterialTheme.typography.bodyLarge)
            Text(Fmt.dateTime(entry.timestampMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("↓ ${Fmt.speed(entry.downloadMbps)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("↑ ${Fmt.speed(entry.uploadMbps)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
