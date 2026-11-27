package com.networkanalyst.core.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun entry(n: Int, key: String = "wifi:Home", down: Double? = 100.0 + n, up: Double? = 20.0) =
        HistoryEntry(timestampMs = 1_000L * n, networkKey = key, networkLabel = "Home", downloadMbps = down, uploadMbps = up, healthScore = 90)

    @Test
    fun `entries survive a reload and are capped`() {
        val file = File(folder.root, "nested/history.json")
        val store = HistoryStore(file, maxEntries = 5)
        assertEquals(emptyList<HistoryEntry>(), store.load())
        repeat(8) { store.add(entry(it)) }
        val reloaded = HistoryStore(file, maxEntries = 5).load()
        assertEquals(5, reloaded.size)
        assertEquals(listOf(3, 4, 5, 6, 7).map { 1_000L * it }, reloaded.map { it.timestampMs })
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `corrupt file is treated as empty and recovers on next write`() {
        val file = File(folder.root, "history.json").apply { writeText("{ not json") }
        val store = HistoryStore(file)
        assertTrue(store.load().isEmpty())
        store.add(entry(1))
        assertEquals(1, store.load().size)
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        val file = File(folder.root, "history.json")
        file.writeText("""[{"timestampMs":5,"networkKey":"mobile","networkLabel":"Mobile data","downloadMbps":42.0,"futureField":true}]""")
        val loaded = HistoryStore(file).load().single()
        assertEquals(42.0, loaded.downloadMbps!!, 1e-9)
        assertNull(loaded.uploadMbps)
    }

    @Test
    fun `baseline is the median for the same network once there is enough history`() {
        val entries = listOf(
            entry(1, down = 280.0), entry(2, down = 300.0), entry(3, down = 20.0), entry(4, down = 290.0),
            entry(5, key = "mobile", down = 5.0),
        )
        val baseline = HistoryStore.baseline(entries, "wifi:Home")!!
        // Median of 20, 280, 290, 300: one bad day does not drag "usual" down.
        assertEquals(285.0, baseline.downloadMbps!!, 1e-9)
        assertEquals(20.0, baseline.uploadMbps!!, 1e-9)
        assertNull("not enough history for this network", HistoryStore.baseline(entries, "mobile"))
        assertNull(HistoryStore.baseline(entries.take(2), "wifi:Home"))
        // Enough download history but no uploads recorded.
        val downloadsOnly = (1..3).map { entry(it, up = null) }
        assertNull(HistoryStore.baseline(downloadsOnly, "wifi:Home")!!.uploadMbps)
    }
}
