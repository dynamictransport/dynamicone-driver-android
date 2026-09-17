package com.dynamictransport.dynamicone.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dynamictransport.dynamicone.domain.model.BreakState
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.model.PendingActionState
import com.dynamictransport.dynamicone.domain.model.PendingActionType
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.RemoteDocumentStatus
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.model.SyncErrorCode
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import java.time.Instant

/**
 * A trip as last confirmed by the server (`server*`) plus the local projection (`local*`) that
 * replays pending offline actions on top of it. The UI always reads the projection.
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val tripNo: String,
    val pickupName: String,
    val deliveryName: String,
    val plannedStartAt: Instant?,
    val containerNo: String?,
    val pickupAddress: String?,
    val deliveryAddress: String?,
    val vehiclePlate: String?,
    val trailerPlate: String?,
    val sealNo: String?,
    val instructions: String?,
    val serverState: TripState,
    val serverBreakState: BreakState,
    /** Comma separated action names; null when the server list is unknown for [serverState]. */
    val serverAllowedActions: String?,
    val localState: TripState,
    val localBreakState: BreakState,
    val syncState: SyncState,
    val updatedAt: Instant,
)

@Entity(tableName = "shift")
data class ShiftEntity(
    @PrimaryKey val slot: Int = CURRENT_SLOT,
    val shiftId: String?,
    val serverStatus: ShiftStatus,
    val serverStartedAt: Instant?,
    val serverEndedAt: Instant?,
    val localStatus: ShiftStatus,
    val localStartedAt: Instant?,
    val localEndedAt: Instant?,
    val vehiclePlate: String?,
    val trailerPlate: String?,
    val syncState: SyncState,
    val updatedAt: Instant,
) {
    companion object {
        const val CURRENT_SLOT = 0
    }
}

@Entity(tableName = "driver_profile")
data class DriverProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val personnelNo: String,
    val preferredLanguage: String?,
)

enum class PendingEntityType {
    TRIP,
    SHIFT,
    INCIDENT,
    DOCUMENT,
}

/** Durable offline queue (docs/04_OFFLINE_SYNC.md). Rows are never deleted silently. */
@Entity(
    tableName = "pending_actions",
    indices = [
        Index(value = ["clientActionId"], unique = true),
        Index(value = ["entityType", "entityId"]),
        Index(value = ["state"]),
    ],
)
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientActionId: String,
    val driverId: String,
    val entityType: PendingEntityType,
    val entityId: String,
    val actionType: PendingActionType,
    val tripAction: TripActionType?,
    val payloadJson: String,
    val createdAt: Instant,
    val retryCount: Int,
    val state: PendingActionState,
    val lastError: SyncErrorCode?,
    val acknowledged: Boolean,
    val updatedAt: Instant,
)

/** Document metadata. The photo itself stays in app-private files ([localFilePath]). */
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["clientActionId"], unique = true),
    ],
)
data class DocumentEntity(
    @PrimaryKey val localId: String,
    val tripId: String,
    val type: DocumentType,
    val clientActionId: String,
    val localFilePath: String?,
    val fileName: String,
    val contentType: String,
    val capturedAt: Instant?,
    val containerNo: String?,
    val sealNo: String?,
    val remoteId: String?,
    val remoteStatus: RemoteDocumentStatus?,
    val thumbnailUrl: String?,
    val syncState: SyncState,
)

enum class PendingCommandKind {
    TRIP_ACTION,
    MESSAGE,
}

/**
 * Driver API command stored before it is sent (idempotent `commandId`). A stored command is only ever
 * resent with exactly the same payload; it is removed once the server outcome is known.
 */
@Entity(
    tableName = "pending_commands",
    indices = [Index(value = ["kind", "tripId"])],
)
data class PendingCommandEntity(
    @PrimaryKey val commandId: String,
    val driverId: String,
    val kind: PendingCommandKind,
    val tripId: String?,
    val payloadJson: String,
    val state: PendingCommandState,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val instant: Instant?,
) {
    companion object {
        const val LAST_SUCCESSFUL_SYNC = "last_successful_sync"
    }
}
