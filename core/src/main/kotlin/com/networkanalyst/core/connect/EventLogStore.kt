package com.networkanalyst.core.connect

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps the Wi-Fi event log on disk so what was captured in the background survives the app
 * being closed. The whole log is rewritten on save; it is small (a few thousand short entries).
 */
class EventLogStore(private val file: File, private val maxEvents: Int = 3_000) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun load(): List<WifiEvent> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            json.decodeFromString<List<WifiEvent>>(file.readText()).takeLast(maxEvents)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(events: List<WifiEvent>) = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(events.takeLast(maxEvents)))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        } catch (_: Exception) {
            // Storage full or unavailable: the log stays in memory for this session.
        }
        Unit
    }

    fun clear() = synchronized(lock) {
        file.delete()
        Unit
    }
}
