package com.networkanalyst.core.connect

import java.util.Locale

/** Plain-text version of the connection test and watch, for sharing with a router maker's support. */
object ConnectReportText {
    fun targetLine(result: TargetResult): String {
        val parts = ArrayList<String>()
        parts += "${result.successes}/${result.trials.size} connected"
        result.medianConnectMs?.let { parts += "typically ${String.format(Locale.US, "%.1f", it / 1000)} s" }
        result.hiddenFailureCount?.let { parts += "$it silent retr${if (it == 1) "y" else "ies"}" }
        if (result.failureReasons.isNotEmpty()) {
            parts += "failed: " + result.failureReasons.joinToString(", ") { "${it.first.label} x${it.second}" }
        }
        return "${result.target.radioLabel} (${result.target.bssid}, ${result.target.rssiDbm} dBm): " + parts.joinToString(", ")
    }

    fun build(report: ConnectionTestReport?, events: List<WifiEvent>, title: String = "Network Analyst connection report"): String = buildString {
        appendLine(title)
        appendLine("=".repeat(title.length))
        if (report != null) {
            appendLine()
            appendLine("CONNECTION TEST" + (report.results.firstOrNull()?.target?.ssid?.let { " for \"$it\"" } ?: ""))
            appendLine(report.headline)
            report.results.filter { it.trials.isNotEmpty() }.forEach { appendLine("- ${targetLine(it)}") }
            report.stoppedBy?.let { appendLine("The test stopped early: ${it.label}. ${it.explanation}") }
            report.findings.forEachIndexed { index, finding ->
                appendLine()
                appendLine("${index + 1}. [${finding.severity.label}] ${finding.title}")
                appendLine("   ${finding.detail}")
                finding.fixes.forEach { appendLine("   - $it") }
            }
        }
        val watch = WatchAnalyzer.analyze(events)
        appendLine()
        appendLine("CONNECTION WATCH")
        appendLine(watch.headline)
        watch.findings.forEach { finding ->
            appendLine("- [${finding.severity.label}] ${finding.title}. ${finding.detail}")
        }
        if (events.isNotEmpty()) {
            appendLine()
            appendLine("EVENT LOG")
            // Skip the log's own title and headline lines.
            WatchLogText.build(events).lineSequence().drop(3).filter { it.isNotBlank() }.forEach { appendLine(it) }
        }
    }.trimEnd() + "\n"
}
