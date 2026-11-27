package com.networkanalyst.core.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Runs the discovery code against real implementations (Samba nmbd, Avahi, miniupnpd) started
 * on this machine. Skipped unless run with `-PdaemonHost=<address the daemons listen on>`;
 * the expected names match the configuration described in docs/VERIFICATION.md.
 */
class RealDaemonTest {
    private val host: String? = System.getProperty("daemonHost")?.takeIf { it.isNotBlank() }

    @Before
    fun onlyWhenRequested() {
        assumeTrue("daemon tests not requested", host != null)
    }

    private fun boundSocket(): DatagramSocket = DatagramSocket(0, InetAddress.getByName(host))

    @Test
    fun `netbios node status against samba`() {
        boundSocket().use { socket ->
            socket.soTimeout = 3000
            val request = NetBios.buildNodeStatusRequest(0x2222)
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName(host), NetBios.PORT))
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val info = NetBios.parseNodeStatusResponse(buffer, packet.length, 0x2222)
            println("DAEMON netbios: $info")
            assertEquals("TESTPC", info!!.computerName)
            assertEquals("HOMELAB", info.group)
        }
    }

    @Test
    fun `mdns browse against avahi`() = runBlocking {
        val observations = Mdns.discover(::boundSocket, listenMs = 3000)
        println("DAEMON mdns: $observations")
        val device = observations[host]
        assertNotNull("avahi did not answer", device)
        assertTrue(device!!.serviceTypes.contains("_googlecast._tcp"))
        // Not in the built-in list: only reachable by following service enumeration.
        assertTrue(device.serviceTypes.contains("_customthing._tcp"))
        assertTrue(device.instanceNames.contains("Living Room TV"))
        assertTrue(device.hostnames.contains("testhost"))
        assertEquals("Chromecast Ultra", device.txt["md"])
    }

    @Test
    fun `scanner merges what real daemons announce`() = runBlocking {
        // A /32 keeps the scan to this machine: nothing else on the network is probed.
        val transport = object : LanTransport {
            override fun datagramSocket() = boundSocket()
        }
        val scanner = LanScanner(transport, LanScanConfig(probePorts = listOf(5555)))
        val result = scanner.scan("$host/32", gateway = null)
        println("DAEMON scan: ${result.devices}")
        val me = result.devices.single()
        assertEquals("Test Router", me.name)
        assertEquals("TestCorp", me.manufacturer)
        assertEquals("testhost", me.hostname)
        assertTrue(me.services.contains("_googlecast._tcp"))
        assertNotNull(result.gatewayDescription)
        val gateway = scanner.queryGateway(result.gatewayDescription!!, trafficSampleMs = 500)
        assertEquals("93.184.216.34", gateway.externalIp)
    }

    @Test
    fun `ssdp search and gateway query against miniupnpd`() = runBlocking {
        val responses = Ssdp.search(::boundSocket, listenMs = 3500)
        println("DAEMON ssdp: ${responses.size} responses, locations ${responses.mapNotNull { it.location }.distinct()}")
        val location = responses.firstNotNullOfOrNull { it.location }
        assertNotNull("no SSDP response with a description URL", location)
        val client = UpnpClient()
        val description = client.fetchDescription(location!!)
        println("DAEMON upnp description: $description")
        assertEquals("Test Router", description!!.friendlyName)
        assertTrue(description.isInternetGateway)
        val gateway = client.queryGateway(description, trafficSampleMs = 1000)
        println("DAEMON gateway: $gateway")
        assertEquals("93.184.216.34", gateway.externalIp)
        assertEquals(300_000_000L, gateway.maxDownstreamBps)
        assertEquals(20_000_000L, gateway.maxUpstreamBps)
        assertEquals("Connected", gateway.connectionStatus)
        assertEquals("Cable", gateway.wanAccessType)
        assertNotNull(gateway.uptimeSeconds)
        assertNotNull("traffic counters should give a rate", gateway.backgroundDownBps)
    }
}
