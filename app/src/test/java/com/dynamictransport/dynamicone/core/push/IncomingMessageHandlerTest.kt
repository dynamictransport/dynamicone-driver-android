package com.dynamictransport.dynamicone.core.push

import com.dynamictransport.dynamicone.domain.model.ConversationList
import com.dynamictransport.dynamicone.domain.model.ConversationSummary
import com.dynamictransport.dynamicone.domain.model.DriverMessage
import com.dynamictransport.dynamicone.domain.model.MessageDirection
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.testing.FakeMessageNotifier
import com.dynamictransport.dynamicone.testing.FakeMessageRepository
import com.dynamictransport.dynamicone.testing.TEST_NOW
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMessageHandlerTest {

    private val messages = FakeMessageRepository()
    private val notifier = FakeMessageNotifier()
    private val openConversations = OpenConversationTracker()
    private val handler = IncomingMessageHandler(messages, notifier, openConversations)

    private fun incoming(id: String, tripId: String?) = DriverMessage(id, tripId, "Body $id", MessageDirection.TO_DRIVER, TEST_NOW, null)

    private fun inbox(vararg conversations: ConversationSummary) {
        messages.conversations.value = ConversationList(conversations.toList(), loaded = true)
    }

    @Test
    fun `a push reads the inbox and announces each new unread message once`() = runTest {
        inbox(
            ConversationSummary(tripId = null, tripNo = null, lastMessage = null, unreadCount = 0),
            ConversationSummary("t1", "DT-1", incoming("m1", "t1"), unreadCount = 1, lastUnread = incoming("m1", "t1")),
        )

        handler.onMessageSignal()

        assertEquals(1, messages.conversationRefreshes)
        assertEquals(listOf<Pair<String?, String>>("t1" to "m1"), notifier.shown.map { it.tripId to it.latest.id })

        // The same newest message does not alert again; a newer one does, with the current count.
        handler.onMessageSignal()
        assertEquals(1, notifier.shown.size)
        inbox(ConversationSummary("t1", "DT-1", incoming("m2", "t1"), unreadCount = 2, lastUnread = incoming("m2", "t1")))
        handler.onMessageSignal()
        assertEquals(listOf("m1", "m2"), notifier.shown.map { it.latest.id })
        assertEquals(2, notifier.shown.last().unreadCount)
        assertEquals("DT-1", notifier.shown.last().tripNo)
    }

    @Test
    fun `a conversation on screen is not announced`() = runTest {
        openConversations.open(null)
        inbox(ConversationSummary(null, null, incoming("g1", null), unreadCount = 1, lastUnread = incoming("g1", null)))

        handler.onMessageSignal()

        assertTrue(notifier.shown.isEmpty())
        // The conversation on screen shows the message at once instead of at its next poll.
        assertEquals(listOf<String?>(null), messages.refreshedTrips)
    }

    @Test
    fun `a repeated delivery of the same push message reads nothing`() = runTest {
        inbox(ConversationSummary(null, null, incoming("g1", null), unreadCount = 1, lastUnread = incoming("g1", null)))

        handler.onMessageSignal("g1")
        handler.onMessageSignal("g1")

        assertEquals(1, messages.conversationRefreshes)
        assertEquals(1, notifier.shown.size)
    }

    @Test
    fun `a push whose inbox read failed is handled when it is delivered again`() = runTest {
        messages.refreshResult = RefreshResult.OFFLINE
        handler.onMessageSignal("g1")

        messages.refreshResult = RefreshResult.SUCCESS
        inbox(ConversationSummary(null, null, incoming("g1", null), unreadCount = 1, lastUnread = incoming("g1", null)))
        handler.onMessageSignal("g1")

        assertEquals(2, messages.conversationRefreshes)
        assertEquals(listOf("g1"), notifier.shown.map { it.latest.id })
    }
}
