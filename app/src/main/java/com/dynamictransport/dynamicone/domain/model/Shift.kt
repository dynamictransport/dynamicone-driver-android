package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

enum class ShiftStatus {
    NOT_STARTED,
    ACTIVE,
    FINISHED,

    /** The connected backend has no shift/attendance endpoints: nothing can be started or ended. */
    UNAVAILABLE,
    ;

    companion object {
        fun fromWire(value: String?): ShiftStatus = entries.firstOrNull { it.name == value } ?: NOT_STARTED
    }
}

data class Shift(
    val id: String?,
    val status: ShiftStatus,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val vehiclePlate: String?,
    val trailerPlate: String?,
    val syncState: SyncState = SyncState.SYNCED,
    /**
     * Driver API daily shift (docs/16): started at the vehicle with its tag and the daily check, ended for the daily
     * rest (also during a trip), and required before trip work continues.
     */
    val requiresVehicleCheck: Boolean = false,
    /** Command id of the start, known for daily shifts; ending refers to it while the start is not confirmed. */
    val startCommandId: String? = null,
    val identifiedBy: VehicleIdentification? = null,
    val vehicleCheck: VehicleCheckSummary? = null,
    /** Started although the tachograph rest was not over. */
    val startedBeforeRestEnd: Boolean = false,
    /** Ended because the tachograph shows a daily rest that began after the start (docs/16 §9.1). */
    val endedByTachograph: Boolean = false,
) {
    val isActive: Boolean get() = status == ShiftStatus.ACTIVE

    /** False when the backend does not support shifts; trip actions are then not gated by a shift. */
    val isTracked: Boolean get() = status != ShiftStatus.UNAVAILABLE

    /** A daily shift that is not running: trip work waits for the start at the vehicle. */
    val startRequired: Boolean get() = requiresVehicleCheck && !isActive

    companion object {
        val NotStarted = Shift(
            id = null,
            status = ShiftStatus.NOT_STARTED,
            startedAt = null,
            endedAt = null,
            vehiclePlate = null,
            trailerPlate = null,
        )

        val Unavailable = NotStarted.copy(status = ShiftStatus.UNAVAILABLE)
    }
}

/** A shift as the server records it (`GET shift`, docs/16 §4). */
data class ShiftRecord(
    val id: String,
    val commandId: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val endedBy: ShiftEndedBy?,
    val vehicle: VehicleInfo?,
    val identifiedBy: VehicleIdentification?,
    val trailerPlate: String?,
    val startedBeforeRestEnd: Boolean,
    val vehicleCheck: VehicleCheckSummary?,
)

enum class ShiftEndedBy {
    DRIVER,
    TACHOGRAPH,
    OFFICE,
    UNKNOWN,
}

/** The server's shift state of the driver. */
data class ServerShiftState(
    val active: ShiftRecord?,
    val lastEnded: ShiftRecord?,
    val plannedVehicle: VehicleInfo?,
)

enum class ShiftCommandType {
    START,
    END,
}

enum class ShiftCommandState {
    /** Stored on the phone, waiting to be sent. [LocalShiftCommand.failure] may name why the server did not take it yet. */
    PENDING,
    SENDING,
    SYNCED,

    /** Rejected by the server; shown with the reason until the driver resends or removes it. */
    FAILED,
}

enum class ShiftCommandFailure {
    /** The tag is not registered on an active vehicle. */
    TAG_UNKNOWN,

    /** The check does not match the server's list (answers or photos missing). */
    CHECK_INVALID,

    /** The phone's time is in the future or too old. */
    TIME_REJECTED,

    /** Another shift is already running. */
    ALREADY_ACTIVE,

    /** The shift to end is not running (any more). */
    NOT_ACTIVE,

    /** The account may not use driver features (403): the command keeps waiting and is sent again later. */
    ACCESS_REQUIRED,
    CONFLICT,
    REJECTED,
}

/** A shift start or end stored on the phone. */
data class LocalShiftCommand(
    val commandId: String,
    val type: ShiftCommandType,
    /** Phone time of the start or end. */
    val occurredAt: Instant,
    val state: ShiftCommandState,
    val failure: ShiftCommandFailure?,
    val vehiclePlate: String?,
    val identifiedBy: VehicleIdentification?,
    val defectCount: Int,
    /** Defect photos of a start that are not on the server yet. */
    val photosWaiting: Int = 0,
    /** Defect photos the server did not accept. */
    val photosFailed: Int = 0,
    /** For an end: the start it closes. */
    val startCommandId: String? = null,
) {
    val isUnsent: Boolean get() = state == ShiftCommandState.PENDING || state == ShiftCommandState.SENDING
}

sealed interface ShiftCommandResult {
    /** The server recorded it. */
    data object Recorded : ShiftCommandResult

    /** Stored on the phone and sent automatically with the same command id; [hold] when the server answered (403). */
    data class Saved(val hold: ShiftCommandFailure? = null) : ShiftCommandResult

    /** The server rejected it; it stays on the phone as failed. */
    data class Failed(val failure: ShiftCommandFailure) : ShiftCommandResult

    /** Nothing was stored: the check is incomplete or the state does not allow it. */
    data class NotAllowed(val reason: ActionRejection) : ShiftCommandResult

    data object NotLoggedIn : ShiftCommandResult
}
