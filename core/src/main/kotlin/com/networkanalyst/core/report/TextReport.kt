package com.networkanalyst.core.report

import com.networkanalyst.core.analysis.Diagnosis
import com.networkanalyst.core.analysis.Severity
import com.networkanalyst.core.model.NetworkSnapshot
import com.networkanalyst.core.model.PingResult
import com.networkanalyst.core.speed.BufferbloatGrade
import com.networkanalyst.core.wifi.WifiChannels
import java.util.Locale

/** Renders a scan as plain text for sharing, for example with an internet provider's support. */
object TextReport {
    private fun Double.fmt(decimals: Int = 1): String = String.format(Locale.US, "%.${decimals}f", this)

    fun build(snapshot: NetworkSnapshot, diagnosis: Diagnosis, title: String = "Network Analyst report"): String = buildString {
        appendLine(title)
        appendLine("=".repeat(title.length))
        appendLine("${diagnosis.headline} (health ${diagnosis.healthScore}/100)")
        appendLine(diagnosis.summary)
        appendLine()

        appendLine("MEASUREMENTS")
        val c = snapshot.connection
        appendLine("Connection: ${c.transport.label}" + (if (c.isVpn) " via VPN" else "") + (c.cellularGeneration?.let { " ($it)" } ?: ""))
        snapshot.wifiLink?.let { link ->
            val channel = WifiChannels.channelOf(link.frequencyMhz)
            appendLine(
                "Wi-Fi: ${link.ssid ?: "(name hidden)"}, ${WifiChannels.bandOf(link.frequencyMhz).label}" +
                    (channel?.let { " channel $it" } ?: "") +
                    (link.channelWidthMhz?.let { " ($it MHz)" } ?: "") +
                    ", signal ${link.rssiDbm} dBm, ${link.standard.label}" +
                    (link.rxLinkSpeedMbps?.let { ", link ${it} Mbps down" } ?: "") +
                    (link.txLinkSpeedMbps?.let { " / ${it} Mbps up" } ?: ""),
            )
        }
        snapshot.gatewayPing?.let { appendLine(pingLine("Router", it)) }
        snapshot.internetPings.forEach { appendLine(pingLine(it.label, it)) }
        snapshot.speed?.let { s ->
            s.downloadMbps?.let { appendLine("Download: ${it.fmt()} Mbps") }
            s.uploadMbps?.let { appendLine("Upload: ${it.fmt()} Mbps") }
            s.idleLatencyMs?.let { appendLine("Latency to test server: ${it.fmt()} ms idle" + (s.idleJitterMs?.let { j -> ", jitter ${j.fmt()} ms" } ?: "")) }
            s.bufferbloatMs?.let { appendLine("Latency under load: +${it.fmt(0)} ms (grade ${BufferbloatGrade.of(it).label})") }
            s.serverLocation?.let { appendLine("Test server: $it") }
        }
        snapshot.dns?.servers?.filter { it.usable }?.forEach { server ->
            appendLine("DNS ${server.name} (${server.address}): ${server.medianMs?.fmt()} ms" + (if (server.failures > 0) ", ${server.failures}/${server.queries} failed" else ""))
        }
        snapshot.devices?.let { appendLine("Devices found on the network: ${it.size}") }
        appendLine()

        val problems = diagnosis.findings.filter { it.severity != Severity.GOOD }
        if (problems.isEmpty()) {
            appendLine("FINDINGS")
            appendLine("No problems found.")
        } else {
            appendLine("FINDINGS")
            problems.forEachIndexed { index, finding ->
                appendLine("${index + 1}. [${finding.severity.label}] ${finding.title}")
                appendLine("   ${finding.detail}")
                finding.fixes.forEach { appendLine("   - $it") }
            }
        }
        val healthy = diagnosis.findings.filter { it.severity == Severity.GOOD }
        if (healthy.isNotEmpty()) {
            appendLine()
            appendLine("CHECKED AND FINE")
            healthy.forEach { appendLine("- ${it.title}") }
        }
    }.trimEnd() + "\n"

    private fun pingLine(label: String, ping: PingResult): String =
        if (!ping.reachable) {
            "Ping $label (${ping.target}): no reply"
        } else {
            "Ping $label (${ping.target}): ${ping.medianMs?.fmt()} ms median, jitter ${(ping.jitterMs ?: 0.0).fmt()} ms, ${ping.lossPercent.fmt(0)}% loss"
        }
}
