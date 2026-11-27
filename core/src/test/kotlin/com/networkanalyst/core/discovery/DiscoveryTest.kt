package com.networkanalyst.core.discovery

import com.networkanalyst.core.dns.DnsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun hex(s: String) = s.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ---- SSDP ----

    @Test
    fun `ssdp search has the exact wire format`() {
        val text = String(Ssdp.buildSearch("ssdp:all", 2), Charsets.US_ASCII)
        assertEquals(
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nST: ssdp:all\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n\r\n",
            text,
        )
    }

    @Test
    fun `ssdp response parsing`() {
        val raw = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nST: upnp:rootdevice\r\n" +
            "USN: uuid:1111::upnp:rootdevice\r\nEXT:\r\nSERVER: Debian/13 UPnP/1.1 MiniUPnPd/2.3.4\r\n" +
            "location: http://192.168.1.1:5555/rootDesc.xml\r\n\r\n"
        val bytes = raw.toByteArray()
        val response = Ssdp.parseResponse(bytes, bytes.size, "192.168.1.1")!!
        assertEquals("http://192.168.1.1:5555/rootDesc.xml", response.location)
        assertEquals("upnp:rootdevice", response.searchTarget)
        assertEquals("Debian/13 UPnP/1.1 MiniUPnPd/2.3.4", response.server)
        assertEquals("192.168.1.1", response.address)

        val notify = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n\r\n".toByteArray()
        assertNull("announcements are not search responses", Ssdp.parseResponse(notify, notify.size, "1.2.3.4"))
        val error = "HTTP/1.1 500 Internal Server Error\r\n\r\n".toByteArray()
        assertNull(Ssdp.parseResponse(error, error.size, "1.2.3.4"))
        assertNull(Ssdp.parseResponse(ByteArray(0), 0, "1.2.3.4"))
    }

    // ---- UPnP description and SOAP, using documents captured from miniupnpd 2.3.4 ----

    @Test
    fun `parses a real gateway description`() {
        val description = UpnpXml.parseDescription(fixture("miniupnpd-rootDesc.xml"), "http://192.0.2.2:5555/rootDesc.xml")!!
        assertEquals("Test Router", description.friendlyName)
        assertEquals("TestCorp", description.manufacturer)
        assertEquals("TR-1000", description.modelName)
        assertEquals("urn:schemas-upnp-org:device:InternetGatewayDevice:2", description.deviceType)
        assertTrue(description.isInternetGateway)
        val common = description.service(UpnpXml.SERVICE_WAN_COMMON)!!
        assertEquals("urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1", common.serviceType)
        assertEquals("http://192.0.2.2:5555/ctl/CmnIfCfg", common.controlUrl)
        val ip = description.service(UpnpXml.SERVICE_WAN_IP)!!
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:2", ip.serviceType)
        assertEquals("http://192.0.2.2:5555/ctl/IPConn", ip.controlUrl)
        assertNull(description.service(UpnpXml.SERVICE_WAN_PPP))
    }

    @Test
    fun `description honours URLBase and rejects junk`() {
        val xml = """
            <root><URLBase>http://10.0.0.1:49152/base/</URLBase><device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Kitchen &amp; Dining Speaker</friendlyName>
            <manufacturer><![CDATA[Sonos, Inc.]]></manufacturer>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>ctl/av</controlURL></service>
            <service><serviceType>urn:x:broken:1</serviceType><controlURL>javascript:alert(1)</controlURL></service>
            </serviceList></device></root>
        """.trimIndent()
        val description = UpnpXml.parseDescription(xml, "http://10.0.0.1:1400/desc.xml")!!
        assertEquals("Kitchen & Dining Speaker", description.friendlyName)
        assertEquals("Sonos, Inc.", description.manufacturer)
        assertFalse(description.isInternetGateway)
        assertEquals(listOf("http://10.0.0.1:49152/base/ctl/av"), description.services.map { it.controlUrl })
        assertNull(UpnpXml.parseDescription("<html>router login</html>", "http://10.0.0.1/"))
        assertNull(UpnpXml.parseDescription("", "http://10.0.0.1/"))
    }

    @Test
    fun `soap request matches what a real gateway accepted`() {
        assertEquals(
            "<?xml version=\"1.0\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:GetExternalIPAddress xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:2\"></u:GetExternalIPAddress>" +
                "</s:Body></s:Envelope>\r\n",
            UpnpXml.soapEnvelope("urn:schemas-upnp-org:service:WANIPConnection:2", "GetExternalIPAddress"),
        )
    }

    @Test
    fun `reads values out of real soap replies`() {
        val link = fixture("miniupnpd-GetCommonLinkProperties.xml")
        assertEquals("Cable", UpnpXml.firstElement(link, "NewWANAccessType"))
        assertEquals("20000000", UpnpXml.firstElement(link, "NewLayer1UpstreamMaxBitRate"))
        assertEquals("300000000", UpnpXml.firstElement(link, "NewLayer1DownstreamMaxBitRate"))
        assertEquals("Up", UpnpXml.firstElement(link, "NewPhysicalLinkStatus"))
        assertEquals("93.184.216.34", UpnpXml.firstElement(fixture("miniupnpd-GetExternalIPAddress.xml"), "NewExternalIPAddress"))
        val status = fixture("miniupnpd-GetStatusInfo.xml")
        assertEquals("Connected", UpnpXml.firstElement(status, "NewConnectionStatus"))
        assertEquals("2918", UpnpXml.firstElement(status, "NewUptime"))
        // This real reading is larger than 32 bits, so it must be read as a long.
        assertEquals(4367458077L, UpnpXml.firstElement(fixture("miniupnpd-GetTotalBytesReceived.xml"), "NewTotalBytesReceived")!!.toLong())
        assertNull(UpnpXml.firstElement(status, "NewDoesNotExist"))
    }

    @Test
    fun `byte counter rate handles wrap, reset and nonsense`() {
        // 1.25 MB in 1 s = 10 Mbps.
        assertEquals(10_000_000.0, UpnpClient.counterRate(1_000_000, 2_250_000, 1.0)!!, 1.0)
        // A 32-bit counter that wrapped: from 100 below the limit to 400 past zero is 500 bytes.
        assertEquals(4000.0, UpnpClient.counterRate((1L shl 32) - 100, 400, 1.0)!!, 1e-6)
        // A wide counter that went backwards was reset; nothing can be said.
        assertNull(UpnpClient.counterRate(4_367_458_077, 1_000, 1.0))
        // An implausible jump is discarded rather than reported as terabits.
        assertNull(UpnpClient.counterRate(0, 4_000_000_000, 1.0))
        assertNull(UpnpClient.counterRate(null, 5, 1.0))
        assertNull(UpnpClient.counterRate(5, 10, 0.0))
    }

    // ---- NetBIOS, using a reply captured from Samba nmbd ----

    @Test
    fun `netbios request is byte-identical to the one a real server answered`() {
        assertEquals(
            "4e410000000100000000000020434b4141414141414141414141414141414141414141414141414141414141410000210001",
            NetBios.buildNodeStatusRequest(0x4e41).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `parses a real netbios node status reply`() {
        val reply = hex(fixture("samba-nmbd-node-status-reply.hex"))
        val info = NetBios.parseNodeStatusResponse(reply, reply.size, 0x4e41)!!
        assertEquals("TESTPC", info.computerName)
        assertEquals("HOMELAB", info.group)
        assertNull("Samba reports an all-zero hardware address", info.macAddress)

        assertNull("wrong transaction id", NetBios.parseNodeStatusResponse(reply, reply.size, 0x1111))
        assertNull("truncated", NetBios.parseNodeStatusResponse(reply, 60, 0x4e41))
        val request = NetBios.buildNodeStatusRequest(0x4e41)
        assertNull("a request is not a reply", NetBios.parseNodeStatusResponse(request, request.size, 0x4e41))
        // A reply that does carry a hardware address.
        val withMac = reply.copyOf().also {
            val macOffset = 57 + 5 * 18
            byteArrayOf(0x00, 0x1a, 0x2b, 0x3c, 0x4d, 0x5e).copyInto(it, macOffset)
        }
        assertEquals("00:1a:2b:3c:4d:5e", NetBios.parseNodeStatusResponse(withMac, withMac.size, 0x4e41)!!.macAddress)
    }

    // ---- mDNS ----

    @Test
    fun `mdns observation collects types, names, hosts and model hints`() {
        val out = java.io.ByteArrayOutputStream()
        fun name(n: String) { n.split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }; out.write(0) }
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xff) }
        fun u32(v: Int) { u16(v shr 16); u16(v and 0xffff) }
        fun rdataName(n: String): ByteArray = java.io.ByteArrayOutputStream().also { o ->
            n.split('.').forEach { o.write(it.length); o.write(it.toByteArray()) }; o.write(0)
        }.toByteArray()
        u16(0); u16(0x8400); u16(0); u16(2); u16(0); u16(3)
        // answer 1: service enumeration -> _airplay._tcp.local
        name("_services._dns-sd._udp.local"); u16(12); u16(1); u32(10)
        rdataName("_airplay._tcp.local").let { u16(it.size); out.write(it) }
        // answer 2: _googlecast._tcp.local -> Living Room TV._googlecast._tcp.local
        name("_googlecast._tcp.local"); u16(12); u16(1); u32(10)
        rdataName("Living Room TV._googlecast._tcp.local").let { u16(it.size); out.write(it) }
        // additional: SRV, TXT, A
        name("Living Room TV._googlecast._tcp.local"); u16(33); u16(1); u32(10)
        rdataName("chromecast-abc.local").let { u16(6 + it.size); u16(0); u16(0); u16(8009); out.write(it) }
        name("Living Room TV._googlecast._tcp.local"); u16(16); u16(1); u32(10)
        val txt = listOf("fn=Living Room TV", "md=Chromecast Ultra", "rs=", "bs=FA8FCA")
        u16(txt.sumOf { it.length + 1 }); txt.forEach { out.write(it.length); out.write(it.toByteArray()) }
        name("chromecast-abc.local"); u16(1); u16(1); u32(10); u16(4); out.write(byteArrayOf(192.toByte(), 168.toByte(), 1, 50))

        val packet = DnsMessage.parse(out.toByteArray())!!
        val observation = Mdns.observe(packet, "192.168.1.50")
        assertEquals(setOf("_airplay._tcp", "_googlecast._tcp"), observation.serviceTypes)
        assertEquals(setOf("Living Room TV"), observation.instanceNames)
        assertEquals(setOf("chromecast-abc"), observation.hostnames)
        assertEquals(mapOf("fn" to "Living Room TV", "md" to "Chromecast Ultra"), observation.txt)

        val merged = observation.merge(MdnsObservation("192.168.1.50", serviceTypes = setOf("_spotify-connect._tcp")))
        assertEquals(3, merged.serviceTypes.size)
        assertNotNull(merged.txt["md"])
    }
}
