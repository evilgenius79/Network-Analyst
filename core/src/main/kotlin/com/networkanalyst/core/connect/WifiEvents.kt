package com.networkanalyst.core.connect

import kotlinx.serialization.Serializable

/** What kind of thing was observed about the phone's Wi-Fi. */
@Serializable
enum class WifiEventKind {
    /** The Wi-Fi supplicant changed state; [WifiEvent.value] is the state name, e.g. "ASSOCIATING". */
    SUPPLICANT,

    /** The network's overall state changed; value is "CONNECTING", "OBTAINING_IPADDR", "CONNECTED" or "DISCONNECTED". */
    NETWORK,

    /** The system flagged an authentication error; value is "WRONG_PASSWORD", "TIMEOUT", "EAP" or "UNSPECIFIED". */
    AUTH_ERROR,

    /** Wi-Fi came up with a usable link; carries the access point it is on. */
    LINK_UP,

    /** The Wi-Fi link went away. */
    LINK_DOWN,

    /** Wi-Fi was switched; value is "on" or "off". */
    WIFI_SWITCH,

    /** A remark for the log, such as monitoring starting; value is free text. */
    NOTE,

    /** The app's own connection test began; what follows until [TEST_END] is the app's doing. */
    TEST_START,
    TEST_END,
}

/** One timestamped observation. A run of these is what the connection timeline is built from. */
@Serializable
data class WifiEvent(
    val timeMs: Long,
    val kind: WifiEventKind,
    val value: String = "",
    val ssid: String? = null,
    val bssid: String? = null,
    val rssiDbm: Int? = null,
    val frequencyMhz: Int? = null,
)

/**
 * Where a connection attempt stopped. Joining a protected Wi-Fi network happens in this order:
 * the access point accepts the phone (association), both sides derive encryption keys from the
 * password (key exchange), and the phone is given an IP address.
 */
enum class FailedStage(val label: String, val explanation: String, /** A few words, for lists. */ val brief: String) {
    JOINING(
        "Joining the access point",
        "The access point did not accept the phone. It was out of reach, busy, at its device limit, or deliberately ignoring the phone to push it to another access point or band.",
        "refused when joining",
    ),
    KEY_EXCHANGE(
        "Exchanging encryption keys",
        "The access point accepted the phone but the two could not agree on encryption keys. That points to the password or security settings, or to a signal too weak for the exchange to complete.",
        "key exchange failed",
    ),
    IP_ADDRESS(
        "Getting an IP address",
        "The phone joined the Wi-Fi network but was not given an IP address. That is the router's address service (DHCP) not answering or having no addresses left.",
        "no IP address",
    ),
    BEFORE_IP_ADDRESS(
        "Joining or exchanging keys",
        "The attempt failed before the phone asked for an IP address. This phone does not report whether it was the access point refusing the phone or the key exchange failing.",
        "failed before the IP address step",
    ),
}
