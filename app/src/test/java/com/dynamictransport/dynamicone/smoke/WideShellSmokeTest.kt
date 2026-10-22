package com.dynamictransport.dynamicone.smoke

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.app.DynamicOneApplication
import com.dynamictransport.dynamicone.app.MainActivity
import com.dynamictransport.dynamicone.app.navigation.NAV_BAR_TAG
import com.dynamictransport.dynamicone.app.navigation.NAV_RAIL_TAG
import com.dynamictransport.dynamicone.feature.common.ACTION_PANE_TAG
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The signed-in shell on a tablet mounted in landscape (1024 x 640 dp): navigation is a rail on the left instead
 * of a bar at the bottom, and the first screen keeps its dominant action in the pane on the right.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = DynamicOneApplication::class, qualifiers = "en-w1024dp-h640dp-land")
class WideShellSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
    }

    @Test
    fun navigationIsARailOnTheLeftOfAWideScreen() {
        waitFor(hasText("Welcome"))
        composeRule.onNodeWithTag("login_username").performTextInput("driver.1027")
        composeRule.onNodeWithTag("login_password").performTextInput("demo")
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()
        waitFor(hasText("Hello, Mehmet"))

        composeRule.onNodeWithTag(NAV_RAIL_TAG).assertExists()
        composeRule.onNodeWithTag(NAV_BAR_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(ACTION_PANE_TAG).assertExists()

        composeRule.onNodeWithTag("nav_TRIPS").performClick()
        waitFor(hasText("Trips"))
        composeRule.onNodeWithTag(NAV_RAIL_TAG).assertExists()
        composeRule.onNodeWithTag("nav_HOME").performClick()
        waitFor(hasText("Hello, Mehmet"))
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
    }
}
