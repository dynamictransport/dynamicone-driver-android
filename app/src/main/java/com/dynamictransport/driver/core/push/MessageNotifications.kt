package com.dynamictransport.driver.core.push

import android.content.Intent
import com.dynamictransport.driver.domain.model.DriverMessage
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.repository.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Push data keys (docs/13_PUSH_NOTIFICATIONS.md); also the extras of a tapped notification. */
const val EXTRA_TYPE = "type"
const val EXTRA_TRIP_ID = "tripId"
const val EXTRA_MESSAGE_ID = "messageId"
const val TYPE_DRIVER_MESSAGE = "driver.message"

/** The tag the backend's system notification uses too, so the app replaces and removes it. */
fun conversationTag(tripId: String?): String = "message-${tripId ?: "general"}"

/** A conversation with incoming messages the driver has not read. */
data class MessageNotification(
    /** Null: the general conversation. */
    val tripId: String?,
    val tripNo: String?,
    /** Newest unread incoming message. */
    val latest: DriverMessage,
    val unreadCount: Int,
)

interface MessageNotifier {
    fun ensureChannel()

    /** The driver allows notifications (Android 13+ permission and the system settings). */
    fun canNotify(): Boolean

    /** One notification per conversation; a newer message replaces the earlier one. */
    fun show(notification: MessageNotification)

    fun cancel(tripId: String?)

    fun cancelAll()
}

/** Registers the device's push token with the backend for the signed-in driver. */
interface PushRegistrar {
    suspend fun register(pushToken: String)

    /** Called before the session ends, while its access token is still valid. */
    suspend fun unregister()
}

/** Starts and stops push notifications with the driver's session. */
interface PushSession {
    fun onSignedIn()

    fun onSignedOut()
}

/** Conversations on screen: they are read by polling and need no notification. */
@Singleton
class OpenConversationTracker @Inject constructor() {
    /** Conversation tag to trip id; the general conversation has an empty trip id. */
    private val open = ConcurrentHashMap<String, String>()

    fun open(tripId: String?) {
        open[conversationTag(tripId)] = tripId.orEmpty()
    }

    fun close(tripId: String?) {
        open.remove(conversationTag(tripId))
    }

    fun isOpen(tripId: String?): Boolean = open.containsKey(conversationTag(tripId))

    /** Trip ids of the conversations on screen; null is the general conversation. */
    fun openConversations(): List<String?> = open.values.map { it.ifEmpty { null } }
}

/**
 * A push only says that a message arrived (docs/06): the inbox is read from the API, a conversation on screen is read
 * at once, and each conversation with a new unread incoming message that is not on screen gets its notification.
 */
@Singleton
class IncomingMessageHandler @Inject constructor(
    private val messageRepository: MessageRepository,
    private val notifier: MessageNotifier,
    private val openConversations: OpenConversationTracker,
) {
    /** Newest announced message per conversation: the same message never alerts twice in this process. */
    private val announced = ConcurrentHashMap<String, String>()

    /** Push message ids whose inbox read succeeded: FCM may deliver the same message more than once. */
    private val handledPushes = LinkedHashSet<String>()

    /** [messageId]: the push's `messageId`; a repeated delivery of an already handled message reads nothing. */
    suspend fun onMessageSignal(messageId: String? = null) {
        if (messageId != null && synchronized(handledPushes) { messageId in handledPushes }) return
        if (messageRepository.refreshConversations() == RefreshResult.SUCCESS && messageId != null) markHandled(messageId)
        // A conversation on screen shows the new message now instead of at its next poll.
        openConversations.openConversations().forEach { messageRepository.refresh(it) }
        messageRepository.observeConversations().first().conversations.forEach { conversation ->
            val latest = conversation.lastUnread ?: return@forEach
            if (openConversations.isOpen(conversation.tripId)) return@forEach
            val tag = conversationTag(conversation.tripId)
            if (announced[tag] == latest.id) return@forEach
            announced[tag] = latest.id
            notifier.show(MessageNotification(conversation.tripId, conversation.tripNo, latest, conversation.unreadCount))
        }
    }

    private fun markHandled(messageId: String) = synchronized(handledPushes) {
        handledPushes += messageId
        if (handledPushes.size > MAX_HANDLED_PUSHES) handledPushes.remove(handledPushes.first())
    }

    private companion object {
        const val MAX_HANDLED_PUSHES = 200
    }
}

/** A tapped notification asks to open this conversation. */
data class ConversationOpenRequest(val tripId: String?)

/** Hands notification taps from the activity to the navigation. */
@Singleton
class NotificationNavigation @Inject constructor() {
    private val requests = MutableStateFlow<ConversationOpenRequest?>(null)
    val pending: StateFlow<ConversationOpenRequest?> = requests.asStateFlow()

    /** The notification permission is asked at most once per app process. */
    @Volatile
    var permissionAsked: Boolean = false

    fun handle(intent: Intent?) {
        val tapped = intent ?: return
        if (tapped.getStringExtra(EXTRA_TYPE) != TYPE_DRIVER_MESSAGE) return
        requests.value = ConversationOpenRequest(tapped.getStringExtra(EXTRA_TRIP_ID)?.takeIf { it.isNotBlank() })
    }

    fun consume() {
        requests.value = null
    }
}
