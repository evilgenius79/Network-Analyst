package com.networkanalyst.core.analysis

import kotlinx.serialization.Serializable

enum class Severity(val label: String) {
    /** Something is badly hurting the connection right now. */
    CRITICAL("Problem"),
    /** Costing performance or reliability; worth fixing. */
    WARNING("Needs attention"),
    /** Useful to know or a possible improvement; not a fault. */
    INFO("Note"),
    /** Checked and found healthy. */
    GOOD("OK"),
}

enum class Category(val label: String) {
    CONNECTION("Connection"),
    WIFI_SIGNAL("Wi-Fi signal"),
    WIFI_CHANNEL("Wi-Fi channel"),
    WIFI_SETUP("Wi-Fi setup"),
    LOCAL_NETWORK("Phone to router"),
    INTERNET("Internet line"),
    SPEED("Speed"),
    BUFFERBLOAT("Lag under load"),
    DNS("DNS"),
    ROUTER("Router"),
    DEVICES("Devices"),
    SECURITY("Security"),
    CONNECTING("Connecting"),
}

/** One conclusion from the analysis, written for a person rather than an engineer. */
@Serializable
data class Finding(
    /** Stable identifier of the rule that produced this, e.g. "wifi.signal.weak". */
    val id: String,
    val category: Category,
    val severity: Severity,
    val title: String,
    /** What was measured and why it matters. */
    val detail: String,
    /** Concrete things to try, most effective first. Empty for healthy results. */
    val fixes: List<String> = emptyList(),
)

/** The single weakest link, used for the headline of the report. */
enum class Bottleneck {
    NO_CONNECTION,
    WIFI,
    INTERNET_LINE,
    BUFFERBLOAT,
    DNS,
    VPN,
    NONE,
}

enum class Rating(val label: String) { GREAT("Great"), OK("OK"), POOR("Poor"), UNKNOWN("Not tested") }

/** How well the connection suits an everyday activity. */
@Serializable
data class UseCaseVerdict(val name: String, val rating: Rating, val reason: String)

@Serializable
data class Diagnosis(
    /** Sorted most serious first. */
    val findings: List<Finding>,
    /** 0 (unusable) to 100 (nothing wrong found). */
    val healthScore: Int,
    val bottleneck: Bottleneck,
    val headline: String,
    val summary: String,
    val useCases: List<UseCaseVerdict> = emptyList(),
) {
    val problems: List<Finding> get() = findings.filter { it.severity == Severity.CRITICAL || it.severity == Severity.WARNING }
}
