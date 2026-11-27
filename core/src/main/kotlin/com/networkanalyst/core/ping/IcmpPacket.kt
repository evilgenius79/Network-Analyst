package com.networkanalyst.core.ping

import java.nio.ByteBuffer

/**
 * Builds and parses ICMP echo messages for use with unprivileged "ping sockets"
 * (SOCK_DGRAM + IPPROTO_ICMP), which is how Android lets ordinary apps ping.
 *
 * On such a socket the kernel owns the identifier field (it overwrites it with the socket's
 * local port) and fills in the checksum, and received data starts at the ICMP header with no
 * IP header in front. Replies are therefore matched on sequence number and payload only.
 */
object IcmpPacket {
    const val TYPE_ECHO_REQUEST_V4 = 8
    const val TYPE_ECHO_REPLY_V4 = 0
    const val TYPE_ECHO_REQUEST_V6 = 128
    const val TYPE_ECHO_REPLY_V6 = 129

    private const val HEADER_SIZE = 8
    /** Payload: 8-byte session nonce followed by the 8-byte send time. */
    const val PAYLOAD_SIZE = 16

    data class EchoReply(val sequence: Int, val nonce: Long, val sentAtNanos: Long)

    fun buildEchoRequest(sequence: Int, nonce: Long, sentAtNanos: Long, ipv6: Boolean = false): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + PAYLOAD_SIZE)
        buffer.put((if (ipv6) TYPE_ECHO_REQUEST_V6 else TYPE_ECHO_REQUEST_V4).toByte())
        buffer.put(0) // code
        buffer.putShort(0) // checksum placeholder
        buffer.putShort(0) // identifier, assigned by the kernel
        buffer.putShort((sequence and 0xFFFF).toShort())
        buffer.putLong(nonce)
        buffer.putLong(sentAtNanos)
        val bytes = buffer.array()
        if (!ipv6) {
            // The kernel recomputes this for ping sockets, but a correct value keeps the
            // packet valid on any stack that does not. (ICMPv6 needs a pseudo-header and is
            // always computed by the kernel.)
            val checksum = checksum(bytes, bytes.size)
            bytes[2] = (checksum ushr 8).toByte()
            bytes[3] = checksum.toByte()
        }
        return bytes
    }

    /** Parses an echo reply, returning null for anything else (errors, other sessions, runts). */
    fun parseEchoReply(data: ByteArray, length: Int, ipv6: Boolean = false): EchoReply? {
        if (length < HEADER_SIZE + PAYLOAD_SIZE || data.size < length) return null
        val expectedType = if (ipv6) TYPE_ECHO_REPLY_V6 else TYPE_ECHO_REPLY_V4
        if ((data[0].toInt() and 0xFF) != expectedType || data[1].toInt() != 0) return null
        val buffer = ByteBuffer.wrap(data, 0, length)
        val sequence = buffer.getShort(6).toInt() and 0xFFFF
        return EchoReply(sequence, buffer.getLong(HEADER_SIZE), buffer.getLong(HEADER_SIZE + 8))
    }

    /** RFC 1071 internet checksum over [length] bytes. */
    fun checksum(data: ByteArray, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < length) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
