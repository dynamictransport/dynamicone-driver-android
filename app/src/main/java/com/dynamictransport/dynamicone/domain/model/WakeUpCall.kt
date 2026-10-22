package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

/**
 * Wake-up call (docs/25): the planner rings the driver's tablet and phone from the cockpit. Only real calls of
 * the server ring; the app never invents one.
 */
enum class WakeUpCallStatus(val wire: String) {
    PENDING("Pending"),
    RINGING("Ringing"),
    ACKNOWLEDGED("Acknowledged"),
    EXPIRED("Expired"),
    CANCELLED("Cancelled"),
    FAILED("Failed"),
    ;

    /** The call still waits for the driver. */
    val isOpen: Boolean get() = this == PENDING || this == RINGING

    companion object {
        fun fromWire(value: String?): WakeUpCallStatus? = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

/** Why the office calls (docs/25 §5); a code the app does not know is shown like [OTHER]. */
enum class WakeUpReason(val wire: String) {
    CALL_OFFICE("call_office"),
    DEPARTURE_TIME("departure_time"),
    LOADING_TIME_CHANGED("loading_time_changed"),
    TRIP_CHANGED("trip_changed"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String?): WakeUpReason = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: OTHER
    }
}

/** A call as the server describes it (or, while the server cannot be reached, as the push announced it). */
data class WakeUpCall(
    val callId: String,
    val status: WakeUpCallStatus,
    val reason: WakeUpReason,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val requestedByDisplayName: String?,
    val tripId: String?,
    val tripNo: String?,
) {
    fun isExpiredAt(now: Instant): Boolean = !now.isBefore(expiresAt)
}

/** The device's answer to a `ring` push: what to do now. */
sealed interface WakeUpDecision {
    /** Ring now; the call is open on the server (or the server could not say otherwise). */
    data class Ring(val call: WakeUpCall) : WakeUpDecision

    /** Nothing to do: closed, unknown, expired, already answered on this device. */
    data object Ignore : WakeUpDecision
}

/** What happened to a call on this device, in the order it moves. */
enum class WakeUpOutcomeState {
    /** Rings right now. */
    RINGING,

    /** The driver answered; the answer waits for the connection. */
    ACKNOWLEDGED_PENDING,

    /** The server has the answer. */
    ACKNOWLEDGED,

    /** Rang until the deadline, nobody answered (the report may still be on its way). */
    NO_ANSWER,

    /** Silenced by the server: answered on another device or cancelled by the planner. */
    STOPPED,
}

/** The latest call of the signed-in driver, for the banner on the home screen. */
data class WakeUpOutcome(
    val callId: String,
    val reason: WakeUpReason,
    val requestedByDisplayName: String?,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val state: WakeUpOutcomeState,
    val acknowledgedAt: Instant?,
    val updatedAt: Instant,
)
