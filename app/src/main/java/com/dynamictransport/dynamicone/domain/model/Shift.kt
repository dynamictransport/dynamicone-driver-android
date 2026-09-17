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
) {
    val isActive: Boolean get() = status == ShiftStatus.ACTIVE

    /** False when the backend does not support shifts; trip actions are then not gated by a shift. */
    val isTracked: Boolean get() = status != ShiftStatus.UNAVAILABLE

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
