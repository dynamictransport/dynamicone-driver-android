package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

enum class SignedDocumentType {
    /** Compensation / payment document. */
    COMPENSATION,

    /** Leave document. */
    LEAVE,

    /** A type this app version does not know. */
    UNKNOWN,
}

/** A personnel document the driver signed; its content is the PDF stored at signing time. */
data class SignedDocument(
    val id: String,
    val type: SignedDocumentType,
    val signedAt: Instant?,
)

enum class MessageDirection {
    /** Sent by the driver. */
    FROM_DRIVER,

    /** Sent to the driver. */
    TO_DRIVER,
}

/** A persisted message of the general conversation ([tripId] null) or of one trip conversation. */
data class DriverMessage(
    val id: String,
    val tripId: String?,
    val body: String,
    val direction: MessageDirection,
    val createdAt: Instant?,
    /** For an outgoing message: when the other side read it. */
    val readAt: Instant?,
    /** Full name of the person who wrote the message; null when the server does not send it. */
    val senderName: String? = null,
)

/** A message stored on the device with its idempotent command id before it is sent. */
data class PendingMessage(
    val commandId: String,
    val tripId: String?,
    val body: String,
    val createdAt: Instant,
    val state: PendingCommandState,
)

data class Conversation(
    /** Null: the general conversation with the office. */
    val tripId: String?,
    /** Oldest first, without duplicates. */
    val messages: List<DriverMessage>,
    /** Messages not (yet) visible in the server list. */
    val pending: List<PendingMessage>,
    val canLoadOlder: Boolean,
    /** At least one page was read from the server. */
    val loaded: Boolean,
) {
    companion object {
        fun empty(tripId: String?) = Conversation(tripId, emptyList(), emptyList(), canLoadOlder = false, loaded = false)
    }
}

/** One row of the conversation list: the general conversation or the conversation of one trip. */
data class ConversationSummary(
    /** Null: the general conversation. */
    val tripId: String?,
    /** Trip number, when the app knows it. */
    val tripNo: String?,
    /** Newest known message; null when the conversation has none yet. */
    val lastMessage: DriverMessage?,
    /** Incoming messages without a read receipt among the messages read so far. */
    val unreadCount: Int,
    /** Newest incoming message without a read receipt; what a notification announces. */
    val lastUnread: DriverMessage? = null,
)

data class ConversationList(
    /** The general conversation first, then trip conversations with the newest message first. */
    val conversations: List<ConversationSummary>,
    /** The inbox was read from the server at least once. */
    val loaded: Boolean,
) {
    val totalUnread: Int get() = conversations.sumOf { it.unreadCount }

    fun unreadFor(tripId: String?): Int = conversations.firstOrNull { it.tripId == tripId }?.unreadCount ?: 0

    companion object {
        val EMPTY = ConversationList(emptyList(), loaded = false)
    }
}
