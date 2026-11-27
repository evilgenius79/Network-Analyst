package com.networkanalyst.core.discovery

import java.io.ByteArrayOutputStream

/** A NetBIOS node status reply: the names a Windows-style host has registered. */
data class NetBiosInfo(
    /** Computer name (the unique name with suffix 0x00), when present. */
    val computerName: String?,
    /** Workgroup or domain (the group name with suffix 0x00), when present. */
    val group: String?,
    /** Adapter hardware address as reported by the host, or null when it is all zeros. */
    val macAddress: String?,
)

/**
 * NetBIOS Name Service node status query (RFC 1002, sections 4.2.17 and 4.2.18), sent to UDP
 * port 137. Windows PCs, NAS boxes and many printers answer with their computer name.
 */
object NetBios {
    const val PORT = 137
    private const val TYPE_NBSTAT = 0x0021
    private const val FLAG_GROUP = 0x8000

    /** Builds a node status request for the wildcard name "*". */
    fun buildNodeStatusRequest(transactionId: Int): ByteArray {
        val out = ByteArrayOutputStream(50)
        fun u16(value: Int) {
            out.write(value ushr 8 and 0xFF)
            out.write(value and 0xFF)
        }
        u16(transactionId)
        u16(0x0000) // query, no flags
        u16(1) // one question
        u16(0)
        u16(0)
        u16(0)
        // "*" padded with NULs to 16 bytes, each byte split into two nibbles and added to 'A'.
        val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
        out.write(0x20)
        for (b in raw) {
            out.write('A'.code + (b.toInt() ushr 4 and 0x0F))
            out.write('A'.code + (b.toInt() and 0x0F))
        }
        out.write(0x00)
        u16(TYPE_NBSTAT)
        u16(0x0001) // class IN
        return out.toByteArray()
    }

    /** Parses a node status response for [transactionId]. Returns null if it is not one. */
    fun parseNodeStatusResponse(data: ByteArray, length: Int, transactionId: Int): NetBiosInfo? {
        if (length < 57 || data.size < length) return null
        fun u8(i: Int) = data[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (u16(0) != (transactionId and 0xFFFF)) return null
        if (u16(2) and 0x8000 == 0) return null // not a response
        if (u16(6) < 1) return null // no answer record
        // Answer name: either a full encoded name (0x20 + 32 bytes + 0x00) or a 2-byte pointer.
        var offset = 12
        offset += if (u8(offset) and 0xC0 == 0xC0) 2 else u8(offset) + 2
        if (offset + 11 > length) return null
        if (u16(offset) != TYPE_NBSTAT) return null
        offset += 2 + 2 + 4 // type, class, ttl
        val rdLength = u16(offset)
        offset += 2
        if (offset + rdLength > length || rdLength < 1) return null
        val count = u8(offset)
        offset += 1
        if (offset + count * 18 > length) return null

        var computer: String? = null
        var group: String? = null
        repeat(count) { index ->
            val base = offset + index * 18
            val name = String(data, base, 15, Charsets.ISO_8859_1).trim { it == ' ' || it == '\u0000' }
            val suffix = u8(base + 15)
            val flags = u16(base + 16)
            if (suffix == 0x00 && name.isNotEmpty()) {
                if (flags and FLAG_GROUP != 0) {
                    if (group == null) group = name
                } else if (computer == null) {
                    computer = name
                }
            }
        }
        val macOffset = offset + count * 18
        val mac = if (macOffset + 6 <= length) {
            val bytes = (0 until 6).map { u8(macOffset + it) }
            if (bytes.all { it == 0 }) null else bytes.joinToString(":") { "%02x".format(it) }
        } else null
        return NetBiosInfo(computer, group, mac)
    }
}
