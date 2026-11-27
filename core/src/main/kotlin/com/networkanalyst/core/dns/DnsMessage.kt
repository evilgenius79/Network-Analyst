package com.networkanalyst.core.dns

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/** Record payloads this app understands; everything else is kept as [Other]. */
sealed interface RData {
    data class Address(val address: String) : RData
    data class Name(val name: String) : RData
    data class Text(val entries: List<String>) : RData
    data class Service(val priority: Int, val weight: Int, val port: Int, val target: String) : RData
    data class Other(val length: Int) : RData
}

data class DnsQuestion(val name: String, val type: Int)

data class DnsRecord(val name: String, val type: Int, val ttlSeconds: Long, val data: RData)

data class DnsPacket(
    val id: Int,
    val isResponse: Boolean,
    val rcode: Int,
    val truncated: Boolean,
    val questions: List<DnsQuestion>,
    val answers: List<DnsRecord>,
    val authorities: List<DnsRecord>,
    val additionals: List<DnsRecord>,
) {
    val allRecords: List<DnsRecord> get() = answers + authorities + additionals
}

/**
 * Minimal DNS wire-format encoder/decoder (RFC 1035), enough to time resolvers and to read
 * mDNS service announcements (RFC 6762), which use the same message layout.
 */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33

    const val RCODE_NOERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    const val RCODE_REFUSED = 5

    private const val CLASS_IN = 1
    private const val MAX_POINTER_JUMPS = 32

    /**
     * Builds a single-question query.
     * [unicastResponse] sets the mDNS "QU" bit asking responders to reply directly to the sender.
     */
    fun buildQuery(
        id: Int,
        name: String,
        type: Int,
        recursionDesired: Boolean = true,
        unicastResponse: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream(32 + name.length)
        out.writeShort(id and 0xFFFF)
        out.writeShort(if (recursionDesired) 0x0100 else 0)
        out.writeShort(1) // questions
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(0)
        writeName(out, name)
        out.writeShort(type)
        out.writeShort(if (unicastResponse) CLASS_IN or 0x8000 else CLASS_IN)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value ushr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        val trimmed = name.trim('.')
        if (trimmed.isNotEmpty()) {
            for (label in trimmed.split('.')) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                require(bytes.isNotEmpty() && bytes.size <= 63) { "invalid DNS label in '$name'" }
                out.write(bytes.size)
                out.write(bytes)
            }
        }
        out.write(0)
    }

    /** Parses a DNS message. Returns null if the data is truncated or malformed. */
    fun parse(data: ByteArray, length: Int = data.size): DnsPacket? {
        if (length < 12 || length > data.size) return null
        return try {
            val reader = Reader(data, length)
            val id = reader.u16()
            val flags = reader.u16()
            val questionCount = reader.u16()
            val answerCount = reader.u16()
            val authorityCount = reader.u16()
            val additionalCount = reader.u16()
            val questions = List(questionCount) {
                val name = reader.name()
                val type = reader.u16()
                reader.u16() // class
                DnsQuestion(name, type)
            }
            DnsPacket(
                id = id,
                isResponse = flags and 0x8000 != 0,
                rcode = flags and 0x000F,
                truncated = flags and 0x0200 != 0,
                questions = questions,
                answers = List(answerCount) { reader.record() },
                authorities = List(authorityCount) { reader.record() },
                additionals = List(additionalCount) { reader.record() },
            )
        } catch (_: MalformedDnsException) {
            null
        }
    }

    private class MalformedDnsException : Exception()

    private class Reader(private val data: ByteArray, private val length: Int) {
        var position = 0

        fun u8(): Int {
            if (position >= length) throw MalformedDnsException()
            return data[position++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun name(): String {
            val (name, next) = readName(position)
            position = next
            return name
        }

        /** Reads a possibly compressed name starting at [start]; returns it with the offset just past it. */
        private fun readName(start: Int): Pair<String, Int> {
            val labels = ArrayList<String>()
            var cursor = start
            var endOfName = -1
            var jumps = 0
            while (true) {
                if (cursor >= length) throw MalformedDnsException()
                val len = data[cursor].toInt() and 0xFF
                when {
                    len == 0 -> {
                        cursor++
                        break
                    }
                    len and 0xC0 == 0xC0 -> {
                        if (cursor + 1 >= length) throw MalformedDnsException()
                        if (++jumps > MAX_POINTER_JUMPS) throw MalformedDnsException()
                        val target = ((len and 0x3F) shl 8) or (data[cursor + 1].toInt() and 0xFF)
                        if (endOfName < 0) endOfName = cursor + 2
                        // A pointer must go backwards; anything else is a loop or garbage.
                        if (target >= cursor) throw MalformedDnsException()
                        cursor = target
                    }
                    len and 0xC0 != 0 -> throw MalformedDnsException()
                    else -> {
                        if (cursor + 1 + len > length) throw MalformedDnsException()
                        labels += String(data, cursor + 1, len, Charsets.UTF_8)
                        cursor += 1 + len
                    }
                }
            }
            return labels.joinToString(".") to (if (endOfName >= 0) endOfName else cursor)
        }

        fun record(): DnsRecord {
            val name = name()
            val type = u16()
            u16() // class (top bit is the mDNS cache-flush flag)
            val ttl = u32()
            val dataLength = u16()
            val dataStart = position
            if (dataStart + dataLength > length) throw MalformedDnsException()
            val rdata: RData = when (type) {
                TYPE_A -> if (dataLength == 4) address(dataStart, 4) else RData.Other(dataLength)
                TYPE_AAAA -> if (dataLength == 16) address(dataStart, 16) else RData.Other(dataLength)
                TYPE_PTR, TYPE_CNAME -> RData.Name(readName(dataStart).first)
                TYPE_TXT -> text(dataStart, dataLength)
                TYPE_SRV -> if (dataLength >= 7) {
                    position = dataStart
                    val priority = u16()
                    val weight = u16()
                    val port = u16()
                    RData.Service(priority, weight, port, readName(position).first)
                } else RData.Other(dataLength)
                else -> RData.Other(dataLength)
            }
            position = dataStart + dataLength
            return DnsRecord(name, type, ttl, rdata)
        }

        private fun address(start: Int, size: Int): RData =
            RData.Address(InetAddress.getByAddress(data.copyOfRange(start, start + size)).hostAddress ?: "")

        private fun text(start: Int, dataLength: Int): RData {
            val entries = ArrayList<String>()
            var cursor = start
            val end = start + dataLength
            while (cursor < end) {
                val len = data[cursor].toInt() and 0xFF
                if (cursor + 1 + len > end) throw MalformedDnsException()
                if (len > 0) entries += String(data, cursor + 1, len, Charsets.UTF_8)
                cursor += 1 + len
            }
            return RData.Text(entries)
        }
    }
}
