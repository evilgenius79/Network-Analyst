package com.networkanalyst.core.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventLogStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `events round-trip with all their fields and are capped`() {
        val file = File(folder.root, "sub/events.json")
        val store = EventLogStore(file, maxEvents = 3)
        assertTrue(store.load().isEmpty())
        val events = listOf(
            WifiEvent(1, WifiEventKind.SUPPLICANT, "ASSOCIATING"),
            WifiEvent(2, WifiEventKind.AUTH_ERROR, "TIMEOUT"),
            WifiEvent(3, WifiEventKind.LINK_UP, ssid = "Home \"quoted\"", bssid = "aa:bb:cc:00:00:01", rssiDbm = -61, frequencyMhz = 5180),
            WifiEvent(4, WifiEventKind.TEST_START, "Home"),
        )
        store.save(events)
        assertEquals(events.takeLast(3), EventLogStore(file, maxEvents = 3).load())
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `corrupt file reads as empty and is replaced on the next save`() {
        val file = File(folder.root, "events.json").apply { writeText("[{broken") }
        val store = EventLogStore(file)
        assertTrue(store.load().isEmpty())
        store.save(listOf(WifiEvent(9, WifiEventKind.NOTE, "ok")))
        assertEquals(1, store.load().size)
    }
}
