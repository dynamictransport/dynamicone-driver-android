package com.dynamictransport.dynamicone.feature

import com.dynamictransport.dynamicone.domain.model.ConversationList
import com.dynamictransport.dynamicone.domain.model.ConversationSummary
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.feature.messages.ConversationsViewModel
import com.dynamictransport.dynamicone.feature.messages.MessageBadgeViewModel
import com.dynamictransport.dynamicone.feature.messages.MessagesViewModel
import com.dynamictransport.dynamicone.testing.FakeMessageNotifier
import com.dynamictransport.dynamicone.testing.FakeMessageRepository
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.keepCollecting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val messages = FakeMessageRepository()
    private val notifier = FakeMessageNotifier()
    private val general = ConversationSummary(tripId = null, tripNo = null, lastMessage = null, unreadCount = 1)
    private val trip = ConversationSummary(tripId = "t1", tripNo = "TRP-T1", lastMessage = null, unreadCount = 2)

    private fun viewModel(demo: Boolean = false, push: Boolean = false) = ConversationsViewModel(
        messageRepository = messages,
        appInfo = object : AppInfo {
            override val isDemoMode = demo
            override val versionName = "test"
            override val isPushConfigured = push
        },
        notifier = notifier,
    )

    @Test
    fun `the inbox is read while the list is visible and a failed read keeps the list as stale`() = runTest {
        messages.conversations.value = ConversationList(listOf(general, trip), loaded = true)
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        runCurrent()

        assertEquals(1, messages.conversationRefreshes)
        assertEquals(listOf(null, "t1"), viewModel.uiState.value.conversations.map { it.tripId })

        messages.refreshResult = RefreshResult.OFFLINE
        advanceTimeBy(MessagesViewModel.POLL_INTERVAL_MILLIS + 1)
        assertEquals(2, messages.conversationRefreshes)
        // Read before: the failure keeps the list on screen as stale instead of an empty error.
        assertTrue(viewModel.uiState.value.stale)
        assertNull(viewModel.uiState.value.loadError)
    }

    @Test
    fun `the demo list is unavailable and is not read again`() = runTest {
        messages.refreshResult = RefreshResult.NOT_SUPPORTED
        val viewModel = viewModel(demo = true)
        keepCollecting(viewModel.uiState)
        runCurrent()
        advanceTimeBy(MessagesViewModel.POLL_INTERVAL_MILLIS * 3)

        assertTrue(viewModel.uiState.value.unavailable)
        assertEquals(1, messages.conversationRefreshes)
    }

    @Test
    fun `the notification banner knows whether push is set up and allowed`() = runTest {
        notifier.enabled = false
        val viewModel = viewModel(push = true)
        keepCollecting(viewModel.uiState)

        assertTrue(viewModel.uiState.value.pushAvailable)
        assertFalse(viewModel.notificationsEnabled())
        // Without the Firebase configuration, and in the demo, messages are never announced.
        assertFalse(viewModel(push = false).uiState.value.pushAvailable)
        assertFalse(viewModel(demo = true, push = true).uiState.value.pushAvailable)
    }

    @Test
    fun `the navigation badge adds up the unread messages of all conversations`() = runTest {
        val badge = MessageBadgeViewModel(messages)
        keepCollecting(badge.unreadCount)
        messages.conversations.value = ConversationList(listOf(general, trip), loaded = true)
        runCurrent()

        assertEquals(3, badge.unreadCount.value)
    }
}
