package com.dynamictransport.driver.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.driver.core.design.theme.DynamicOneDriverTheme
import com.dynamictransport.driver.domain.model.ConversationSummary
import com.dynamictransport.driver.domain.model.DriverMessage
import com.dynamictransport.driver.domain.model.MessageDirection
import com.dynamictransport.driver.feature.messages.ConversationsScreen
import com.dynamictransport.driver.feature.messages.ConversationsUiState
import com.dynamictransport.driver.testing.TEST_NOW
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ConversationsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String?>()
    private var enableRequests = 0

    private fun setScreen(state: ConversationsUiState, notificationsBlocked: Boolean = false) {
        composeRule.setContent {
            DynamicOneDriverTheme(darkTheme = false) {
                ConversationsScreen(
                    state = state,
                    onOpenConversation = { opened += it },
                    onRetry = {},
                    notificationsBlocked = notificationsBlocked,
                    onEnableNotifications = { enableRequests++ },
                )
            }
        }
    }

    @Test
    fun conversationsShowTheirTripLastMessageAndUnreadCount() {
        val cockpit = DriverMessage("m1", "t1", "Gate 4", MessageDirection.TO_DRIVER, TEST_NOW, null)
        val reply = DriverMessage("m2", null, "On my way", MessageDirection.FROM_DRIVER, TEST_NOW.plusSeconds(60), null)
        setScreen(
            ConversationsUiState(
                conversations = listOf(
                    ConversationSummary(tripId = null, tripNo = null, lastMessage = reply, unreadCount = 0),
                    ConversationSummary(tripId = "t1", tripNo = "DT-2026-000012-01", lastMessage = cockpit, unreadCount = 2),
                    ConversationSummary(tripId = "t9", tripNo = null, lastMessage = null, unreadCount = 0),
                ),
                loaded = true,
            ),
        )

        composeRule.onNodeWithText("General messages").assertExists()
        composeRule.onNodeWithText("You: On my way").assertExists()
        composeRule.onNodeWithText("Trip DT-2026-000012-01").assertExists()
        composeRule.onNodeWithText("Office: Gate 4").assertExists()
        composeRule.onNodeWithContentDescription("2 unread").assertExists()
        // A trip whose number is not known is still listed.
        composeRule.onNodeWithText("Trip messages").assertExists()
        composeRule.onNodeWithTag("notifications_blocked").assertDoesNotExist()

        composeRule.onNodeWithTag("conversation_t1").performClick()
        composeRule.onNodeWithTag("conversation_general").performClick()
        assertEquals(listOf("t1", null), opened)
    }

    @Test
    fun anIncomingMessageNamesItsSenderWhenKnown() {
        val named = DriverMessage("m1", "t1", "Gate 4", MessageDirection.TO_DRIVER, TEST_NOW, null, senderName = "Ayşe Yılmaz")
        setScreen(
            ConversationsUiState(
                conversations = listOf(ConversationSummary(tripId = "t1", tripNo = "DT-2026-000012-01", lastMessage = named, unreadCount = 1)),
                loaded = true,
            ),
        )

        composeRule.onNodeWithText("Ayşe Yılmaz: Gate 4").assertExists()
        composeRule.onNodeWithText("Office: Gate 4").assertDoesNotExist()
    }

    @Test
    fun blockedNotificationsOfferToTurnThemOn() {
        setScreen(
            ConversationsUiState(conversations = listOf(ConversationSummary(null, null, null, 0)), loaded = true, pushAvailable = true),
            notificationsBlocked = true,
        )

        composeRule.onNodeWithText("Notifications are off. Turn them on to hear about new messages.").assertExists()
        composeRule.onNodeWithText("Turn on").performClick()
        assertEquals(1, enableRequests)
    }

    @Test
    fun demoShowsTheListAsUnavailable() {
        setScreen(ConversationsUiState(unavailable = true, isDemoMode = true))

        composeRule.onNodeWithTag("messages_unavailable").assertExists()
    }
}
