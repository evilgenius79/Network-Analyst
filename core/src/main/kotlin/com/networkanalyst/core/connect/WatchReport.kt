package com.networkanalyst.core.connect

import com.networkanalyst.core.analysis.Category
import com.networkanalyst.core.analysis.Finding
import com.networkanalyst.core.analysis.Severity
import com.networkanalyst.core.util.Stats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the connection watch has seen the phone do on its own. */
data class WatchReport(
    val summary: TimelineSummary,
    val findings: List<Finding>,
    val headline: String,
)

/** Interprets the phone's own connection attempts and drops, as recorded by the watch. */
object WatchAnalyzer {
    fun analyze(events: List<WifiEvent>): WatchReport {
        // Connections the app's own test made say nothing about how the phone behaves by itself.
        val summary = ConnectionTimeline.summarize(events, includeTests = false)
        val attempts = summary.attempts.filter { !it.roam }
        val failures = attempts.filter { !it.success }
        val roams = summary.attempts.filter { it.roam }
        val findings = ArrayList<Finding>()

        if (summary.attempts.isEmpty() && summary.drops.isEmpty()) {
            return WatchReport(
                summary,
                emptyList(),
                if (events.any { it.kind == WifiEventKind.SUPPLICANT || it.kind == WifiEventKind.NETWORK }) {
                    "Watching. No connection attempts or drops seen yet."
                } else {
                    "Watching. Nothing has happened yet."
                },
            )
        }

        for ((stage, count) in failures.mapNotNull { it.failedAt }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }) {
            val wrongPassword = failures.count { it.failedAt == stage && it.authError == "WRONG_PASSWORD" }
            val timeouts = failures.count { it.failedAt == stage && it.authError == "TIMEOUT" }
            val extra = buildString {
                if (wrongPassword > 0) append(" Android flagged $wrongPassword of them as a wrong password.")
                if (timeouts > 0) append(" Android flagged $timeouts of them as an authentication timeout.")
            }
            findings += Finding(
                "watch.failed.${stage.name.lowercase()}", Category.CONNECTING, Severity.WARNING,
                "$count attempt${plural(count)} stopped at \"${stage.label}\"",
                stage.explanation + extra,
            )
        }

        if (summary.drops.isNotEmpty()) {
            val durations = summary.drops.map { it.connectedForMs.toDouble() }
            val signals = summary.drops.mapNotNull { it.rssiDbm }
            val weak = signals.count { it <= -75 }
            findings += Finding(
                "watch.drops", Category.CONNECTING, if (summary.drops.size >= 3) Severity.WARNING else Severity.INFO,
                "The connection dropped ${summary.drops.size} time${plural(summary.drops.size)}",
                "Typically after ${duration(Stats.median(durations)!!.toLong())} connected." +
                    when {
                        signals.isEmpty() -> ""
                        weak == signals.size -> " The signal was weak (${signals.max()} dBm or lower) every time, so these look like range."
                        weak == 0 -> " The signal was fine each time (${signals.min()} dBm or better), so range is not the reason."
                        else -> " The signal was weak for $weak of them."
                    },
            )
        }

        val failedRoams = roams.count { !it.success }
        if (failedRoams > 0) {
            findings += Finding(
                "watch.roams", Category.CONNECTING, Severity.WARNING,
                "$failedRoams move${plural(failedRoams)} between access points failed",
                "The phone tried to switch to another access point of the same network and lost the connection doing so. On a mesh that points at the hand-over between units.",
            )
        }

        if (!summary.detailed && failures.isNotEmpty()) {
            findings += Finding(
                "watch.coarse", Category.CONNECTING, Severity.INFO,
                "This phone reports only coarse connection stages",
                "It does not broadcast the individual steps of joining a network, so a failure can only be placed before or after the IP address step.",
            )
        }

        val headline = when {
            failures.isEmpty() && summary.drops.isEmpty() ->
                "${attempts.size} connection attempt${plural(attempts.size)} seen, all successful."
            failures.isEmpty() ->
                "${attempts.size} connection attempt${plural(attempts.size)} succeeded; the connection dropped ${summary.drops.size} time${plural(summary.drops.size)}."
            else ->
                "${failures.size} of ${attempts.size} connection attempt${plural(attempts.size)} failed."
        }
        return WatchReport(summary, findings.sortedBy { it.severity.ordinal }, headline)
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"

    internal fun duration(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 90 -> "$seconds seconds"
            seconds < 90 * 60 -> "${seconds / 60} minutes"
            else -> String.format(Locale.US, "%.1f hours", seconds / 3600.0)
        }
    }
}

/** Renders the raw event log as text, one line per event, for sharing. */
object WatchLogText {
    fun describe(event: WifiEvent): String {
        val where = listOfNotNull(
            event.ssid?.let { "\"$it\"" },
            event.bssid,
            event.frequencyMhz?.let { "$it MHz" },
            event.rssiDbm?.let { "$it dBm" },
        ).joinToString(" ")
        val what = when (event.kind) {
            WifiEventKind.SUPPLICANT -> "Wi-Fi step: ${event.value}"
            WifiEventKind.NETWORK -> "Network: ${event.value}"
            WifiEventKind.AUTH_ERROR -> "Authentication error: ${event.value}"
            WifiEventKind.LINK_UP -> "Connected"
            WifiEventKind.LINK_DOWN -> "Disconnected"
            WifiEventKind.WIFI_SWITCH -> "Wi-Fi switched ${event.value}"
            WifiEventKind.NOTE -> event.value
            WifiEventKind.TEST_START -> "Connection test started" + (if (event.value.isNotBlank()) ": ${event.value}" else "")
            WifiEventKind.TEST_END -> "Connection test finished"
        }
        return if (where.isEmpty()) what else "$what  ($where)"
    }

    fun build(events: List<WifiEvent>, title: String = "Network Analyst connection log"): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return buildString {
            appendLine(title)
            appendLine(WatchAnalyzer.analyze(events).headline)
            appendLine()
            for (event in events.sortedBy { it.timeMs }) {
                appendLine("${format.format(Date(event.timeMs))}  ${describe(event)}")
            }
        }
    }
}
