package com.networkanalyst.core.connect

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reads an event log in the form the app shares it ([WatchLogText.build]) back into events,
 * so that logs sent in from real phones can be kept as test cases.
 */
object LogFixture {
    private val LINE = Regex("""^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})  (.*?)(?:  \((.*)\))?$""")
    private val ADDRESS = Regex("""(?:[0-9a-f]{2}:){5}[0-9a-f]{2}""")

    fun load(resource: String): List<WifiEvent> =
        parse(requireNotNull(javaClass.classLoader.getResource(resource)) { "missing test resource $resource" }.readText())

    fun parse(text: String): List<WifiEvent> {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return text.lineSequence().mapNotNull { line ->
            val match = LINE.matchEntire(line.trimEnd()) ?: return@mapNotNull null
            val (stamp, what, where) = match.destructured
            val time = format.parse(stamp)!!.time
            val ssid = Regex("\"([^\"]*)\"").find(where)?.groupValues?.get(1)
            val bssid = ADDRESS.find(where)?.value
            val frequency = Regex("""(\d+) MHz""").find(where)?.groupValues?.get(1)?.toInt()
            val rssi = Regex("""(-?\d+) dBm""").find(where)?.groupValues?.get(1)?.toInt()
            fun event(kind: WifiEventKind, value: String = "") = WifiEvent(time, kind, value, ssid, bssid, rssi, frequency)
            when {
                what.startsWith("Wi-Fi step: ") -> event(WifiEventKind.SUPPLICANT, what.removePrefix("Wi-Fi step: "))
                what.startsWith("Network: ") -> event(WifiEventKind.NETWORK, what.removePrefix("Network: "))
                what.startsWith("Authentication error: ") -> event(WifiEventKind.AUTH_ERROR, what.removePrefix("Authentication error: "))
                what == "Connected" -> event(WifiEventKind.LINK_UP)
                what == "Disconnected" -> event(WifiEventKind.LINK_DOWN)
                what.startsWith("Wi-Fi switched ") -> event(WifiEventKind.WIFI_SWITCH, what.removePrefix("Wi-Fi switched "))
                what.startsWith("Connection test started") -> event(WifiEventKind.TEST_START, what.substringAfter(": ", ""))
                what == "Connection test finished" -> event(WifiEventKind.TEST_END)
                else -> event(WifiEventKind.NOTE, what)
            }
        }.toList()
    }
}
