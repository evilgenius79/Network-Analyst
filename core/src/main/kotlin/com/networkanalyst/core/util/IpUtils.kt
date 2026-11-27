package com.networkanalyst.core.util

/** Where an address sits, which matters when working out NAT layers. */
enum class AddressScope { LOOPBACK, LINK_LOCAL, PRIVATE, CGNAT, PUBLIC, UNSPECIFIED }

object IpUtils {
    /** Parses a strict dotted-quad IPv4 address into its 32-bit value, or null if malformed. */
    fun parseIpv4(text: String): Int? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val octet = part.toInt()
            if (octet > 255) return null
            result = (result shl 8) or octet
        }
        return result
    }

    fun formatIpv4(value: Int): String =
        "${value ushr 24 and 0xFF}.${value ushr 16 and 0xFF}.${value ushr 8 and 0xFF}.${value and 0xFF}"

    fun isIpv4(text: String): Boolean = parseIpv4(text) != null

    /** Classifies an IPv4 address. Returns null when [text] is not IPv4. */
    fun scopeOfIpv4(text: String): AddressScope? {
        val ip = parseIpv4(text) ?: return null
        fun inRange(base: String, prefix: Int) = Ipv4Subnet(parseIpv4(base)!!, prefix).contains(ip)
        return when {
            ip == 0 -> AddressScope.UNSPECIFIED
            inRange("127.0.0.0", 8) -> AddressScope.LOOPBACK
            inRange("169.254.0.0", 16) -> AddressScope.LINK_LOCAL
            inRange("10.0.0.0", 8) || inRange("172.16.0.0", 12) || inRange("192.168.0.0", 16) -> AddressScope.PRIVATE
            // RFC 6598 shared address space, used by ISPs for carrier-grade NAT.
            inRange("100.64.0.0", 10) -> AddressScope.CGNAT
            else -> AddressScope.PUBLIC
        }
    }

    /** Strips an IPv6 zone/scope suffix such as "%wlan0". */
    fun stripZone(address: String): String = address.substringBefore('%')
}

/** An IPv4 network described by any address inside it plus a prefix length. */
class Ipv4Subnet(address: Int, val prefixLength: Int) {
    init {
        require(prefixLength in 0..32) { "prefix length must be 0..32, was $prefixLength" }
    }

    val mask: Int = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
    val network: Int = address and mask
    val broadcast: Int = network or mask.inv()

    /** Number of usable host addresses (network and broadcast excluded for prefixes shorter than /31). */
    val hostCount: Long
        get() {
            val total = 1L shl (32 - prefixLength)
            return if (prefixLength >= 31) total else total - 2
        }

    fun contains(ip: Int): Boolean = (ip and mask) == network

    /**
     * Usable host addresses in ascending order. When the subnet is larger than [limit] hosts,
     * the window of [limit] addresses nearest to [around] is returned so a scan of a big
     * network still covers the neighbourhood of the device.
     */
    fun hosts(limit: Int = Int.MAX_VALUE, around: Int = network): List<Int> {
        val first = if (prefixLength >= 31) network.toUInt().toLong() else network.toUInt().toLong() + 1
        val last = if (prefixLength >= 31) broadcast.toUInt().toLong() else broadcast.toUInt().toLong() - 1
        if (last < first) return emptyList()
        val count = last - first + 1
        if (count <= limit) return (first..last).map { it.toInt() }
        val center = around.toUInt().toLong().coerceIn(first, last)
        var start = center - limit / 2
        if (start < first) start = first
        if (start + limit - 1 > last) start = last - limit + 1
        return (start until start + limit).map { it.toInt() }
    }

    override fun toString(): String = "${IpUtils.formatIpv4(network)}/$prefixLength"

    companion object {
        /** Parses "192.168.1.10/24". Returns null when malformed. */
        fun parse(cidr: String): Ipv4Subnet? {
            val address = IpUtils.parseIpv4(cidr.substringBefore('/')) ?: return null
            val prefix = cidr.substringAfter('/', "").toIntOrNull() ?: return null
            if (prefix !in 0..32) return null
            return Ipv4Subnet(address, prefix)
        }
    }
}
