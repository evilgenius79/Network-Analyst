package com.networkanalyst.core.speed

/**
 * Letter grade for bufferbloat: how much latency rises when the line is fully loaded.
 * The cut-offs follow the scale commonly used by bufferbloat tests
 * (under 5 ms A+, under 30 A, under 60 B, under 200 C, under 400 D, otherwise F).
 */
enum class BufferbloatGrade(val label: String) {
    A_PLUS("A+"), A("A"), B("B"), C("C"), D("D"), F("F");

    companion object {
        fun of(addedLatencyMs: Double): BufferbloatGrade = when {
            addedLatencyMs < 5 -> A_PLUS
            addedLatencyMs < 30 -> A
            addedLatencyMs < 60 -> B
            addedLatencyMs < 200 -> C
            addedLatencyMs < 400 -> D
            else -> F
        }
    }
}
