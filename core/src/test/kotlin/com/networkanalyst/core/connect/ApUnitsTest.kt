package com.networkanalyst.core.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApUnitsTest {
    // Made-up addresses laid out the way a real two-unit mesh reported its own: each box has a
    // 2.4 GHz and a 5 GHz radio, one address apart.
    private val a24 = TestTarget("Home", "a4:11:22:33:45:86", 2462, -47)
    private val a5 = TestTarget("Home", "a4:11:22:33:45:87", 5560, -68)
    private val b24 = TestTarget("Home", "c0:22:33:44:9a:e6", 2437, -45)
    private val b5 = TestTarget("Home", "c0:22:33:44:9a:e7", 5560, -51)

    @Test
    fun `radios with neighbouring addresses are one box`() {
        assertTrue(ApUnits.sameUnit(a24, a5))
        assertTrue(ApUnits.sameUnit(b5, b24))
        assertFalse(ApUnits.sameUnit(a5, b5))
        assertEquals(listOf(listOf(a24, a5), listOf(b5, b24)), ApUnits.group(listOf(a24, b5, a5, b24)) { it })
    }

    @Test
    fun `a third radio joins its box`() {
        val a5High = TestTarget("Home", "a4:11:22:33:45:88", 5745, -70)
        assertEquals(1, ApUnits.group(listOf(a24, a5, a5High)) { it }.size)
    }

    @Test
    fun `the same channel twice means two boxes, however close the addresses`() {
        // Two units from one production batch, both on 5 GHz channel 112.
        val twin = TestTarget("Home", "a4:11:22:33:45:89", 5560, -60)
        assertFalse(ApUnits.sameUnit(a5, twin))
        assertEquals(2, ApUnits.group(listOf(a5, twin)) { it }.size)
    }

    @Test
    fun `addresses far apart or malformed are not grouped`() {
        assertFalse(ApUnits.adjacent("a4:11:22:33:45:86", "a4:11:22:33:45:96"))
        assertFalse(ApUnits.adjacent("a4:11:22:33:45:86", "a4:11:22:33:46:87"))
        assertFalse(ApUnits.adjacent("a4:11:22:33:45:86", "a4:11:22:33:45:86"))
        assertFalse(ApUnits.adjacent("not an address", "a4:11:22:33:45:86"))
        assertTrue(ApUnits.adjacent("A4:11:22:33:45:86", "a4:11:22:33:45:87"))
    }

    @Test
    fun `bare addresses group and label the same way`() {
        val units = ApUnits.groupAddresses(listOf("c0:22:33:44:9a:e7", "a4:11:22:33:45:87", "A4:11:22:33:45:86", "a4:11:22:33:45:87"))
        assertEquals(listOf(listOf("a4:11:22:33:45:86", "a4:11:22:33:45:87"), listOf("c0:22:33:44:9a:e7")), units)
        assertEquals("a4:11:22:33:45:86/87", ApUnits.label(units[0]))
        assertEquals("c0:22:33:44:9a:e7", ApUnits.label(units[1]))
    }
}
