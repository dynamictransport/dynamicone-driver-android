package com.dynamictransport.dynamicone.domain.model

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
    /** Keys of the plan's document slots that must be uploaded before this action (docs/18). */
    val blockingDocumentSlots: List<String> = emptyList(),
    /** The server's `canExecute`; a backend that does not send it blocks nothing. */
    val canExecute: Boolean = true,
    /** docs/20: refused until the driver confirms the trailer under the truck; not an evidence problem. */
    val blockedByTrailer: Boolean = false,
) {
    /** Start or a stop/container completion: moves the trip forward. */
    val isProgress: Boolean get() = code == ExecutionActionCode.START || code == ExecutionActionCode.COMPLETE_STOP

    /** Offered, but not possible before the listed evidence is uploaded. A contradictory answer counts as blocked. */
    val isBlocked: Boolean get() = !canExecute || blockingDocumentSlots.isNotEmpty()

    /** The same step of the plan (code, stop and container); whether it is blocked may change meanwhile. */
    fun isSameStep(other: ExecutionAction): Boolean =
        code == other.code && stopId == other.stopId && containerId == other.containerId
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
    /** The driver has to confirm the trailer under the truck before the trip can start (docs/20). */
    val trailerConfirmationRequired: Boolean = false,
) {
    /**
     * Slots that block a start or stop/container completion, in checklist order. The server decides which
     * evidence blocks which step; keys that are not in the checklist cannot be shown and are left out.
     */
    val blockingSlots: List<DocumentSlot>
        get() {
            val keys = availableActions.filter { it.isProgress && it.isBlocked }.flatMapTo(HashSet()) { it.blockingDocumentSlots }
            return documents.filter { it.key in keys }
        }
}

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
