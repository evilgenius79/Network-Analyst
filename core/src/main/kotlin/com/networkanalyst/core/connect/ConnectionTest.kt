package com.networkanalyst.core.connect

import com.networkanalyst.core.analysis.Category
import com.networkanalyst.core.analysis.Finding
import com.networkanalyst.core.analysis.Severity
import com.networkanalyst.core.model.WifiBand
import com.networkanalyst.core.util.Stats
import com.networkanalyst.core.wifi.WifiChannels
import kotlinx.serialization.Serializable

/** Why an attempt to connect to one access point ended without a connection. */
enum class ConnectFailure(val label: String, val explanation: String) {
    NOT_FOUND(
        "Not found",
        "The access point was not seen when the phone looked for it. It may be out of range here, or its radio may have changed channel or switched off.",
    ),
    NO_RESPONSE(
        "No response",
        "The access point was in range but did not answer. Its signal may be too weak here, or it may be ignoring this phone to steer it to another access point.",
    ),
    ASSOCIATION("Refused when joining", FailedStage.JOINING.explanation),
    AUTHENTICATION(
        "Key exchange failed",
        "The access point and the phone could not complete the encryption key exchange. With the right password that points to a security setting the phone and access point disagree on, or to frames being lost during the exchange.",
    ),
    IP_ADDRESS("No IP address", FailedStage.IP_ADDRESS.explanation),
    TIMED_OUT(
        "Timed out",
        "Android neither connected nor reported a failure within the time allowed.",
    ),
    DECLINED("Not allowed", "The request was declined in Android's approval dialog."),
    REJECTED(
        "Not accepted",
        "Android turned the request down at once, before trying to connect. It only takes these requests from an app that is open on screen, with Wi-Fi switched on.",
    ),
    UNKNOWN("Failed", "Android reported a failure without saying at which stage."),
    ;

    /**
     * False for outcomes in which no connection was attempted at all. Those say nothing about
     * the access point, so they end the test and are left out of its results.
     */
    val countsAsTest: Boolean get() = this != DECLINED && this != REJECTED
}

/** One access point radio to test, as seen in a scan. */
@Serializable
data class TestTarget(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int,
    val rssiDbm: Int,
) {
    val band: WifiBand get() = WifiChannels.bandOf(frequencyMhz)
    val channel: Int? get() = WifiChannels.channelOf(frequencyMhz)

    /** Short description such as "5 GHz ch 36". */
    val radioLabel: String get() = band.label + (channel?.let { " ch $it" } ?: "")
}

/** One request to connect to a target. Android may try several times behind one request. */
@Serializable
data class ConnectTrial(
    val bssid: String,
    val success: Boolean,
    val failure: ConnectFailure? = null,
    /** From the request until Android reported the outcome. */
    val durationMs: Long,
    /**
     * Failed tries Android made silently before reporting the outcome, with the stage each
     * stopped at. Null when the phone does not reveal them.
     */
    val hiddenFailures: List<FailedStage>? = null,
)

data class TargetResult(val target: TestTarget, val trials: List<ConnectTrial>) {
    val successes: Int get() = trials.count { it.success }
    val failures: Int get() = trials.size - successes

    /** Silent retries across all trials, or null when the phone does not reveal them. */
    val hiddenFailureCount: Int? get() = if (trials.any { it.hiddenFailures == null }) null else trials.sumOf { it.hiddenFailures!!.size }
    val medianConnectMs: Double? get() = Stats.median(trials.filter { it.success }.map { it.durationMs.toDouble() })

    /** Connected every time without a single silent retry (as far as can be seen). */
    val clean: Boolean get() = trials.isNotEmpty() && failures == 0 && (hiddenFailureCount ?: 0) == 0
    val dead: Boolean get() = trials.isNotEmpty() && successes == 0

    /** Reported failure reasons, most frequent first. */
    val failureReasons: List<Pair<ConnectFailure, Int>>
        get() = trials.mapNotNull { it.failure }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
}

data class ConnectionTestReport(
    /** Per access point, holding only the requests in which a connection was really attempted. */
    val results: List<TargetResult>,
    val findings: List<Finding>,
    /** One-line conclusion. */
    val headline: String,
    /** Set when the test ended early because a request was never attempted, with the reason. */
    val stoppedBy: ConnectFailure? = null,
)

/** Turns per-access-point results into conclusions about where the fault lies. */
object ConnectionTestAnalyzer {
    fun analyze(allResults: List<TargetResult>): ConnectionTestReport {
        // A request that was never attempted (declined, or not accepted by Android) is not a
        // failed connection; it only explains why the test stopped where it did.
        val stoppedBy = allResults.flatMap { it.trials }.mapNotNull { it.failure }.firstOrNull { !it.countsAsTest }
        val results = allResults.map { r -> r.copy(trials = r.trials.filter { it.failure?.countsAsTest != false }) }
        val tested = results.filter { it.trials.isNotEmpty() }
        if (tested.isEmpty()) {
            return ConnectionTestReport(
                results,
                emptyList(),
                when (stoppedBy) {
                    ConnectFailure.DECLINED -> "Nothing was tested because the request was not allowed."
                    ConnectFailure.REJECTED -> "Nothing was tested because Android did not accept the request."
                    else -> "Nothing was tested."
                },
                stoppedBy,
            )
        }
        val findings = ArrayList<Finding>()
        val clean = tested.filter { it.clean }
        val dead = tested.filter { it.dead }
        val troubled = tested.filter { !it.clean }
        val totalTrials = tested.sumOf { it.trials.size }
        val totalSuccess = tested.sumOf { it.successes }
        val retriesKnown = tested.all { it.hiddenFailureCount != null }
        val totalRetries = tested.sumOf { it.hiddenFailureCount ?: 0 }
        val reasons = tested.flatMap { r -> r.trials.mapNotNull { it.failure } }
        val hiddenStages = tested.flatMap { r -> r.trials.flatMap { it.hiddenFailures.orEmpty() } }

        fun describe(result: TargetResult) = "${result.target.radioLabel} (${result.target.bssid}, ${result.target.rssiDbm} dBm)"

        // ---- Everything worked ----
        if (troubled.isEmpty()) {
            findings += Finding(
                "connect.all_ok", Category.CONNECTING, Severity.GOOD,
                "Every access point accepted the phone",
                "All $totalTrials connection requests across ${tested.size} access point radio${plural(tested.size)} succeeded" +
                    (if (retriesKnown) " on the first try, with no silent retries." else ". This phone does not reveal silent retries, so an occasional failed try may be hidden.") +
                    " Asked directly, the network takes this phone without trouble at this spot and moment.",
                listOf(
                    "If \"Connection failed\" still appears, run this test again right when it happens, and leave the connection watch on to catch it.",
                    "This test connects with fresh settings. If only the saved network fails, forget the network on the phone and join it again, or reset the phone's Wi-Fi settings.",
                ),
            )
            return ConnectionTestReport(results, findings, "The network accepts this phone at every access point tested.", stoppedBy)
        }

        // ---- Nothing worked ----
        if (clean.isEmpty() && totalSuccess == 0) {
            val dominant = reasons.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ConnectFailure.UNKNOWN
            val (title, fixes) = when (dominant) {
                ConnectFailure.AUTHENTICATION -> "The key exchange failed on every access point" to listOf(
                    "Check the password typed into this test, character by character. A wrong password fails exactly like this.",
                    "If the password is right, the phone and the router disagree on a security setting. Check the router is on plain WPA2 (AES), with WPA3 and any Wi-Fi 7 multi-link option switched off, and try again.",
                )
                ConnectFailure.IP_ADDRESS -> "The phone joins but is never given an IP address" to listOf(
                    "Restart the router. Its address service (DHCP) may have hung.",
                    "If the mesh is in bridge mode behind another router, check that router's DHCP address range is not used up.",
                )
                ConnectFailure.ASSOCIATION, ConnectFailure.NO_RESPONSE -> "No access point would take the phone" to listOf(
                    "Turn off client steering on the router for a test and try again; steering can work by refusing a device until it tries elsewhere.",
                    "Check the router is not blocking or pausing this phone, and that it has not reached its device limit.",
                    "Connect this phone to a different Wi-Fi network (another phone's hotspot). If it fails there too, the phone is at fault.",
                )
                else -> "Every connection request failed" to listOf(
                    "Make sure the phone is within range of the network and that Wi-Fi is on, then run the test again.",
                    "Connect this phone to a different Wi-Fi network (another phone's hotspot). If it fails there too, the phone is at fault.",
                )
            }
            findings += Finding(
                "connect.all_failed", Category.CONNECTING, Severity.CRITICAL, title,
                "None of the $totalTrials connection requests succeeded. The usual reported reason was \"${dominant.label}\": ${dominant.explanation}",
                fixes,
            )
            return ConnectionTestReport(results, findings, title + ".", stoppedBy)
        }

        // ---- Mixed: some access points are fine, some are not ----
        if (clean.isNotEmpty()) {
            val badBands = troubled.map { it.target.band }.toSet()
            val goodBands = clean.map { it.target.band }.toSet()
            val bandSpecific = badBands.size == 1 && badBands.none { it in goodBands }
            val which = troubled.joinToString("; ") { r ->
                describe(r) + ": ${r.successes} of ${r.trials.size} connected" +
                    (r.hiddenFailureCount?.takeIf { it > 0 }?.let { ", $it silent retr${if (it == 1) "y" else "ies"}" } ?: "") +
                    (r.failureReasons.firstOrNull()?.let { ", mostly \"${it.first.label}\"" } ?: "")
            }
            // Requests that all got through, but only after silent retries, are unreliable rather than failing.
            val onlyRetries = troubled.all { it.failures == 0 }
            if (bandSpecific) {
                val band = badBands.first()
                findings += Finding(
                    "connect.band", Category.CONNECTING, if (onlyRetries) Severity.WARNING else Severity.CRITICAL,
                    if (onlyRetries) "Connecting needs retries on ${band.label} only" else "Connecting fails on ${band.label} only",
                    "Every ${band.label} radio tested gave trouble, while ${goodBands.joinToString(" and ") { it.label }} connected cleanly. " +
                        "So the phone fails when it picks ${band.label} and works when it picks another band, which matches a fault that comes and goes. $which.",
                    buildList {
                        if (band != WifiBand.GHZ_2_4) add("As a check, hide the ${band.label} band on the router for a few minutes (some mesh systems offer this under troubleshooting) and see whether the phone then connects every time.")
                        add("Turn off client or band steering on the router and test again.")
                        add("Report it to the router maker with these results; one band failing for one phone model is often a firmware compatibility fault.")
                    },
                )
            } else {
                findings += Finding(
                    "connect.some_aps", Category.CONNECTING, if (dead.isNotEmpty()) Severity.CRITICAL else Severity.WARNING,
                    if (troubled.size == 1) "One access point radio gives trouble" else "${troubled.size} access point radios give trouble",
                    "${clean.size} of ${tested.size} access point radios connected cleanly every time. The rest did not, so the phone fails when it " +
                        "happens to pick one of those and works when it picks another, which matches a fault that comes and goes. $which.",
                    listOf(
                        "Find the unit: stand next to each mesh unit in turn; the one whose radios show the strongest signal there is that unit. Restart it, and check how it links back to the main router.",
                        "Compare signal strengths. If the failing radios are the weak ones, the phone is reaching for a unit that is too far away, and moving a unit closer fixes it.",
                        "If a failing radio has a strong signal, report it to the router maker with these results.",
                    ),
                )
            }
        } else {
            // No access point was clean, but some requests got through.
            findings += Finding(
                "connect.flaky", Category.CONNECTING, Severity.CRITICAL,
                "Connecting is unreliable on every access point",
                "$totalSuccess of $totalTrials connection requests succeeded" +
                    (if (retriesKnown && totalRetries > 0) ", and Android made $totalRetries silent retr${if (totalRetries == 1) "y" else "ies"} along the way" else "") +
                    ". No access point radio was reliably good, so the fault is not tied to one unit or band.",
                listOf(
                    "Connect this phone to a different Wi-Fi network (another phone's hotspot) and run this test there. Trouble on two unrelated networks means the phone is at fault.",
                    "Restart the router and its mesh units, then test again.",
                    "Turn off client steering on the router for a test.",
                ),
            )
        }

        // ---- Silent retries, wherever they happened ----
        if (totalRetries > 0) {
            val byStage = hiddenStages.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
            val top = byStage.first().first
            findings += Finding(
                "connect.retries", Category.CONNECTING, Severity.WARNING,
                "Android had to retry $totalRetries time${plural(totalRetries)} behind the scenes",
                "A connection request is retried silently before a failure is reported, so these tries are failures the result alone would hide. " +
                    byStage.joinToString(", ") { "${it.second} stopped at \"${it.first.label}\"" } + ". ${top.explanation}",
            )
        }

        // ---- What the reported failures say ----
        if (reasons.isNotEmpty()) {
            val top = reasons.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
            if (top.key == ConnectFailure.IP_ADDRESS) {
                findings += Finding(
                    "connect.dhcp", Category.CONNECTING, Severity.WARNING,
                    "Some attempts joined but got no IP address",
                    "${top.value} request${plural(top.value)} failed at the last step. ${ConnectFailure.IP_ADDRESS.explanation}",
                    listOf(
                        "Check which box hands out addresses. If the mesh is in bridge mode, it is the provider's router, whose address range may be small.",
                        "Restart that router.",
                    ),
                )
            } else if (top.key == ConnectFailure.AUTHENTICATION && totalSuccess > 0) {
                findings += Finding(
                    "connect.keys_sometimes", Category.CONNECTING, Severity.WARNING,
                    "The key exchange fails some of the time",
                    "${top.value} request${plural(top.value)} failed while exchanging encryption keys although the same password worked on other attempts, so the password is right. " +
                        "That leaves frames being lost during the exchange or the access point handling this phone inconsistently.",
                    listOf(
                        "Compare the signal strength of the radios that failed with those that worked. If the failing ones are the weak ones, it is range.",
                        "If strong radios fail too, report it to the router maker with these results.",
                    ),
                )
            }
        }

        val headline = when {
            findings.any { it.id == "connect.band" } -> findings.first { it.id == "connect.band" }.title + "."
            findings.any { it.id == "connect.some_aps" } -> "The trouble is tied to specific access points."
            else -> "Connecting is unreliable everywhere."
        }
        return ConnectionTestReport(results, findings.sortedBy { it.severity.ordinal }, headline, stoppedBy)
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"
}
