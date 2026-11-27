package com.networkanalyst.core.discovery

import com.networkanalyst.core.model.DeviceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class LanScannerTest {
    private fun classify(
        services: List<String> = emptyList(),
        names: List<String?> = emptyList(),
        manufacturer: String? = null,
        model: String? = null,
        upnp: String? = null,
        ports: List<Int> = emptyList(),
        gateway: Boolean = false,
        self: Boolean = false,
    ) = DeviceClassifier.classify(gateway, self, services, names, manufacturer, model, upnp, ports)

    @Test
    fun `classifier recognises common devices`() {
        assertEquals(DeviceType.THIS_DEVICE, classify(self = true, gateway = true))
        assertEquals(DeviceType.ROUTER, classify(gateway = true))
        assertEquals(DeviceType.ROUTER, classify(upnp = "urn:schemas-upnp-org:device:InternetGatewayDevice:2"))
        assertEquals(DeviceType.TV, classify(services = listOf("_googlecast._tcp"), names = listOf("Living Room TV"), model = "Chromecast Ultra"))
        assertEquals(DeviceType.SPEAKER, classify(services = listOf("_googlecast._tcp"), model = "Google Home Mini", names = listOf("Kitchen speaker")))
        assertEquals(DeviceType.SPEAKER, classify(upnp = "urn:schemas-upnp-org:device:ZonePlayer:1", manufacturer = "Sonos, Inc."))
        assertEquals(DeviceType.PRINTER, classify(services = listOf("_ipp._tcp", "_http._tcp"), names = listOf("HP ENVY 6000")))
        assertEquals(DeviceType.GAME_CONSOLE, classify(names = listOf("PlayStation 5")))
        assertEquals(DeviceType.NAS, classify(names = listOf("DiskStation"), manufacturer = "Synology", ports = listOf(445)))
        assertEquals(DeviceType.SMART_HOME, classify(services = listOf("_hap._tcp")))
        assertEquals(DeviceType.PHONE, classify(ports = listOf(62078)))
        assertEquals(DeviceType.PHONE, classify(names = listOf("Marias-iPhone")))
        assertEquals(DeviceType.COMPUTER, classify(services = listOf("_smb._tcp", "_companion-link._tcp"), names = listOf("Johns-MacBook-Pro")))
        assertEquals(DeviceType.COMPUTER, classify(ports = listOf(445)))
        assertEquals(DeviceType.TV, classify(services = listOf("_airplay._tcp", "_raop._tcp")))
        assertEquals(DeviceType.CAMERA, classify(names = listOf("Front door camera")))
        assertEquals(DeviceType.UNKNOWN, classify())
        // A person's name containing a brand word must not turn a phone into a printer.
        assertEquals(DeviceType.PHONE, classify(names = listOf("Brothers-iPhone")))
    }

    @Test
    fun `reverse lookup names and hostname cleaning`() {
        assertEquals("20.1.168.192.in-addr.arpa", LanScanner.reverseName("192.168.1.20"))
        assertNull(LanScanner.reverseName("fe80::1"))
        assertEquals("Johns-Laptop", LanScanner.cleanHostname("Johns-Laptop.lan."))
        assertEquals("nas", LanScanner.cleanHostname("nas.home.arpa"))
        assertNull(LanScanner.cleanHostname(""))
        assertNull(LanScanner.cleanHostname(null))
        assertNull("an address is not a name", LanScanner.cleanHostname("192.168.1.20"))
    }

    @Test
    fun `device entry merges every source of clues`() {
        val device = LanScanner.buildDevice(
            ip = "192.168.1.50",
            isSelf = false,
            isGateway = false,
            pingMs = 4.2,
            ports = mapOf(80 to PortState.OPEN, 443 to PortState.CLOSED, 445 to PortState.NO_ANSWER, 8009 to PortState.OPEN),
            mdns = MdnsObservation(
                "192.168.1.50",
                serviceTypes = setOf("_googlecast._tcp"),
                instanceNames = setOf("Chromecast-abc123"),
                hostnames = setOf("chromecast-abc"),
                txt = mapOf("fn" to "Living Room TV", "md" to "Chromecast Ultra"),
            ),
            upnp = null,
            netBios = null,
            reverseName = null,
        )
        assertEquals("Living Room TV", device.name)
        assertEquals("chromecast-abc", device.hostname)
        assertEquals("Chromecast Ultra", device.model)
        assertEquals(DeviceType.TV, device.type)
        assertEquals(listOf(80, 8009), device.openPorts)
        assertEquals(listOf("_googlecast._tcp"), device.services)
        assertEquals("Living Room TV", device.displayName)
        assertEquals(4.2, device.pingMs!!, 1e-9)

        val pc = LanScanner.buildDevice(
            "192.168.1.60", false, false, null, mapOf(445 to PortState.OPEN), null, null,
            NetBiosInfo("DESKTOP-7QK2", "WORKGROUP", null), reverseName = "DESKTOP-7QK2",
        )
        assertEquals("DESKTOP-7QK2", pc.hostname)
        assertNull("name equal to the hostname is not repeated", pc.name)
        assertEquals(DeviceType.COMPUTER, pc.type)

        val silent = LanScanner.buildDevice("192.168.1.70", false, false, 12.0, emptyMap(), null, null, null, null)
        assertEquals("192.168.1.70", silent.displayName)
        assertEquals(DeviceType.UNKNOWN, silent.type)

        val router = LanScanner.buildDevice(
            "192.168.1.1", false, true, 1.5, mapOf(80 to PortState.OPEN), null,
            UpnpDescription("Home Router", "TestCorp", "TR-1000", "urn:schemas-upnp-org:device:InternetGatewayDevice:1", emptyList()),
            null, "router",
        )
        assertEquals(DeviceType.ROUTER, router.type)
        assertEquals("Home Router", router.name)
        assertEquals("TestCorp", router.manufacturer)
    }

    @Test
    fun `tcp probe tells open, closed and silent apart`() {
        val scanner = LanScanner(config = LanScanConfig(tcpTimeoutMs = 300))
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { listener ->
            assertEquals(PortState.OPEN, scanner.probe("127.0.0.1", listener.localPort))
        }
        val closedPort = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(PortState.CLOSED, scanner.probe("127.0.0.1", closedPort))
    }

    @Test
    fun `scan of a tiny loopback subnet finds the listening host`() = runBlocking {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.2")).use { listener ->
            val config = LanScanConfig(
                probePorts = listOf(listener.localPort), tcpTimeoutMs = 300,
                mdnsListenMs = 300, ssdpListenMs = 300, nameLookupTimeoutMs = 150,
            )
            // 127.0.0.0/29 has hosts .1 to .6; this device is .1, the gateway is declared as .6.
            val result = LanScanner(config = config).scan("127.0.0.1/29", gateway = "127.0.0.6")
            assertEquals("127.0.0.0/29", result.subnet)
            assertEquals(6, result.scannedHosts)
            assertTrue(!result.truncated)
            val byIp = result.devices.associateBy { it.ip }
            assertTrue(byIp.getValue("127.0.0.1").isSelf)
            assertEquals(DeviceType.THIS_DEVICE, byIp.getValue("127.0.0.1").type)
            assertEquals(listOf(listener.localPort), byIp.getValue("127.0.0.2").openPorts)
            // The gateway is listed even though it did not answer anything.
            assertTrue(byIp.getValue("127.0.0.6").isGateway)
            assertEquals(DeviceType.ROUTER, byIp.getValue("127.0.0.6").type)
            // Addresses are sorted numerically.
            assertEquals(result.devices.map { it.ip }.sorted(), result.devices.map { it.ip })
        }
    }
}
