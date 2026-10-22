package com.dynamictransport.dynamicone.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.core.wakeup.WakeUpPermissionIssue
import com.dynamictransport.dynamicone.domain.model.WakeUpOutcome
import com.dynamictransport.dynamicone.domain.model.WakeUpOutcomeState
import com.dynamictransport.dynamicone.domain.model.WakeUpReason
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.wakeup.WakeUpCallBanner
import com.dynamictransport.dynamicone.feature.wakeup.WakeUpCallScreen
import com.dynamictransport.dynamicone.feature.wakeup.WakeUpPermissionBanner
import com.dynamictransport.dynamicone.testing.wakeUpCall
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

/** The wake-up call screen and the home banners (docs/25 U2, U3, U7): one big action, status with icon and text. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class WakeUpCallScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()
    private lateinit var defaultZone: TimeZone

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() = TimeZone.setDefault(defaultZone)

    @Test
    fun theCallScreenNamesTheCallerTheReasonAndTheTripWithOneBigAnswer() {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                WakeUpCallScreen(
                    call = wakeUpCall(reason = WakeUpReason.LOADING_TIME_CHANGED, tripNo = "DT-2026-000012-01"),
                    onAcknowledge = { calls += "awake" },
                    now = Instant.parse("2026-10-22T05:06:00Z"),
                )
            }
        }

        composeRule.onNodeWithText("05:06").assertIsDisplayed()
        composeRule.onNodeWithTag("wake_up_title").assertTextContains("Ayşe Yılmaz is calling you")
        composeRule.onNodeWithTag("wake_up_reason").assertTextContains("The loading time has changed.")
        composeRule.onNodeWithTag("wake_up_trip").assertTextContains("Trip DT-2026-000012-01")
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("I'M AWAKE").assertHeightIsAtLeast(72.dp).performClick()
        assertEquals(listOf("awake"), calls)
    }

    @Test
    fun theHomeBannerTellsWhoCalledAndWhetherTheAnswerWentOut() {
        val outcome = WakeUpOutcome(
            callId = "call-1",
            reason = WakeUpReason.CALL_OFFICE,
            requestedByDisplayName = null,
            requestedAt = Instant.parse("2026-10-22T05:05:00Z"),
            expiresAt = Instant.parse("2026-10-22T05:10:00Z"),
            state = WakeUpOutcomeState.ACKNOWLEDGED_PENDING,
            acknowledgedAt = Instant.parse("2026-10-22T05:06:40Z"),
            updatedAt = Instant.parse("2026-10-22T05:06:40Z"),
        )
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Column {
                    WakeUpCallBanner(outcome)
                    WakeUpCallBanner(outcome.copy(state = WakeUpOutcomeState.NO_ANSWER, acknowledgedAt = null, requestedByDisplayName = "Ayşe Yılmaz"))
                    WakeUpPermissionBanner(WakeUpPermissionIssue.BATTERY, onOpenSettings = { calls += "settings" })
                }
            }
        }

        composeRule.onNodeWithTag("wake_up_home_ACKNOWLEDGED_PENDING").assertIsDisplayed()
        composeRule.onNodeWithText("The office called · 05:06 · answer waiting to be sent").assertIsDisplayed()
        composeRule.onNodeWithTag("wake_up_home_NO_ANSWER").assertIsDisplayed()
        composeRule.onNodeWithText("Ayşe Yılmaz called · 05:05 · not answered").assertIsDisplayed()
        composeRule.onNodeWithTag("wake_up_permission_BATTERY").assertIsDisplayed()
        composeRule.onNodeWithText("ALLOW").performClick()
        assertEquals(listOf("settings"), calls)
    }
}
