package com.networkanalyst.core.connect

/**
 * Works out which radios belong to the same physical access point.
 *
 * A dual- or tri-band access point gives each of its radios its own address, and makers hand
 * these out in a row, so the radios of one box differ only in the last digits. That is a
 * convention, not a rule, so the grouping is a strong hint rather than a fact: two radios are
 * taken to share a box when their addresses agree in the first five bytes, lie within a few
 * steps of each other in the last, and are not on the same channel (one box does not announce
 * the same network twice on one channel, so those would be two boxes from one batch).
 */
object ApUnits {
    private const val MAX_STEP = 8

    private fun bytes(bssid: String): List<Int>? =
        bssid.split(':', '-').takeIf { it.size == 6 }?.map { it.toIntOrNull(16) ?: return null }

    /** True when the two addresses look like radios of one box. */
    fun adjacent(a: String, b: String): Boolean {
        val x = bytes(a) ?: return false
        val y = bytes(b) ?: return false
        return x.subList(0, 5) == y.subList(0, 5) && kotlin.math.abs(x[5] - y[5]) in 1 until MAX_STEP
    }

    fun sameUnit(a: TestTarget, b: TestTarget): Boolean = adjacent(a.bssid, b.bssid) && a.frequencyMhz != b.frequencyMhz

    /** Splits radios into boxes. Radios that fit no other form a box of their own. */
    fun <T> group(items: List<T>, target: (T) -> TestTarget): List<List<T>> {
        val units = ArrayList<MutableList<T>>()
        for (item in items) {
            val home = units.firstOrNull { unit -> unit.all { sameUnit(target(it), target(item)) } }
            if (home != null) home += item else units += mutableListOf(item)
        }
        return units
    }

    /** Groups bare addresses, for events that carry no channel. */
    fun groupAddresses(addresses: Collection<String>): List<List<String>> {
        val units = ArrayList<MutableList<String>>()
        for (address in addresses.map { it.lowercase() }.distinct().sorted()) {
            val home = units.firstOrNull { unit -> unit.any { adjacent(it, address) } }
            if (home != null) home += address else units += mutableListOf(address)
        }
        return units
    }

    /** "a4:11:22:33:45:86/87" for the radios of one box. */
    fun label(addresses: List<String>): String {
        val sorted = addresses.map { it.lowercase() }.distinct().sorted()
        if (sorted.size <= 1) return sorted.firstOrNull().orEmpty()
        return sorted.first() + sorted.drop(1).joinToString("") { "/" + it.substringAfterLast(':') }
    }
}
