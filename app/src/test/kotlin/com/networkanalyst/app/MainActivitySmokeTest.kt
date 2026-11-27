package com.networkanalyst.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Starts the real activity (view model, collectors and all) in a simulated Android
 * environment and walks through the tabs. It cannot prove the measurements work on a phone,
 * but it does catch crashes on launch and broken navigation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivitySmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = rule.onNode(hasText(label) and isSelectable())

    @Test
    fun `app launches and every tab opens`() {
        rule.onNodeWithText("Find out what is slowing your network").assertIsDisplayed()
        rule.onNode(hasText("Run full scan") and hasClickAction()).assertIsDisplayed()

        tab("Wi-Fi").performClick()
        // The simulated device is not on Wi-Fi, so the screen must explain that rather than crash.
        rule.onNodeWithText("Not connected to Wi-Fi").assertIsDisplayed()

        tab("Speed").performClick()
        rule.onNode(hasText("Start speed test") and hasClickAction()).assertIsDisplayed()

        tab("Devices").performClick()
        rule.onNodeWithText("Devices on your network").assertIsDisplayed()
        rule.onNode(hasText("Scan for devices") and hasClickAction()).assertIsNotEnabled()

        tab("Connect").performClick()
        rule.onNodeWithText("Why won't it connect?").assertIsDisplayed()
        // Without the location permission there is no list of access points to test, and the
        // screen must say so. The connection watch is there regardless.
        rule.onNodeWithText("Allow location to list access points").assertIsDisplayed()

        tab("Scan").performClick()
        rule.onNodeWithText("Include in the scan").assertIsDisplayed()
    }

    @Test
    fun `leaving and reopening the app is noted in the watch log`() {
        val graph = (rule.activity.application as NetworkAnalystApp).graph
        fun notes() = graph.monitor.events.value.filter { it.kind == com.networkanalyst.core.connect.WifiEventKind.NOTE }.map { it.value }

        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertTrue(notes().last() == AppGraph.NOTE_HIDDEN)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertTrue(notes().last() == AppGraph.NOTE_REOPENED)

        // A rotation restarts the screen but is not leaving the app.
        val before = notes().size
        rule.activityRule.scenario.recreate()
        assertTrue(notes().size == before)

        // With background watching on, nothing is missed, so nothing is noted.
        graph.watchingInBackground.value = true
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertTrue(notes().size == before)
        graph.watchingInBackground.value = false
    }
}
