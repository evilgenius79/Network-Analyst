package com.networkanalyst.core.connect

import com.networkanalyst.core.util.Stats

/** One attempt by the phone to get onto a Wi-Fi network, reconstructed from observed events. */
data class ObservedAttempt(
    val startMs: Long,
    val endMs: Long,
    val success: Boolean,
    /** Where it stopped, for a failed attempt. */
    val failedAt: FailedStage? = null,
    /** Authentication error the system flagged during the attempt, if any. */
    val authError: String? = null,
    /**
     * Access point the attempt was aimed at. Known for successful attempts, and for failed
     * ones on phones that name the access point while they are still trying.
     */
    val bssid: String? = null,
    /** True for a move between access points while already connected. */
    val roam: Boolean = false,
    /** For an attempt cut off after the access point accepted the phone: how long after. */
    val droppedAfterMs: Long? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/** An established connection that went away. */
data class ObservedDrop(
    val atMs: Long,
    val connectedForMs: Long,
    val bssid: String? = null,
    /** Last signal reading before the drop. */
    val rssiDbm: Int? = null,
)

data class TimelineSummary(
    val attempts: List<ObservedAttempt>,
    val drops: List<ObservedDrop>,
    /** True when supplicant states were seen, which is what makes the failing stage precise. */
    val detailed: Boolean,
) {
    val failures: List<ObservedAttempt> get() = attempts.filter { !it.success }
    val successes: List<ObservedAttempt> get() = attempts.filter { it.success }
    val failuresByStage: Map<FailedStage, Int>
        get() = failures.mapNotNull { it.failedAt }.groupingBy { it }.eachCount()
    val medianConnectMs: Double?
        get() = Stats.median(successes.filter { !it.roam }.map { it.durationMs.toDouble() })
}

/**
 * Rebuilds connection attempts from the stream of Wi-Fi events.
 *
 * Two sources describe an attempt. Supplicant states give the fine steps
 * (ASSOCIATING, ASSOCIATED, FOUR_WAY_HANDSHAKE, COMPLETED); network states give the coarse
 * ones (CONNECTING, OBTAINING_IPADDR, CONNECTED, DISCONNECTED). Phones differ in which they
 * report, so either alone is enough; with only network states the failing stage is coarser.
 * The two arrive through separate channels and can interleave in either order, so an attempt
 * that supplicant states are describing is only ever closed by a supplicant state.
 */
object ConnectionTimeline {
    private enum class Progress { STARTED, ASSOCIATED, KEY_EXCHANGE, KEYS_DONE, ADDRESS }

    private val START_STATES = setOf("AUTHENTICATING", "ASSOCIATING")
    private val PROGRESS_STATES = setOf("ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED")
    private val IDLE_STATES = setOf("DISCONNECTED", "INACTIVE", "INTERFACE_DISABLED", "DORMANT", "UNINITIALIZED", "INVALID")

    /** An attempt that has gone quiet for this long was abandoned without the phone saying so. */
    private const val STALE_MS = 30_000L

    /**
     * A link cut this soon after the access point accepted the phone was dropped, not timed
     * out: the key exchange normally starts within tens of milliseconds and takes seconds to
     * give up when it fails.
     */
    const val DROP_WINDOW_MS = 1_000L

    private class Open(val startMs: Long, var roam: Boolean) {
        var progress = Progress.STARTED
        var sawSupplicant = false
        var authError: String? = null
        var lastMs = startMs
        var bssid: String? = null
        var associatedAtMs: Long? = null
    }

    /**
     * @param includeTests when false, events between a TEST_START and its TEST_END are left
     *   out, so connections the app itself made do not count as the phone's own behaviour
     */
    fun summarize(events: List<WifiEvent>, includeTests: Boolean = true): TimelineSummary {
        val attempts = ArrayList<ObservedAttempt>()
        val drops = ArrayList<ObservedDrop>()
        var open: Open? = null
        var connectedSince: Long? = null
        var connectedBssid: String? = null
        var lastRssi: Int? = null
        var detailed = false
        var inTest = false

        fun fail(attempt: Open, atMs: Long) {
            val sinceAccepted = attempt.associatedAtMs?.let { atMs - it }
            val dropped = attempt.progress == Progress.ASSOCIATED && sinceAccepted != null && sinceAccepted < DROP_WINDOW_MS
            val stage = when (attempt.progress) {
                Progress.STARTED -> if (attempt.sawSupplicant) FailedStage.JOINING else FailedStage.BEFORE_IP_ADDRESS
                Progress.ASSOCIATED -> if (dropped) FailedStage.DROPPED else FailedStage.KEY_EXCHANGE
                Progress.KEY_EXCHANGE -> FailedStage.KEY_EXCHANGE
                Progress.KEYS_DONE, Progress.ADDRESS -> FailedStage.IP_ADDRESS
            }
            attempts += ObservedAttempt(
                attempt.startMs, atMs, success = false, failedAt = stage, authError = attempt.authError, bssid = attempt.bssid, roam = attempt.roam,
                droppedAfterMs = sinceAccepted.takeIf { dropped },
            )
        }

        fun succeed(attempt: Open, atMs: Long, bssid: String?) {
            attempts += ObservedAttempt(attempt.startMs, atMs, success = true, authError = attempt.authError, bssid = bssid ?: attempt.bssid, roam = attempt.roam)
        }

        fun markConnected(atMs: Long, bssid: String?) {
            if (connectedSince == null) connectedSince = atMs
            if (bssid != null) connectedBssid = bssid
        }

        fun dropIfConnected(atMs: Long) {
            val since = connectedSince ?: return
            drops += ObservedDrop(atMs, (atMs - since).coerceAtLeast(0), connectedBssid, lastRssi)
            connectedSince = null
            connectedBssid = null
        }

        fun reset() {
            open = null
            connectedSince = null
            connectedBssid = null
        }

        for (event in events.sortedBy { it.timeMs }) {
            if (event.kind == WifiEventKind.TEST_START) {
                inTest = true
                // Whatever was going on is cut short by the test; it is not the phone's doing.
                if (!includeTests) reset()
                continue
            }
            if (event.kind == WifiEventKind.TEST_END) {
                inTest = false
                if (!includeTests) reset()
                continue
            }
            if (inTest && !includeTests) continue

            event.rssiDbm?.let { lastRssi = it }
            when (event.kind) {
                WifiEventKind.SUPPLICANT -> {
                    detailed = true
                    val state = event.value.uppercase()
                    val current = open
                    when (state) {
                        in START_STATES -> {
                            // Starting over after getting further, after a long silence, or
                            // at a different access point means the previous try failed
                            // without a word.
                            val elsewhere = current?.bssid != null && event.bssid != null && !current.bssid.equals(event.bssid, ignoreCase = true)
                            if (current != null && (current.progress > Progress.STARTED || event.timeMs - current.lastMs > STALE_MS || elsewhere)) {
                                fail(current, event.timeMs)
                                open = null
                            }
                            val attempt = open ?: Open(event.timeMs, roam = connectedSince != null).also { open = it }
                            attempt.sawSupplicant = true
                            attempt.lastMs = event.timeMs
                            event.bssid?.let { attempt.bssid = it }
                        }
                        in PROGRESS_STATES -> {
                            val attempt = current ?: Open(event.timeMs, roam = connectedSince != null).also { open = it }
                            attempt.sawSupplicant = true
                            attempt.lastMs = event.timeMs
                            event.bssid?.let { attempt.bssid = it }
                            val reached = when (state) {
                                "ASSOCIATED" -> Progress.ASSOCIATED
                                "COMPLETED" -> Progress.KEYS_DONE
                                else -> Progress.KEY_EXCHANGE
                            }
                            if (reached > attempt.progress) attempt.progress = reached
                            if (state == "ASSOCIATED" && attempt.associatedAtMs == null) attempt.associatedAtMs = event.timeMs
                            // A roam keeps its IP address, so finishing the key exchange completes it.
                            if (state == "COMPLETED" && attempt.roam) {
                                succeed(attempt, event.timeMs, event.bssid)
                                event.bssid?.let { connectedBssid = it }
                                open = null
                            }
                        }
                        in IDLE_STATES -> {
                            if (current != null) {
                                fail(current, event.timeMs)
                                open = null
                            }
                            // The link itself is down, whether or not a new attempt was under way.
                            dropIfConnected(event.timeMs)
                        }
                        // SCANNING and anything unknown say nothing about an attempt.
                    }
                }
                WifiEventKind.NETWORK -> {
                    val current = open
                    when (event.value.uppercase()) {
                        "CONNECTING", "AUTHENTICATING" -> {
                            if (current == null) open = Open(event.timeMs, roam = false)
                        }
                        "OBTAINING_IPADDR" -> {
                            val attempt = current ?: Open(event.timeMs, roam = false).also { open = it }
                            attempt.progress = Progress.ADDRESS
                            attempt.lastMs = event.timeMs
                        }
                        "CONNECTED" -> {
                            if (current != null && !current.roam) {
                                succeed(current, event.timeMs, event.bssid)
                                open = null
                            }
                            markConnected(event.timeMs, event.bssid)
                        }
                        "DISCONNECTED", "FAILED" -> {
                            if (current != null && !current.sawSupplicant) {
                                fail(current, event.timeMs)
                                open = null
                            } else {
                                // This may be late news about the previous link while the
                                // supplicant is already describing the next attempt.
                                dropIfConnected(event.timeMs)
                                current?.roam = false
                            }
                        }
                    }
                }
                WifiEventKind.AUTH_ERROR -> open?.authError = event.value
                WifiEventKind.LINK_UP -> {
                    val current = open
                    if (current != null && !current.roam) {
                        succeed(current, event.timeMs, event.bssid)
                        open = null
                    }
                    markConnected(event.timeMs, event.bssid)
                }
                WifiEventKind.LINK_DOWN -> {
                    dropIfConnected(event.timeMs)
                    open?.roam = false
                }
                WifiEventKind.WIFI_SWITCH -> if (event.value.equals("off", ignoreCase = true)) {
                    // Turning Wi-Fi off is the user's doing, not a failure or a drop.
                    reset()
                }
                WifiEventKind.NOTE, WifiEventKind.TEST_START, WifiEventKind.TEST_END -> Unit
            }
        }
        return TimelineSummary(attempts, drops, detailed)
    }
}
