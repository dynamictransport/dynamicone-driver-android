package com.dynamictransport.dynamicone.domain.model

import java.time.Duration
import java.time.Instant

/** Action codes of the trip action workflow. Codes this app version does not know are never offered. */
enum class ExecutionActionCode {
    START,
    COMPLETE_STOP,
    REST,
    PROBLEM,

    /** Export loading stop (docs/27): physical loading is finished; the stop stays open. */
    CONFIRM_LOADING,

    /** Export loading stop (docs/27): the seal number of one container, sent with `sealNumber`. */
    RECORD_SEAL_NUMBER,
}

/** The server's stated `blockingReason` of an action. Evidence slots are listed separately. */
enum class ExecutionBlock {
    /** docs/20: the trailer under the truck has to be confirmed first. */
    TRAILER_CONFIRMATION_REQUIRED,

    /** docs/27: the stop closes only after the loading confirmation. */
    LOADING_CONFIRMATION_REQUIRED,

    /** docs/27: the stop closes only after the seal number of the container. */
    SEAL_NUMBER_REQUIRED,

    /** A reason this app version does not know; the action is blocked all the same. */
    UNKNOWN,
    ;

    /** The export loading stop is closed by the planner, never by the app (docs/27). */
    val isExportSealGate: Boolean get() = this == LOADING_CONFIRMATION_REQUIRED || this == SEAL_NUMBER_REQUIRED
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
    /** The server's stated reason while the action cannot be executed; null when none was given. */
    val blockingReason: ExecutionBlock? = null,
    /** docs/27, `RecordSealNumber`: the seal number already recorded for this container of this trip. */
    val sealNumber: String? = null,
    /** docs/27: display data of the step, as the server sent it. */
    val containerNumber: String? = null,
    val stopName: String? = null,
) {
    /** Start or a stop/container completion: moves the trip forward. */
    val isProgress: Boolean get() = code == ExecutionActionCode.START || code == ExecutionActionCode.COMPLETE_STOP

    /** Loading confirmation or seal entry of an export loading stop: recorded, but never closes the stop (docs/27). */
    val isExportLoadingStep: Boolean
        get() = code == ExecutionActionCode.CONFIRM_LOADING || code == ExecutionActionCode.RECORD_SEAL_NUMBER

    /** Offered, but not possible before the listed evidence is uploaded. A contradictory answer counts as blocked. */
    val isBlocked: Boolean get() = !canExecute || blockingDocumentSlots.isNotEmpty()

    /** The same step of the plan (code, stop and container); whether it is blocked may change meanwhile. */
    fun isSameStep(other: ExecutionAction): Boolean =
        code == other.code && stopId == other.stopId && containerId == other.containerId
}

/**
 * The seal of one container at an export loading stop (docs/27): the seal number and the seal photo are
 * two separate server records. One of them never stands in for the other.
 */
data class SealEntry(
    /** The server's `RecordSealNumber` option of this container. */
    val action: ExecutionAction,
    /** The `Seal` slot of the same stop and container; null when the plan has no such slot. */
    val photoSlot: DocumentSlot?,
) {
    val containerId: String? get() = action.containerId

    /** The seal number recorded on the server for this trip and container. */
    val recordedSealNumber: String? get() = action.sealNumber

    val sealNumberRecorded: Boolean get() = !recordedSealNumber.isNullOrBlank()

    /** Uploaded and waiting for review, approved, or not required: the photo asks nothing more of the driver. */
    val photoDone: Boolean
        get() = when (photoSlot?.state) {
            null -> true
            DocumentSlotState.PENDING_REVIEW, DocumentSlotState.APPROVED, DocumentSlotState.NOT_REQUIRED -> true
            DocumentSlotState.MISSING, DocumentSlotState.REJECTED, DocumentSlotState.UNKNOWN -> false
        }

    /** Both records are on the server. Only then does the stop wait for the planner. */
    val complete: Boolean get() = sealNumberRecorded && photoDone
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
     * The export loading stop of the plan, if the current stop is one (docs/27): the server offers the
     * loading confirmation or seal entries for it, or blocks its completion for the seal gate. The app
     * never sends `CompleteStop` for this stop; the planner closes it.
     */
    val exportLoadingStopId: String?
        get() = availableActions.firstOrNull { it.isExportLoadingStep }?.stopId
            ?: availableActions.firstOrNull { it.code == ExecutionActionCode.COMPLETE_STOP && it.blockingReason?.isExportSealGate == true }?.stopId

    /** The server's loading confirmation option; null once loading is confirmed or outside export loading. */
    val loadingConfirmation: ExecutionAction?
        get() = availableActions.firstOrNull { it.code == ExecutionActionCode.CONFIRM_LOADING }

    /** One entry per container the server still lists for seal entry, in server order, with its seal photo slot. */
    val sealEntries: List<SealEntry>
        get() = availableActions.filter { it.code == ExecutionActionCode.RECORD_SEAL_NUMBER }.map { action ->
            SealEntry(action, documents.firstOrNull { it.isSealPhotoOf(action.stopId, action.containerId) })
        }

    /**
     * Slots that block a start or stop/container completion, in checklist order. The server decides which
     * evidence blocks which step; keys that are not in the checklist cannot be shown and are left out.
     * Seal photos of the export loading stop belong to the seal entries and are not repeated here.
     */
    val blockingSlots: List<DocumentSlot>
        get() {
            val keys = availableActions.filter { it.isProgress && it.isBlocked }.flatMapTo(HashSet()) { it.blockingDocumentSlots }
            val exportStop = exportLoadingStopId
            return documents.filter { it.key in keys && !(exportStop != null && it.type == DocumentType.SEAL_PHOTO && it.stopId == exportStop) }
        }

    private fun DocumentSlot.isSealPhotoOf(stopId: String?, containerId: String?): Boolean =
        type == DocumentType.SEAL_PHOTO && this.stopId == stopId && this.containerId == containerId
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
    /** docs/27: the seal number of a `RecordSealNumber` command, part of the stored payload. */
    val sealNumber: String? = null,
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
