package com.networkanalyst.core.connect

/**
 * Works out what one connection request really did, from the outcome Android reported and
 * the Wi-Fi events seen while it ran.
 *
 * Android retries a requested connection silently (up to three more times) before it reports
 * a failure, so "connected" can hide failed tries and "failed" hides how each try went. The
 * events recorded during the request reveal them, on phones that broadcast connection steps.
 */
object TrialOutcome {
    data class Result(val failure: ConnectFailure?, val hiddenFailures: List<FailedStage>?)

    private val PROGRESS_STATES = setOf("ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED")

    /** A request that ends sooner than this without any attempt was refused, not answered. */
    const val INSTANT_MS = 400L

    /**
     * Failed tries visible in [windowEvents], or null when the phone showed nothing at all.
     *
     * The window opens when the request is made. If the phone happened to be in the middle of
     * reconnecting to its saved network at that moment, the tail of that unrelated attempt
     * lands in the window; steps seen before any attempt has started here are therefore dropped.
     */
    fun observedFailures(windowEvents: List<WifiEvent>, targetBssid: String? = null): List<FailedStage>? {
        val relevant = windowEvents.sortedBy { it.timeMs }
            .filter { it.kind != WifiEventKind.TEST_START && it.kind != WifiEventKind.TEST_END }
        if (relevant.none { it.kind == WifiEventKind.SUPPLICANT || it.kind == WifiEventKind.NETWORK }) return null
        val own = relevant.dropWhile { event ->
            when (event.kind) {
                WifiEventKind.SUPPLICANT -> event.value.uppercase() in PROGRESS_STATES
                WifiEventKind.NETWORK -> event.value.uppercase() == "OBTAINING_IPADDR"
                else -> true
            }
        }
        return ConnectionTimeline.summarize(own).attempts
            .filter { !it.success }
            // Between two requests the phone may start rejoining its saved network by itself,
            // and the next request cuts that short. A try known to have been aimed at another
            // access point is the phone's own doing, not a retry of this request.
            .filter { targetBssid == null || it.bssid == null || it.bssid.equals(targetBssid, ignoreCase = true) }
            .mapNotNull { it.failedAt }
    }

    fun success(windowEvents: List<WifiEvent>, targetBssid: String? = null): Result = Result(null, observedFailures(windowEvents, targetBssid))

    /**
     * @param reported the stage Android gave for the failure, when it gave one
     * @param timedOut the app's own time limit ran out before Android reported anything
     * @param approvalExpected Android had to ask the user to allow this request
     * @param stageReporting this version of Android reports a stage for every connection failure
     * @param elapsedMs how long the request lasted, when known
     * @param targetBssid the access point the request named, to tell its tries from the phone's own
     */
    fun failure(
        reported: ConnectFailure?,
        timedOut: Boolean,
        windowEvents: List<WifiEvent>,
        approvalExpected: Boolean,
        stageReporting: Boolean,
        elapsedMs: Long? = null,
        targetBssid: String? = null,
    ): Result {
        val observed = observedFailures(windowEvents, targetBssid)
        // The last failed try is the failure being reported, not a silent retry before it.
        val hidden = observed?.dropLast(1)
        val failure = when {
            // Android sometimes reports a failure without a stage ("unknown"). The steps the
            // phone was seen taking then say more than Android did.
            reported != null && (reported != ConnectFailure.UNKNOWN || observed.isNullOrEmpty()) -> reported
            // Also when time ran out while Android was still retrying: the tries seen until
            // then show where it keeps failing, which says more than "timed out".
            !observed.isNullOrEmpty() -> when (observed.last()) {
                FailedStage.JOINING -> ConnectFailure.ASSOCIATION
                FailedStage.KEY_EXCHANGE -> ConnectFailure.AUTHENTICATION
                FailedStage.IP_ADDRESS -> ConnectFailure.IP_ADDRESS
                FailedStage.BEFORE_IP_ADDRESS -> if (timedOut) ConnectFailure.TIMED_OUT else ConnectFailure.UNKNOWN
            }
            timedOut -> ConnectFailure.TIMED_OUT
            // Nothing was attempted and the answer came faster than a person could tap a dialog
            // or a scan could run: Android refused the request outright. It does that when the
            // app is not on screen.
            elapsedMs != null && elapsedMs < INSTANT_MS -> ConnectFailure.REJECTED
            // Nothing was attempted. Where every connection failure comes with a stage, or the
            // phone's steps are visible and showed no attempt, the request ended before any
            // connecting began: it was turned down in the dialog, or the access point was
            // never found.
            stageReporting || observed != null -> if (approvalExpected) ConnectFailure.DECLINED else ConnectFailure.NOT_FOUND
            else -> ConnectFailure.UNKNOWN
        }
        return Result(failure, hidden)
    }
}
