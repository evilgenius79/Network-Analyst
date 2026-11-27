package com.networkanalyst.core.discovery

import com.networkanalyst.core.model.GatewayInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit

data class UpnpService(val serviceType: String, val controlUrl: String)

data class UpnpDescription(
    val friendlyName: String?,
    val manufacturer: String?,
    val modelName: String?,
    val deviceType: String?,
    val services: List<UpnpService>,
) {
    val isInternetGateway: Boolean
        get() = deviceType?.contains(":InternetGatewayDevice:", ignoreCase = true) == true ||
            services.any { it.serviceType.startsWith(UpnpXml.SERVICE_WAN_COMMON, ignoreCase = true) }

    fun service(typePrefix: String): UpnpService? = services.firstOrNull { it.serviceType.startsWith(typePrefix, ignoreCase = true) }
}

/**
 * The small amount of XML handling UPnP needs. Device descriptions and SOAP replies are flat,
 * predictable documents, so elements are pulled out by name instead of running a full XML
 * parser over data that comes from arbitrary devices on the network (which also rules out
 * entity-expansion tricks).
 */
object UpnpXml {
    const val SERVICE_WAN_COMMON = "urn:schemas-upnp-org:service:WANCommonInterfaceConfig:"
    const val SERVICE_WAN_IP = "urn:schemas-upnp-org:service:WANIPConnection:"
    const val SERVICE_WAN_PPP = "urn:schemas-upnp-org:service:WANPPPConnection:"

    private const val MAX_DOCUMENT_CHARS = 512 * 1024

    /** Text of the first element named [tag] (any namespace prefix), unescaped; null if absent or empty. */
    fun firstElement(xml: String, tag: String): String? =
        Regex("<(?:[A-Za-z0-9_]+:)?${Regex.escape(tag)}(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?${Regex.escape(tag)}\\s*>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

    private fun elements(xml: String, tag: String): List<String> =
        Regex("<${Regex.escape(tag)}(?:\\s[^>]*)?>(.*?)</${Regex.escape(tag)}\\s*>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.toList()

    fun unescape(text: String): String {
        var value = text
        val cdata = Regex("^\\s*<!\\[CDATA\\[(.*)]]>\\s*$", RegexOption.DOT_MATCHES_ALL).find(value)
        if (cdata != null) return cdata.groupValues[1]
        value = value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        return value.replace("&amp;", "&")
    }

    /**
     * Parses a device description. Control URLs are resolved against the document's URLBase
     * when it has one, otherwise against [descriptionUrl], as the UPnP architecture specifies.
     */
    fun parseDescription(xml: String, descriptionUrl: String): UpnpDescription? {
        if (xml.length > MAX_DOCUMENT_CHARS || !xml.contains("<device", ignoreCase = true)) return null
        val base = firstElement(xml, "URLBase") ?: descriptionUrl
        val services = elements(xml, "service").mapNotNull { block ->
            val type = firstElement(block, "serviceType") ?: return@mapNotNull null
            val control = firstElement(block, "controlURL") ?: return@mapNotNull null
            val resolved = resolve(base, control) ?: return@mapNotNull null
            UpnpService(type, resolved)
        }
        return UpnpDescription(
            friendlyName = firstElement(xml, "friendlyName"),
            manufacturer = firstElement(xml, "manufacturer"),
            modelName = firstElement(xml, "modelName"),
            deviceType = firstElement(xml, "deviceType"),
            services = services,
        )
    }

    private fun resolve(base: String, relative: String): String? = try {
        URI(base).resolve(relative.trim()).toString().takeIf { it.startsWith("http://") || it.startsWith("https://") }
    } catch (_: Exception) {
        null
    }

    fun soapEnvelope(serviceType: String, action: String): String =
        "<?xml version=\"1.0\"?>\r\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$serviceType\"></u:$action></s:Body></s:Envelope>\r\n"
}

/** Talks HTTP/SOAP to UPnP devices on the local network. */
class UpnpClient(baseClient: OkHttpClient = OkHttpClient()) {
    private val client = baseClient.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /** Downloads and parses the description at [location]. Null on any failure. */
    fun fetchDescription(location: String): UpnpDescription? = try {
        client.newCall(Request.Builder().url(location).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            // Bound the read: this is an arbitrary device on the network.
            val text = response.body.source().let { source ->
                source.request(MAX_BODY_BYTES)
                source.buffer.snapshot(minOf(source.buffer.size, MAX_BODY_BYTES).toInt()).utf8()
            }
            UpnpXml.parseDescription(text, location)
        }
    } catch (_: Exception) {
        null
    }

    /** Invokes an argument-less SOAP action and returns the raw response body, or null on failure. */
    fun invoke(service: UpnpService, action: String): String? = try {
        val body = UpnpXml.soapEnvelope(service.serviceType, action).toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())
        val request = Request.Builder()
            .url(service.controlUrl)
            .header("SOAPAction", "\"${service.serviceType}#$action\"")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body.source()
            source.request(MAX_BODY_BYTES)
            source.buffer.snapshot(minOf(source.buffer.size, MAX_BODY_BYTES).toInt()).utf8()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Asks an Internet Gateway Device (the router) about its WAN side: link rates, public
     * address, connection state, and how much traffic is crossing it right now. The traffic
     * figure comes from reading the router's byte counters twice, [trafficSampleMs] apart, so
     * the phone must stay quiet in between for it to mean "other devices".
     */
    suspend fun queryGateway(description: UpnpDescription, trafficSampleMs: Long = 3_000): GatewayInfo = withContext(Dispatchers.IO) {
        val common = description.service(UpnpXml.SERVICE_WAN_COMMON)
        val connection = description.service(UpnpXml.SERVICE_WAN_IP) ?: description.service(UpnpXml.SERVICE_WAN_PPP)

        val link = common?.let { invoke(it, "GetCommonLinkProperties") }
        val status = connection?.let { invoke(it, "GetStatusInfo") }
        val external = connection?.let { invoke(it, "GetExternalIPAddress") }

        var downBps: Double? = null
        var upBps: Double? = null
        if (common != null && trafficSampleMs > 0) {
            val t0 = System.nanoTime()
            val rx0 = counter(common, "GetTotalBytesReceived", "NewTotalBytesReceived")
            val tx0 = counter(common, "GetTotalBytesSent", "NewTotalBytesSent")
            if (rx0 != null || tx0 != null) {
                delay(trafficSampleMs)
                val rx1 = counter(common, "GetTotalBytesReceived", "NewTotalBytesReceived")
                val tx1 = counter(common, "GetTotalBytesSent", "NewTotalBytesSent")
                val seconds = (System.nanoTime() - t0) / 1_000_000_000.0
                downBps = counterRate(rx0, rx1, seconds)
                upBps = counterRate(tx0, tx1, seconds)
            }
        }

        GatewayInfo(
            friendlyName = description.friendlyName,
            manufacturer = description.manufacturer,
            modelName = description.modelName,
            externalIp = external?.let { UpnpXml.firstElement(it, "NewExternalIPAddress") },
            wanAccessType = link?.let { UpnpXml.firstElement(it, "NewWANAccessType") },
            maxDownstreamBps = link?.let { UpnpXml.firstElement(it, "NewLayer1DownstreamMaxBitRate") }?.toLongOrNull()?.takeIf { it > 0 },
            maxUpstreamBps = link?.let { UpnpXml.firstElement(it, "NewLayer1UpstreamMaxBitRate") }?.toLongOrNull()?.takeIf { it > 0 },
            physicalLinkStatus = link?.let { UpnpXml.firstElement(it, "NewPhysicalLinkStatus") },
            connectionStatus = status?.let { UpnpXml.firstElement(it, "NewConnectionStatus") },
            uptimeSeconds = status?.let { UpnpXml.firstElement(it, "NewUptime") }?.toLongOrNull(),
            backgroundDownBps = downBps,
            backgroundUpBps = upBps,
        )
    }

    private fun counter(service: UpnpService, action: String, argument: String): Long? =
        invoke(service, action)?.let { UpnpXml.firstElement(it, argument) }?.toLongOrNull()

    companion object {
        private const val MAX_BODY_BYTES = 256L * 1024
        private const val MAX_PLAUSIBLE_BPS = 10_000_000_000.0

        /**
         * Bits per second between two readings of a UPnP byte counter. The specification
         * makes these 32-bit, so they wrap at 4 GiB and a smaller second reading means one
         * wrap. Some routers report wider values; for those a decrease can only be a reset.
         */
        fun counterRate(first: Long?, second: Long?, seconds: Double): Double? {
            if (first == null || second == null || seconds <= 0 || first < 0 || second < 0) return null
            val wraps32 = first < (1L shl 32) && second < (1L shl 32)
            if (second < first && !wraps32) return null
            val delta = if (second >= first) second - first else second + (1L shl 32) - first
            val bitsPerSecond = delta * 8 / seconds
            // A router that resets or uses a different counter width would produce nonsense here.
            return bitsPerSecond.takeIf { it <= MAX_PLAUSIBLE_BPS }
        }
    }
}
