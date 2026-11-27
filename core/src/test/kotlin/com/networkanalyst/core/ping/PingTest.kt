package com.networkanalyst.core.ping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PingTest {
    // Captured verbatim from iputils ping 20240117 on Linux.
    private val iputilsOk = """
        PING 127.0.0.1 (127.0.0.1) 56(84) bytes of data.
        64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=0.029 ms
        64 bytes from 127.0.0.1: icmp_seq=2 ttl=64 time=0.026 ms
        64 bytes from 127.0.0.1: icmp_seq=3 ttl=64 time=0.030 ms

        --- 127.0.0.1 ping statistics ---
        3 packets transmitted, 3 received, 0% packet loss, time 407ms
        rtt min/avg/max/mdev = 0.026/0.028/0.030/0.001 ms
    """.trimIndent()

    // Captured verbatim: ping -c 1 -t 1 against a remote address.
    private val iputilsTtlExceeded = """
        PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.
        From 192.0.2.1 icmp_seq=1 Time to live exceeded

        --- 1.1.1.1 ping statistics ---
        1 packets transmitted, 0 received, +1 errors, 100% packet loss, time 0ms
    """.trimIndent()

    // Built from the format strings in toybox's toys/net/ping.c.
    private val toyboxOk = """
        Ping 192.168.1.1 (192.168.1.1): 56(84) bytes.
        64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=3 ms
        64 bytes from 192.168.1.1: icmp_seq=3 ttl=64 time=12 ms

        --- 192.168.1.1 ping statistics ---
        3 packets transmitted, 2 received, 33% packet loss
        round-trip min/avg/max = 3/7/12 ms
    """.trimIndent()

    @Test
    fun `parses iputils replies and summary`() {
        val parsed = PingOutputParser.parse(iputilsOk)
        assertEquals(3, parsed.replies.size)
        assertEquals(listOf(0.029, 0.026, 0.030), parsed.replies.map { it.timeMs })
        assertEquals(listOf(1, 2, 3), parsed.replies.map { it.sequence })
        assertEquals("127.0.0.1", parsed.replies[0].from)
        assertEquals(64, parsed.replies[0].ttl)
        assertEquals(3, parsed.transmitted)
        assertEquals(3, parsed.received)
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun `parses a ttl exceeded hop`() {
        val parsed = PingOutputParser.parse(iputilsTtlExceeded)
        assertTrue(parsed.replies.isEmpty())
        assertEquals(1, parsed.errors.size)
        val hop = parsed.errors[0]
        assertEquals("192.0.2.1", hop.from)
        assertEquals(1, hop.sequence)
        assertTrue(hop.isTtlExceeded)
        assertEquals(1, parsed.transmitted)
        assertEquals(0, parsed.received)
    }

    @Test
    fun `parses toybox output`() {
        val parsed = PingOutputParser.parse(toyboxOk)
        assertEquals(listOf(3.0, 12.0), parsed.replies.map { it.timeMs })
        assertEquals(3, parsed.transmitted)
        assertEquals(2, parsed.received)
    }

    @Test
    fun `parses hostname forms and colon variant of errors`() {
        val parsed = PingOutputParser.parse(
            """
            64 bytes from one.one.one.one (1.1.1.1): icmp_seq=2 ttl=57 time=12.3 ms
            From router.lan (192.168.1.1): icmp_seq=4 Destination Host Unreachable
            From 10.0.0.1: icmp_seq=5 Frag needed and DF set (mtu = 1400)
            64 bytes from 8.8.8.8: icmp_seq=6 ttl=117 time<1 ms
            """.trimIndent(),
        )
        assertEquals("1.1.1.1", parsed.replies[0].from)
        assertEquals(12.3, parsed.replies[0].timeMs, 1e-9)
        assertEquals(1.0, parsed.replies[1].timeMs, 1e-9)
        assertEquals("192.168.1.1", parsed.errors[0].from)
        assertEquals("Destination Host Unreachable", parsed.errors[0].message)
        assertTrue(parsed.errors[1].isFragmentationNeeded)
        assertEquals("10.0.0.1", parsed.errors[1].from)
    }

    @Test
    fun `garbage input yields nothing`() {
        val parsed = PingOutputParser.parse("ping: unknown host example.invalid\n")
        assertTrue(parsed.replies.isEmpty())
        assertTrue(parsed.errors.isEmpty())
        assertNull(parsed.transmitted)
    }

    @Test
    fun `echo request layout and checksum`() {
        val packet = IcmpPacket.buildEchoRequest(sequence = 7, nonce = 0x1122334455667788, sentAtNanos = 42)
        assertEquals(8 + IcmpPacket.PAYLOAD_SIZE, packet.size)
        assertEquals(8, packet[0].toInt())
        assertEquals(0, packet[1].toInt())
        assertEquals(0, packet[6].toInt())
        assertEquals(7, packet[7].toInt())
        // A packet with a correct checksum sums to zero when checksummed again.
        assertEquals(0, IcmpPacket.checksum(packet, packet.size))
    }

    @Test
    fun `checksum matches a known example`() {
        // Classic worked example: bytes 00 01 f2 03 f4 f5 f6 f7 -> checksum 0x220d.
        val data = byteArrayOf(0x00, 0x01, 0xf2.toByte(), 0x03, 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte())
        assertEquals(0x220d, IcmpPacket.checksum(data, data.size))
    }

    @Test
    fun `reply parsing accepts only matching echo replies`() {
        val request = IcmpPacket.buildEchoRequest(sequence = 513, nonce = 99, sentAtNanos = 123456789)
        // The kernel turns the request around: type 8 -> 0, identifier rewritten, payload intact.
        val reply = request.copyOf().also {
            it[0] = 0
            it[4] = 0xc6.toByte()
            it[5] = 0xb9.toByte()
        }
        val parsed = IcmpPacket.parseEchoReply(reply, reply.size)
        assertNotNull(parsed)
        assertEquals(513, parsed!!.sequence)
        assertEquals(99L, parsed.nonce)
        assertEquals(123456789L, parsed.sentAtNanos)

        assertNull("an echo request is not a reply", IcmpPacket.parseEchoReply(request, request.size))
        assertNull("truncated", IcmpPacket.parseEchoReply(reply, 10))
        val timeExceeded = reply.copyOf().also { it[0] = 11 }
        assertNull(IcmpPacket.parseEchoReply(timeExceeded, timeExceeded.size))
        val v6 = IcmpPacket.buildEchoRequest(1, 5, 6, ipv6 = true).also { it[0] = 129.toByte() }
        assertEquals(1, IcmpPacket.parseEchoReply(v6, v6.size, ipv6 = true)!!.sequence)
    }
}
