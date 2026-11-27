package com.networkanalyst.core.history

import com.networkanalyst.core.analysis.SpeedBaseline
import com.networkanalyst.core.util.Stats
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One past test, kept so later results can be compared with what is normal for a network. */
@Serializable
data class HistoryEntry(
    val timestampMs: Long,
    /** Identifies the network the test ran on, e.g. "wifi:Home" or "mobile". */
    val networkKey: String,
    val networkLabel: String,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val idleLatencyMs: Double? = null,
    val bufferbloatMs: Double? = null,
    val healthScore: Int? = null,
)

/** Keeps recent test results in a small JSON file. Safe to call from any thread. */
class HistoryStore(private val file: File, private val maxEntries: Int = 200) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun load(): List<HistoryEntry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            json.decodeFromString<List<HistoryEntry>>(file.readText())
        } catch (_: Exception) {
            // A corrupt or outdated file is not worth failing over; start again.
            emptyList()
        }
    }

    fun add(entry: HistoryEntry): List<HistoryEntry> = synchronized(lock) {
        val updated = (load() + entry).takeLast(maxEntries)
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(updated))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        } catch (_: Exception) {
            // Storage full or unavailable: history is a convenience, not essential.
        }
        updated
    }

    fun clear() = synchronized(lock) {
        file.delete()
        Unit
    }

    companion object {
        /** Fewest earlier results needed before "usual" speed means anything. */
        const val MIN_SAMPLES = 3

        /**
         * Typical speeds for [networkKey] from [entries]: the median of earlier results, or
         * null until there are at least [MIN_SAMPLES] of them.
         */
        fun baseline(entries: List<HistoryEntry>, networkKey: String): SpeedBaseline? {
            val same = entries.filter { it.networkKey == networkKey }
            val downloads = same.mapNotNull { it.downloadMbps }
            val uploads = same.mapNotNull { it.uploadMbps }
            if (downloads.size < MIN_SAMPLES && uploads.size < MIN_SAMPLES) return null
            return SpeedBaseline(
                downloadMbps = downloads.takeIf { it.size >= MIN_SAMPLES }?.let(Stats::median),
                uploadMbps = uploads.takeIf { it.size >= MIN_SAMPLES }?.let(Stats::median),
            )
        }
    }
}
