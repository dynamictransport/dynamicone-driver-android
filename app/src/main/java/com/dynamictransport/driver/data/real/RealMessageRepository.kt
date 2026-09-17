package com.dynamictransport.driver.data.real

import com.dynamictransport.driver.core.common.IdGenerator
import com.dynamictransport.driver.core.network.DriverApi
import com.dynamictransport.driver.core.network.DriverMessageReadDto
import com.dynamictransport.driver.core.network.DriverSendMessageDto
import com.dynamictransport.driver.core.network.RemoteException
import com.dynamictransport.driver.core.network.requireSuccess
import com.dynamictransport.driver.domain.model.ActionRejection
import com.dynamictransport.driver.domain.model.ActionResult
import com.dynamictransport.driver.domain.model.Conversation
import com.dynamictransport.driver.domain.model.ConversationList
import com.dynamictransport.driver.domain.model.ConversationSummary
import com.dynamictransport.driver.domain.model.DriverMessage
import com.dynamictransport.driver.domain.model.MessageDirection
import com.dynamictransport.driver.domain.model.PendingCommandState
import com.dynamictransport.driver.domain.model.PendingMessage
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.repository.MessageRepository
import com.dynamictransport.driver.domain.rules.DriverInputRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The general conversation with the office and one conversation per trip. Pages are merged by message id, so a
 * message is never shown twice. A new message is stored with its command id before sending; the server records
 * the message under that id, which is how a stored message is matched with the server list.
 *
 * The conversation list is the inbox (unfiltered `GET messages`, TH-2026-0440) grouped by trip. Messages read in
 * an open conversation and read receipts update that list too, so its last message and unread count follow what
 * the driver has seen.
 */
@Singleton
class RealMessageRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
    private val store: PendingCommandStore,
    private val trips: RealTripRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) : MessageRepository {

    private data class Thread(
        val messages: Map<String, DriverMessage> = emptyMap(),
        val nextPage: Int = 2,
        val hasMore: Boolean = false,
        val loaded: Boolean = false,
    )

    /** Keyed by trip id; the null key is the general conversation. */
    private val threads = MutableStateFlow<Map<String?, Thread>>(emptyMap())

    /** Inbox messages by id; null until the inbox was read once. */
    private val inbox = MutableStateFlow<Map<String, DriverMessage>?>(null)

    /** Numbers of trips with messages that are not in the trip list, for example older completed trips. */
    private val lookedUpTripNumbers = MutableStateFlow<Map<String, String>>(emptyMap())
    private val readSent = ConcurrentHashMap.newKeySet<String>()
    private val readMutex = Mutex()
    private val sendMutex = Mutex()

    override fun observeConversations(): Flow<ConversationList> = combine(
        inbox,
        trips.observeTrips(),
        lookedUpTripNumbers,
    ) { messages, knownTrips, lookedUp ->
        if (messages == null) return@combine ConversationList.EMPTY
        val numbers = lookedUp + knownTrips.associate { it.id to it.tripNo }
        val byTrip = messages.values.groupBy { it.tripId }
        val tripConversations = byTrip.keys.filterNotNull()
            .map { tripId -> summary(tripId, numbers[tripId], byTrip.getValue(tripId)) }
            .sortedWith(compareByDescending<ConversationSummary, Instant?>(nullsFirst()) { it.lastMessage?.createdAt }.thenBy { it.tripId })
        // The general conversation is always offered first, even before its first message.
        ConversationList(listOf(summary(null, null, byTrip[null].orEmpty())) + tripConversations, loaded = true)
    }

    override suspend fun refreshConversations(): RefreshResult = readMutex.withLock {
        read {
            val page = tracker.call { api.messages(tripId = null, generalOnly = null, page = 1, pageSize = INBOX_PAGE_SIZE) }
            val loaded = page.items.orEmpty().mapNotNull(DriverApiMapping::message)
            inbox.update { current -> current.orEmpty() + loaded.associateBy { it.id } }
            lookUpTripNumbers(loaded.mapNotNullTo(LinkedHashSet()) { it.tripId })
        }
    }

    override fun observeConversation(tripId: String?): Flow<Conversation> = combine(
        threads.map { it[tripId] }.distinctUntilChanged(),
        store.observeMessages(tripId),
    ) { thread, pending ->
        val serverIds = thread?.messages?.keys.orEmpty()
        Conversation(
            tripId = tripId,
            messages = thread?.messages?.values.orEmpty()
                .sortedWith(compareBy<DriverMessage, Instant?>(nullsFirst()) { it.createdAt }.thenBy { it.id }),
            pending = pending.filter { it.commandId !in serverIds },
            canLoadOlder = thread?.hasMore == true,
            loaded = thread?.loaded == true,
        )
    }

    override suspend fun refresh(tripId: String?): RefreshResult = readMutex.withLock {
        read {
            val page = tracker.call { conversationPage(tripId, 1) }
            val loaded = page.items.orEmpty().mapNotNull(DriverApiMapping::message).filter { it.tripId == tripId }
            threads.update { all ->
                val current = all[tripId] ?: Thread()
                all + (
                    tripId to current.copy(
                        messages = current.messages + loaded.associateBy { it.id },
                        hasMore = if (current.loaded) current.hasMore else page.hasMore,
                        loaded = true,
                    )
                    )
            }
            keepInInbox(loaded)
            // Stored messages the server now lists were recorded.
            store.deleteAll(loaded.map { it.id })
        }
    }

    override suspend fun loadOlder(tripId: String?): RefreshResult = readMutex.withLock {
        val current = threads.value[tripId]
        if (current == null || !current.loaded || !current.hasMore) return@withLock RefreshResult.SUCCESS
        read {
            val page = tracker.call { conversationPage(tripId, current.nextPage) }
            val loaded = page.items.orEmpty().mapNotNull(DriverApiMapping::message).filter { it.tripId == tripId }
            threads.update { all ->
                val thread = all[tripId] ?: current
                all + (
                    tripId to thread.copy(
                        messages = thread.messages + loaded.associateBy { it.id },
                        nextPage = current.nextPage + 1,
                        hasMore = page.hasMore,
                    )
                    )
            }
            keepInInbox(loaded)
        }
    }

    override suspend fun send(tripId: String?, body: String): ActionResult {
        DriverInputRules.validateMessage(body)?.let { return ActionResult.Rejected(it) }
        val message = PendingMessage(ids.newId(), tripId, body.trim(), clock.instant(), PendingCommandState.SENDING)
        return sendMutex.withLock {
            if (!store.save(message)) return@withLock ActionResult.Rejected(ActionRejection.NOT_LOGGED_IN)
            deliver(message)
        }
    }

    override suspend fun resend(commandId: String): ActionResult = sendMutex.withLock {
        val message = store.message(commandId) ?: return@withLock ActionResult.Rejected(ActionRejection.NOT_FOUND)
        if (message.state == PendingCommandState.RECORDED) return@withLock ActionResult.Queued
        deliver(message)
    }

    override suspend fun discard(commandId: String) {
        sendMutex.withLock { store.delete(commandId) }
    }

    override suspend fun markRead(tripId: String?, messageId: String): Boolean {
        val message = threads.value[tripId]?.messages?.get(messageId) ?: return false
        if (message.direction != MessageDirection.TO_DRIVER || message.readAt != null) return false
        if (!readSent.add(messageId)) return false
        return try {
            tracker.call { api.markMessageRead(messageId, DriverMessageReadDto).requireSuccess() }
            // The server keeps its own timestamp; locally the message only has to stop counting as unread.
            markReadLocally(message.copy(readAt = clock.instant()))
            true
        } catch (e: CancellationException) {
            readSent.remove(messageId)
            throw e
        } catch (_: RemoteException) {
            // Tried again the next time the message is on screen.
            readSent.remove(messageId)
            false
        }
    }

    fun clear() {
        threads.value = emptyMap()
        inbox.value = null
        lookedUpTripNumbers.value = emptyMap()
        readSent.clear()
    }

    /** General conversation only with `generalOnly`; backends before TH-2026-0440 ignore it and return the same. */
    private suspend fun conversationPage(tripId: String?, page: Int) =
        api.messages(tripId = tripId, generalOnly = if (tripId == null) true else null, page = page, pageSize = PAGE_SIZE)

    private fun summary(tripId: String?, tripNo: String?, messages: List<DriverMessage>): ConversationSummary {
        val newestFirst = compareBy<DriverMessage, Instant?>(nullsFirst()) { it.createdAt }.thenBy { it.id }
        val unread = messages.filter { it.direction == MessageDirection.TO_DRIVER && it.readAt == null }
        return ConversationSummary(
            tripId = tripId,
            tripNo = tripNo,
            lastMessage = messages.maxWithOrNull(newestFirst),
            unreadCount = unread.size,
            lastUnread = unread.maxWithOrNull(newestFirst),
        )
    }

    /** Messages read in a conversation belong to the inbox too, once the inbox has been read. */
    private fun keepInInbox(messages: List<DriverMessage>) {
        if (messages.isNotEmpty()) inbox.update { current -> current?.plus(messages.associateBy { it.id }) }
    }

    private fun markReadLocally(message: DriverMessage) {
        threads.update { all ->
            all.mapValues { (_, thread) ->
                if (message.id in thread.messages) thread.copy(messages = thread.messages + (message.id to message)) else thread
            }
        }
        inbox.update { current -> current?.takeIf { message.id in it }?.plus(message.id to message) ?: current }
    }

    /** Trip numbers for conversation rows; a trip that cannot be read is listed without its number. */
    private suspend fun lookUpTripNumbers(tripIds: Set<String>) {
        val known = trips.currentTrips().mapTo(HashSet()) { it.id } + lookedUpTripNumbers.value.keys
        tripIds.filterNot { it in known }.take(MAX_TRIP_LOOKUPS).forEach { tripId ->
            val number = try {
                tracker.call { api.trip(tripId) }.trip?.tripNumber?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RemoteException) {
                null
            }
            if (number != null) lookedUpTripNumbers.update { it + (tripId to number) }
        }
    }

    private suspend fun deliver(message: PendingMessage): ActionResult {
        store.markState(message.commandId, PendingCommandState.SENDING)
        val response = try {
            tracker.call { api.sendMessage(DriverSendMessageDto(message.commandId, message.body, message.tripId)) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.markState(message.commandId, PendingCommandState.OUTCOME_UNKNOWN) }
            throw e
        } catch (e: RemoteException) {
            return failed(message, e)
        }
        if (!response.succeeded) {
            store.delete(message.commandId)
            return ActionResult.Rejected(ActionRejection.FAILED)
        }
        // The POST returns no message: the stored one stays visible until the list shows the server record.
        store.markState(message.commandId, PendingCommandState.RECORDED)
        refresh(message.tripId)
        return ActionResult.Queued
    }

    private suspend fun failed(message: PendingMessage, e: RemoteException): ActionResult = when {
        e.isOutcomeUnknown() -> {
            store.markState(message.commandId, PendingCommandState.OUTCOME_UNKNOWN)
            ActionResult.Rejected(ActionRejection.OUTCOME_UNKNOWN)
        }
        e is RemoteException.Conflict || e is RemoteException.Validation || e is RemoteException.Rejected ||
            e is RemoteException.PayloadTooLarge || (e is RemoteException.NotFound && !e.isApiDisabled) -> {
            store.delete(message.commandId)
            ActionResult.Rejected(e.toActionRejection())
        }
        else -> {
            store.markState(message.commandId, PendingCommandState.NOT_SENT)
            ActionResult.Rejected(e.toActionRejection())
        }
    }

    private suspend fun read(block: suspend () -> Unit): RefreshResult = try {
        block()
        tracker.markReadSucceeded()
        RefreshResult.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        e.toRefreshResult()
    }

    private companion object {
        const val PAGE_SIZE = 25

        /** The largest page the driver API returns; unread counts are based on these newest inbox messages. */
        const val INBOX_PAGE_SIZE = 100

        /** Trip number reads per inbox read, so a long history never turns one read into many requests. */
        const val MAX_TRIP_LOOKUPS = 5
    }
}
