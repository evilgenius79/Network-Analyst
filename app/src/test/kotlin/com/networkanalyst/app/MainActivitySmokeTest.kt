package com.networkanalyst.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

        tab("Scan").performClick()
        rule.onNodeWithText("Include in the scan").assertIsDisplayed()
    }
}
