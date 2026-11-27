package com.networkanalyst.core.dns

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class DnsTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `query encoding matches the RFC 1035 layout`() {
        val query = DnsMessage.buildQuery(0x1234, "example.com", DnsMessage.TYPE_A)
        // header: id, flags=RD, 1 question; then 7"example" 3"com" 0, type A, class IN
        val expected = hex("1234 0100 0001 0000 0000 0000 07 6578616d706c65 03 636f6d 00 0001 0001")
        assertArrayEquals(expected, query)
    }

    @Test
    fun `mdns unicast-response bit and no recursion`() {
        val query = DnsMessage.buildQuery(0, "_services._dns-sd._udp.local", DnsMessage.TYPE_PTR, recursionDesired = false, unicastResponse = true)
        assertEquals(0, query[2].toInt())
        assertEquals(0x80.toByte(), query[query.size - 2])
        assertEquals(0x01.toByte(), query[query.size - 1])
        val parsed = DnsMessage.parse(query)!!
        assertFalse(parsed.isResponse)
        assertEquals("_services._dns-sd._udp.local", parsed.questions[0].name)
        assertEquals(DnsMessage.TYPE_PTR, parsed.questions[0].type)
    }

    @Test
    fun `parses a response with compressed names`() {
        // Response to example.com A with one CNAME-free answer using a pointer (c00c) to the question name.
        val response = hex(
            "1234 8180 0001 0002 0000 0000" +
                "07 6578616d706c65 03 636f6d 00 0001 0001" +
                "c00c 0001 0001 0000012c 0004 5db8d822" +
                "c00c 001c 0001 0000012c 0010 26062800022000010248189325c81946",
        )
        val packet = DnsMessage.parse(response)!!
        assertTrue(packet.isResponse)
        assertEquals(0x1234, packet.id)
        assertEquals(DnsMessage.RCODE_NOERROR, packet.rcode)
        assertEquals(2, packet.answers.size)
        assertEquals("example.com", packet.answers[0].name)
        assertEquals(300L, packet.answers[0].ttlSeconds)
        assertEquals(RData.Address("93.184.216.34"), packet.answers[0].data)
        assertEquals(RData.Address("2606:2800:220:1:248:1893:25c8:1946"), packet.answers[1].data)
    }

    @Test
    fun `parses mdns service records`() {
        // PTR _googlecast._tcp.local -> Living-Room._googlecast._tcp.local, plus SRV and TXT for it.
        val out = java.io.ByteArrayOutputStream()
        fun name(n: String) { n.split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }; out.write(0) }
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xff) }
        fun u32(v: Int) { u16(v shr 16); u16(v and 0xffff) }
        u16(0); u16(0x8400); u16(0); u16(1); u16(0); u16(2)
        name("_googlecast._tcp.local"); u16(12); u16(1); u32(120)
        val instance = java.io.ByteArrayOutputStream().also { o ->
            "Living-Room._googlecast._tcp.local".split('.').forEach { o.write(it.length); o.write(it.toByteArray()) }; o.write(0)
        }.toByteArray()
        u16(instance.size); out.write(instance)
        name("Living-Room._googlecast._tcp.local"); u16(33); u16(0x8001); u32(120)
        val target = java.io.ByteArrayOutputStream().also { o ->
            "chromecast.local".split('.').forEach { o.write(it.length); o.write(it.toByteArray()) }; o.write(0)
        }.toByteArray()
        u16(6 + target.size); u16(0); u16(0); u16(8009); out.write(target)
        name("Living-Room._googlecast._tcp.local"); u16(16); u16(0x8001); u32(4500)
        val txt = listOf("fn=Living Room TV", "md=Chromecast")
        u16(txt.sumOf { it.length + 1 }); txt.forEach { out.write(it.length); out.write(it.toByteArray()) }

        val packet = DnsMessage.parse(out.toByteArray())!!
        assertEquals(RData.Name("Living-Room._googlecast._tcp.local"), packet.answers[0].data)
        assertEquals(RData.Service(0, 0, 8009, "chromecast.local"), packet.additionals[0].data)
        assertEquals(RData.Text(txt), packet.additionals[1].data)
        assertEquals(3, packet.allRecords.size)
    }

    @Test
    fun `malformed packets are rejected rather than crashing`() {
        assertNull(DnsMessage.parse(ByteArray(5)))
        // Claims one answer but ends after the header.
        assertNull(DnsMessage.parse(hex("1234 8180 0000 0001 0000 0000")))
        // Name is a pointer to itself.
        assertNull(DnsMessage.parse(hex("1234 8180 0001 0000 0000 0000 c00c 0001 0001")))
        // Label length runs past the end.
        assertNull(DnsMessage.parse(hex("1234 8180 0001 0000 0000 0000 3f 6162 00 0001 0001")))
        // Random bytes.
        val noise = ByteArray(200) { (it * 37 + 11).toByte() }
        DnsMessage.parse(noise) // must not throw
    }

    /** A tiny UDP responder standing in for a resolver. */
    private class FakeResolver(val delayMs: Long = 0, val rcode: Int = 0, val dropEvery: Int = 0, val wrongIdFirst: Boolean = false) : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        @Volatile var received = 0
        private val worker = thread(isDaemon = true) {
            val buffer = ByteArray(1500)
            while (!socket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    received++
                    if (dropEvery > 0 && received % dropEvery == 0) continue
                    val reply = buffer.copyOf(packet.length)
                    reply[2] = (reply[2].toInt() or 0x80).toByte()
                    reply[3] = rcode.toByte()
                    if (delayMs > 0) Thread.sleep(delayMs)
                    if (wrongIdFirst) {
                        val stale = reply.copyOf().also { it[0] = (it[0] + 1).toByte() }
                        socket.send(DatagramPacket(stale, stale.size, packet.socketAddress))
                    }
                    socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                } catch (_: Exception) {
                    return@thread
                }
            }
        }
        val port get() = socket.localPort
        override fun close() = socket.close()
    }

    /** Sends to the fake resolver's port instead of 53 by rewriting the destination. */
    private fun redirectingFactory(port: Int): () -> DatagramSocket = {
        object : DatagramSocket() {
            override fun send(p: DatagramPacket) {
                super.send(DatagramPacket(p.data, p.offset, p.length, p.address, port))
            }
        }
    }

    @Test
    fun `benchmark measures a responsive resolver`() = runBlocking {
        FakeResolver(delayMs = 20).use { resolver ->
            val result = DnsBenchmark(redirectingFactory(resolver.port))
                .measure(DnsServer("Fake", "127.0.0.1", isSystem = true), listOf("a.example", "b.example", "c.example"), timeoutMs = 1000)
            assertEquals(3, result.queries)
            assertEquals(0, result.failures)
            assertEquals(3, result.timesMs.size)
            assertTrue("median ${result.medianMs}", result.medianMs!! in 19.0..200.0)
            assertTrue(result.isSystem)
        }
    }

    @Test
    fun `benchmark counts timeouts and server failures`() = runBlocking {
        FakeResolver(dropEvery = 2).use { resolver ->
            val result = DnsBenchmark(redirectingFactory(resolver.port))
                .measure(DnsServer("Lossy", "127.0.0.1"), listOf("a.example", "b.example", "c.example", "d.example"), timeoutMs = 200)
            assertEquals(4, result.queries)
            assertEquals(2, result.failures)
            assertEquals(50.0, result.failurePercent, 1e-9)
        }
        FakeResolver(rcode = DnsMessage.RCODE_SERVFAIL).use { resolver ->
            val result = DnsBenchmark(redirectingFactory(resolver.port))
                .measure(DnsServer("Broken", "127.0.0.1"), listOf("a.example", "b.example"), timeoutMs = 300)
            assertEquals(2, result.failures)
            assertFalse(result.usable)
        }
        FakeResolver(rcode = DnsMessage.RCODE_NXDOMAIN).use { resolver ->
            val result = DnsBenchmark(redirectingFactory(resolver.port))
                .measure(DnsServer("NX", "127.0.0.1"), listOf("nope.example"), timeoutMs = 300)
            assertEquals("NXDOMAIN is a valid, timely answer", 0, result.failures)
        }
    }

    @Test
    fun `benchmark ignores answers with the wrong id`() = runBlocking {
        FakeResolver(wrongIdFirst = true).use { resolver ->
            val result = DnsBenchmark(redirectingFactory(resolver.port))
                .measure(DnsServer("Stale", "127.0.0.1"), listOf("a.example", "b.example"), timeoutMs = 500)
            assertEquals(0, result.failures)
            assertEquals(2, result.timesMs.size)
        }
    }

    @Test
    fun `run measures several servers and reports unusable ones`() = runBlocking {
        FakeResolver().use { resolver ->
            val results = DnsBenchmark(redirectingFactory(resolver.port)).run(
                listOf(DnsServer("Good", "127.0.0.1", isSystem = true), DnsServer("BadAddress", "not an address")),
                domains = listOf("a.example"),
                timeoutMs = 300,
            )
            assertEquals(2, results.size)
            assertNotNull(results[0].medianMs)
            assertFalse(results[1].usable)
            assertEquals(100.0, results[1].failurePercent, 1e-9)
        }
    }
}
