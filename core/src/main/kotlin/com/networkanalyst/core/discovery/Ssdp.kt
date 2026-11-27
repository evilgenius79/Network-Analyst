package com.networkanalyst.core.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** One device's answer to an SSDP search. */
data class SsdpResponse(
    val address: String,
    /** URL of the device's UPnP description document. */
    val location: String?,
    val server: String?,
    val searchTarget: String?,
    val usn: String?,
)

/**
 * SSDP (Simple Service Discovery Protocol), the discovery half of UPnP. A search is one UDP
 * datagram to the multicast group 239.255.255.250:1900; every UPnP device answers with a
 * unicast HTTP-over-UDP response naming the URL of its description.
 */
object Ssdp {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val TARGET_ALL = "ssdp:all"
    const val TARGET_ROOT_DEVICE = "upnp:rootdevice"
    const val TARGET_GATEWAY = "urn:schemas-upnp-org:device:InternetGatewayDevice:1"

    fun buildSearch(searchTarget: String, mxSeconds: Int = 2): ByteArray =
        (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $MULTICAST_ADDRESS:$PORT\r\n" +
                "ST: $searchTarget\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $mxSeconds\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)

    /** Parses a search response. Returns null for anything that is not a "200 OK" reply. */
    fun parseResponse(data: ByteArray, length: Int, fromAddress: String): SsdpResponse? {
        val text = String(data, 0, length, Charsets.UTF_8)
        val lines = text.split("\r\n", "\n")
        val status = lines.firstOrNull()?.trim() ?: return null
        if (!status.startsWith("HTTP/", ignoreCase = true) || status.split(' ').getOrNull(1) != "200") return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return SsdpResponse(
            address = fromAddress,
            location = headers["location"]?.takeIf { it.isNotEmpty() },
            server = headers["server"]?.takeIf { it.isNotEmpty() },
            searchTarget = headers["st"]?.takeIf { it.isNotEmpty() },
            usn = headers["usn"]?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Sends a search for each target and collects responses for [listenMs].
     * Responses arrive as unicast to the sending socket, so no multicast group membership is needed.
     */
    suspend fun search(
        socketFactory: () -> DatagramSocket,
        searchTargets: List<String> = listOf(TARGET_ALL, TARGET_GATEWAY),
        listenMs: Long = 3_000,
        mxSeconds: Int = 2,
        destination: InetAddress = InetAddress.getByName(MULTICAST_ADDRESS),
        port: Int = PORT,
    ): List<SsdpResponse> = withContext(Dispatchers.IO) {
        val responses = LinkedHashMap<String, SsdpResponse>()
        try {
            socketFactory().use { socket ->
                // Send every search twice: UDP multicast over Wi-Fi is easily lost.
                repeat(2) {
                    for (target in searchTargets) {
                        val message = buildSearch(target, mxSeconds)
                        socket.send(DatagramPacket(message, message.size, destination, port))
                    }
                }
                val deadline = System.nanoTime() + listenMs * 1_000_000L
                val buffer = ByteArray(2048)
                while (true) {
                    val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val from = packet.address?.hostAddress ?: continue
                    val response = parseResponse(buffer, packet.length, from) ?: continue
                    // Devices answer once per service; keep one entry per device and description.
                    responses.putIfAbsent("$from|${response.location}", response)
                }
            }
        } catch (_: Exception) {
            // No permission for local traffic, interface gone, ...: return what was collected.
        }
        responses.values.toList()
    }
}
