package com.dynamictransport.dynamicone.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.RemoteSessionRequest
import com.dynamictransport.dynamicone.feature.auth.LoginScreen
import com.dynamictransport.dynamicone.feature.auth.LoginUiState
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.mdm.DeviceManagementScreen
import com.dynamictransport.dynamicone.feature.mdm.DeviceManagementUiState
import com.dynamictransport.dynamicone.feature.mdm.RemoteSessionConsentScreen
import com.dynamictransport.dynamicone.testing.TEST_NOW
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Device management and the remote session prompt (docs/29): one dominant action, state by icon and words. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class DeviceManagementScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(state: DeviceManagementUiState) {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                DeviceManagementScreen(
                    state = state,
                    onBack = { events += "back" },
                    onCodeChanged = { events += "code:$it" },
                    onEnrol = { events += "enrol" },
                    onCheckInNow = { events += "check-in" },
                    onEnableAccessibility = { events += "accessibility" },
                )
            }
        }
    }

    @Test
    fun aPhoneThatIsNotEnrolledAsksForTheCodeAndKeepsTheButtonOffUntilItIsComplete() {
        show(DeviceManagementUiState(code = "K7Q2"))

        composeRule.onNodeWithTag("mdm_enrolled").assertIsDisplayed()
        composeRule.onNodeWithTag("mdm_code").assertIsDisplayed().performTextInput("M")
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).performScrollTo().assertIsNotEnabled().assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag("mdm_check_in_now").assertDoesNotExist()
        composeRule.onNodeWithTag("mdm_last_check_in").assertDoesNotExist()

        assertEquals(1, events.count { it.startsWith("code:") })
    }

    @Test
    fun aCompleteCodeEnablesTheOneBigButton() {
        show(DeviceManagementUiState(code = "K7Q2-M9XD"))

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf("enrol"), events)
    }

    @Test
    fun anErrorIsShownInWordsNextToTheForm() {
        show(DeviceManagementUiState(code = "K7Q2-M9XD", error = R.string.mdm_error_code))

        composeRule.onNodeWithTag("mdm_error").assertIsDisplayed()
    }

    @Test
    fun anEnrolledPhoneShowsItsStateAndNoCodeField() {
        show(DeviceManagementUiState(enrolled = true, deviceOwner = true, accessibilityEnabled = false, lastCheckInAt = TEST_NOW))

        composeRule.onNodeWithTag("mdm_code").assertDoesNotExist()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("mdm_enrolled").assertIsDisplayed()
        composeRule.onNodeWithTag("mdm_owner").assertIsDisplayed()
        composeRule.onNodeWithTag("mdm_accessibility").assertIsDisplayed()
        composeRule.onNodeWithTag("mdm_last_check_in").assertIsDisplayed().assertTextContains("Last contact with the office", substring = true)
        composeRule.onNodeWithTag("mdm_check_in_now").performScrollTo().performClick()
        composeRule.onNodeWithTag("mdm_accessibility_open").performScrollTo().performClick()

        assertEquals(listOf("check-in", "accessibility"), events)
    }

    @Test
    fun remoteHelpThatIsOnNeedsNoButton() {
        show(DeviceManagementUiState(enrolled = true, accessibilityEnabled = true))

        composeRule.onNodeWithTag("mdm_accessibility_card").assertDoesNotExist()
    }

    @Test
    fun theConsentScreenNamesWhoAsksWithOneBigAcceptAndAPlainDecline() {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                RemoteSessionConsentScreen(
                    request = RemoteSessionRequest("c-1", "s-1", "Erkan Barlaz", TEST_NOW.plusSeconds(90)),
                    onAccept = { events += "accept" },
                    onDecline = { events += "decline" },
                )
            }
        }

        composeRule.onNodeWithTag("remote_session_title").assertIsDisplayed().assertTextContains("Erkan Barlaz", substring = true)
        composeRule.onNodeWithTag("remote_session_body").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertIsDisplayed().assertHeightIsAtLeast(72.dp).performClick()
        composeRule.onNodeWithTag("remote_session_decline").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(listOf("accept", "decline"), events)
    }

    @Test
    fun theConsentScreenSaysTheOfficeWhenNoNameCame() {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                RemoteSessionConsentScreen(
                    request = RemoteSessionRequest("c-1", "s-1", null, TEST_NOW.plusSeconds(90)),
                    onAccept = {},
                    onDecline = {},
                )
            }
        }

        composeRule.onNodeWithText("The office wants to see your screen").assertIsDisplayed()
    }

    @Test
    fun theSignInScreenOffersDeviceManagementOnlyWhenTheBuildHasIt() {
        var available by mutableStateOf(true)
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                LoginScreen(
                    state = LoginUiState(),
                    onUsernameChanged = {},
                    onPasswordChanged = {},
                    onTogglePasswordVisibility = {},
                    onLogin = {},
                    onOpenDeviceManagement = if (available) ({ events += "open" }) else null,
                )
            }
        }

        composeRule.onNodeWithTag("login_device_management").performScrollTo().performClick()
        assertEquals(listOf("open"), events)

        available = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("login_device_management").assertDoesNotExist()
    }
}
