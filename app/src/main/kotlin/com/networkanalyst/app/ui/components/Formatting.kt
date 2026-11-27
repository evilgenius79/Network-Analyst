package com.networkanalyst.app.ui.components

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Number and unit formatting shared by every screen. */
object Fmt {
    fun speedValue(mbps: Double): String = when {
        mbps >= 1000 -> String.format(Locale.US, "%.2f", mbps / 1000)
        mbps >= 100 -> String.format(Locale.US, "%.0f", mbps)
        mbps >= 1 -> String.format(Locale.US, "%.1f", mbps)
        else -> String.format(Locale.US, "%.2f", mbps)
    }

    fun speedUnit(mbps: Double): String = if (mbps >= 1000) "Gbps" else "Mbps"

    fun speed(mbps: Double?): String = if (mbps == null) "–" else "${speedValue(mbps)} ${speedUnit(mbps)}"

    fun ms(value: Double?): String = when {
        value == null -> "–"
        value < 10 -> String.format(Locale.US, "%.1f ms", value)
        else -> String.format(Locale.US, "%.0f ms", value)
    }

    fun percent(value: Double): String = String.format(Locale.US, "%.0f%%", value)

    fun bytes(count: Long): String = when {
        count >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", count / 1e9)
        count >= 1_000_000 -> String.format(Locale.US, "%.0f MB", count / 1e6)
        else -> String.format(Locale.US, "%.0f kB", count / 1e3)
    }

    fun dateTime(timestampMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestampMs))

    /** Day and time to the second, for event logs where the order within a minute matters. */
    fun logTime(timestampMs: Long): String = SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
}
