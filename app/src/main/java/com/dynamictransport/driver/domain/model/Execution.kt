package com.dynamictransport.driver.domain.model

import java.time.Duration
import java.time.Instant

/** Action codes of the trip action workflow. Codes this app version does not know are never offered. */
enum class ExecutionActionCode {
    START,
    COMPLETE_STOP,
    REST,
    PROBLEM,
}

/**
 * One option of the server's `availableActions`. Code, stop and container are sent back unchanged;
 * [label] is the backend's English display fallback.
 */
data class ExecutionAction(
    val code: ExecutionActionCode,
    val label: String?,
    val stopId: String?,
    val containerId: String?,
) {
    /** Start or a stop/container completion: moves the trip forward. */
    val isProgress: Boolean get() = code == ExecutionActionCode.START || code == ExecutionActionCode.COMPLETE_STOP
}

/** Current execution plan of a trip (`GET trips/{tripId}/execution`). */
data class TripExecution(
    val tripId: String,
    /** Opaque; sent back as `expectedVersion`, never parsed or incremented. */
    val version: String,
    val status: String?,
    val workflow: TripWorkflow,
    val availableActions: List<ExecutionAction>,
    val documents: List<DocumentSlot>,
)

/** Optional device evidence of an action. The app sends none unless every contract rule holds. */
data class LocationEvidence(
    val observedAt: Instant,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
) {
    /** Null when the evidence would be refused by the backend; the action is then sent without it. */
    fun takeIfValid(now: Instant): LocationEvidence? = takeIf {
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            (accuracyMeters == null || accuracyMeters in 0.0..MAX_ACCURACY_METERS) &&
            !observedAt.isAfter(now.plus(MAX_CLOCK_AHEAD))
    }

    companion object {
        const val MAX_ACCURACY_METERS = 1_000_000.0
        val MAX_CLOCK_AHEAD: Duration = Duration.ofMinutes(5)
    }
}

/** Where a stored command stands. Stored commands are only ever resent with the same id and payload. */
enum class PendingCommandState {
    /** Being sent right now. */
    SENDING,

    /** The connection broke: the server may or may not have recorded it. */
    OUTCOME_UNKNOWN,

    /** Not processed by the server (session, permission or rate limit). */
    NOT_SENT,

    /** Recorded by the server; kept only until the next read shows it. */
    RECORDED,
}

/** A trip action stored on the device before it is sent (idempotent `commandId`). */
data class PendingTripAction(
    val commandId: String,
    val tripId: String,
    val expectedVersion: String,
    val action: ExecutionAction,
    val note: String?,
    val location: LocationEvidence?,
    val createdAt: Instant,
    val state: PendingCommandState,
)

/** Recorded action (`GET trips/{tripId}/actions`). Server receive time and device observation time are different facts. */
data class ActionReceipt(
    val id: String,
    /** Null for a code this app version does not know. */
    val action: ExecutionActionCode?,
    val stopId: String?,
    val containerId: String?,
    val note: String?,
    val receivedAt: Instant?,
    val observedAt: Instant?,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Double?,
)
