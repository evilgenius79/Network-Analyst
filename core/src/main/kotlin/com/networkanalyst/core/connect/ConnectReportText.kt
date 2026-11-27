package com.networkanalyst.core.connect

import java.util.Locale

/** Plain-text version of the connection test and watch, for sharing with a router maker's support. */
object ConnectReportText {
    /** A wait this long before the first try is worth mentioning. */
    const val LONG_WAIT_MS = 2_000.0

    fun targetLine(result: TargetResult): String {
        val parts = ArrayList<String>()
        parts += "${result.successes}/${result.trials.size} connected"
        result.medianConnectMs?.let { parts += "typically ${String.format(Locale.US, "%.1f", it / 1000)} s" }
        result.medianWaitMs?.takeIf { it >= LONG_WAIT_MS }?.let { parts += "after Android spent ${String.format(Locale.US, "%.0f", it / 1000)} s looking for it" }
        when (val count = result.hiddenFailureCount) {
            null -> Unit
            0 -> parts += "0 silent retries"
            else -> parts += result.hiddenFailureSummary!!
        }
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
            report.results.filter { it.trials.isNotEmpty() }.forEach { result ->
                appendLine("- ${targetLine(result)}")
                // What each radio says about itself, so a failing one can be compared with the rest.
                val about = listOfNotNull(result.target.traits.takeIf { it.isNotEmpty() }, result.target.capabilities?.takeIf { it.isNotBlank() })
                if (about.isNotEmpty()) appendLine("    radio: ${about.joinToString("  ")}")
            }
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
