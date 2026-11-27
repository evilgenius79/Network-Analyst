package com.networkanalyst.app.data

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.networkanalyst.app.NetworkAnalystApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The background watch service in a simulated Android: it starts, shows its notification and tidies up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchServiceTest {
    private val app = ApplicationProvider.getApplicationContext<NetworkAnalystApp>()

    private fun notes() = app.graph.monitor.events.value.map { it.value }

    @Test
    fun `runs in the foreground with a notification and tidies up when stopped`() {
        val controller = Robolectric.buildService(WatchService::class.java, Intent(app, WatchService::class.java))
        val service = controller.create().get()
        controller.startCommand(0, 1)

        assertTrue(app.graph.watchingInBackground.value)
        assertTrue("Background watching started" in notes())
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull("the service must be in the foreground, with its notification", notification)
        assertEquals("Watching Wi-Fi connections", shadowOf(notification).contentTitle)
        assertEquals(listOf("Stop"), notification.actions.map { it.title.toString() })
        assertNotNull(app.getSystemService(NotificationManager::class.java).getNotificationChannel(WatchService.CHANNEL_ID))

        // Starting it again while it runs changes nothing.
        controller.startCommand(0, 2)
        assertEquals(1, notes().count { it == "Background watching started" })

        // The notification's Stop button.
        service.onStartCommand(Intent(app, WatchService::class.java).setAction(WatchService.ACTION_STOP), 0, 3)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
        assertFalse(app.graph.watchingInBackground.value)
        assertTrue("Background watching stopped" in notes())
    }
}
