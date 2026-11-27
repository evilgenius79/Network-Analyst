package com.networkanalyst.core.util

import kotlin.math.abs
import kotlin.math.sqrt

/** Small statistics helpers shared by every measurement engine. All return null for empty input. */
object Stats {
    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.sum() / values.size

    fun median(values: List<Double>): Double? = percentile(values, 0.5)

    /** Percentile with linear interpolation between closest ranks. [p] is in 0..1. */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        require(p in 0.0..1.0) { "percentile must be within 0..1, was $p" }
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val rank = p * (sorted.size - 1)
        val low = rank.toInt()
        val high = minOf(low + 1, sorted.size - 1)
        val fraction = rank - low
        return sorted[low] + (sorted[high] - sorted[low]) * fraction
    }

    /** Population standard deviation. */
    fun stdDev(values: List<Double>): Double? {
        val m = mean(values) ?: return null
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }

    /**
     * Jitter as the mean absolute difference between consecutive samples, which is how
     * speed tests commonly report it. Needs at least two samples.
     */
    fun jitter(values: List<Double>): Double? {
        if (values.size < 2) return null
        return values.zipWithNext { a, b -> abs(b - a) }.average()
    }
}
