package com.networkanalyst.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsAndIpTest {
    @Test
    fun `statistics on known values`() {
        val v = listOf(10.0, 20.0, 30.0, 40.0)
        assertEquals(25.0, Stats.mean(v)!!, 1e-9)
        assertEquals(25.0, Stats.median(v)!!, 1e-9)
        assertEquals(10.0, Stats.percentile(v, 0.0)!!, 1e-9)
        assertEquals(40.0, Stats.percentile(v, 1.0)!!, 1e-9)
        assertEquals(37.0, Stats.percentile(v, 0.9)!!, 1e-9)
        assertEquals(11.180339887, Stats.stdDev(v)!!, 1e-6)
        // |12-10| + |9-12| + |15-9| = 11, over 3 gaps
        assertEquals(11.0 / 3, Stats.jitter(listOf(10.0, 12.0, 9.0, 15.0))!!, 1e-9)
    }

    @Test
    fun `statistics on empty input are null`() {
        assertNull(Stats.mean(emptyList()))
        assertNull(Stats.median(emptyList()))
        assertNull(Stats.stdDev(emptyList()))
        assertNull(Stats.jitter(listOf(5.0)))
    }

    @Test
    fun `ipv4 parse and format round trip`() {
        assertEquals("192.168.1.254", IpUtils.formatIpv4(IpUtils.parseIpv4("192.168.1.254")!!))
        assertEquals("255.255.255.255", IpUtils.formatIpv4(IpUtils.parseIpv4("255.255.255.255")!!))
        assertNull(IpUtils.parseIpv4("256.1.1.1"))
        assertNull(IpUtils.parseIpv4("1.2.3"))
        assertNull(IpUtils.parseIpv4("1.2.3.4.5"))
        assertNull(IpUtils.parseIpv4("a.b.c.d"))
        assertNull(IpUtils.parseIpv4("fe80::1"))
    }

    @Test
    fun `address scopes`() {
        assertEquals(AddressScope.PRIVATE, IpUtils.scopeOfIpv4("192.168.0.1"))
        assertEquals(AddressScope.PRIVATE, IpUtils.scopeOfIpv4("10.255.0.1"))
        assertEquals(AddressScope.PRIVATE, IpUtils.scopeOfIpv4("172.31.255.254"))
        assertEquals(AddressScope.PUBLIC, IpUtils.scopeOfIpv4("172.32.0.1"))
        assertEquals(AddressScope.CGNAT, IpUtils.scopeOfIpv4("100.64.0.1"))
        assertEquals(AddressScope.CGNAT, IpUtils.scopeOfIpv4("100.127.255.254"))
        assertEquals(AddressScope.PUBLIC, IpUtils.scopeOfIpv4("100.128.0.1"))
        assertEquals(AddressScope.LINK_LOCAL, IpUtils.scopeOfIpv4("169.254.10.10"))
        assertEquals(AddressScope.LOOPBACK, IpUtils.scopeOfIpv4("127.0.0.1"))
        assertEquals(AddressScope.PUBLIC, IpUtils.scopeOfIpv4("8.8.8.8"))
        assertNull(IpUtils.scopeOfIpv4("not an ip"))
    }

    @Test
    fun `subnet math for a home network`() {
        val subnet = Ipv4Subnet.parse("192.168.1.37/24")!!
        assertEquals("192.168.1.0/24", subnet.toString())
        assertEquals("192.168.1.255", IpUtils.formatIpv4(subnet.broadcast))
        assertEquals(254L, subnet.hostCount)
        val hosts = subnet.hosts()
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", IpUtils.formatIpv4(hosts.first()))
        assertEquals("192.168.1.254", IpUtils.formatIpv4(hosts.last()))
        assertTrue(subnet.contains(IpUtils.parseIpv4("192.168.1.200")!!))
        assertFalse(subnet.contains(IpUtils.parseIpv4("192.168.2.1")!!))
    }

    @Test
    fun `large subnet is windowed around the device`() {
        val subnet = Ipv4Subnet.parse("10.0.0.0/8")!!
        val me = IpUtils.parseIpv4("10.20.30.40")!!
        val hosts = subnet.hosts(limit = 256, around = me)
        assertEquals(256, hosts.size)
        assertTrue(me in hosts)
        // High addresses must stay ordered even though they are negative as signed ints.
        val high = Ipv4Subnet.parse("192.168.0.0/16")!!.hosts(limit = 100, around = IpUtils.parseIpv4("192.168.255.250")!!)
        assertEquals(100, high.size)
        assertEquals("192.168.255.254", IpUtils.formatIpv4(high.last()))
    }

    @Test
    fun `point to point and single host subnets`() {
        assertEquals(2, Ipv4Subnet.parse("10.0.0.0/31")!!.hosts().size)
        assertEquals(1, Ipv4Subnet.parse("10.0.0.5/32")!!.hosts().size)
        assertNull(Ipv4Subnet.parse("10.0.0.0/33"))
        assertNull(Ipv4Subnet.parse("10.0.0.0"))
    }
}
