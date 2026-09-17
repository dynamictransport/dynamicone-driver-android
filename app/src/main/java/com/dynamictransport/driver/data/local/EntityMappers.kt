package com.dynamictransport.driver.data.local

import com.dynamictransport.driver.core.database.DocumentEntity
import com.dynamictransport.driver.core.database.DriverProfileEntity
import com.dynamictransport.driver.core.database.PendingActionEntity
import com.dynamictransport.driver.core.database.ShiftEntity
import com.dynamictransport.driver.core.database.TripEntity
import com.dynamictransport.driver.domain.model.BreakState
import com.dynamictransport.driver.domain.model.DocumentSlot
import com.dynamictransport.driver.domain.model.DocumentSlotState
import com.dynamictransport.driver.domain.model.DocumentType
import com.dynamictransport.driver.domain.model.Driver
import com.dynamictransport.driver.domain.model.FailedAction
import com.dynamictransport.driver.domain.model.PendingActionState
import com.dynamictransport.driver.domain.model.RemoteDocumentStatus
import com.dynamictransport.driver.domain.model.Shift
import com.dynamictransport.driver.domain.model.ShiftStatus
import com.dynamictransport.driver.domain.model.SyncErrorCode
import com.dynamictransport.driver.domain.model.SyncState
import com.dynamictransport.driver.domain.model.Trip
import com.dynamictransport.driver.domain.model.TripActionRecord
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.model.TripDocument
import java.time.Instant

fun TripEntity.toDomain(): Trip {
    // The server list only describes the server state. Once a pending action moved the projection
    // forward, local rules decide until the server confirms.
    val projectionMatchesServer = localState == serverState && localBreakState == serverBreakState
    return Trip(
        id = id,
        tripNo = tripNo,
        state = localState,
        breakState = localBreakState,
        pickupName = pickupName,
        deliveryName = deliveryName,
        plannedStartAt = plannedStartAt,
        containerNo = containerNo,
        pickupAddress = pickupAddress,
        deliveryAddress = deliveryAddress,
        vehiclePlate = vehiclePlate,
        trailerPlate = trailerPlate,
        sealNo = sealNo,
        instructions = instructions,
        serverAllowedActions = if (projectionMatchesServer) serverAllowedActions?.let(::parseActions) else null,
        syncState = syncState,
    )
}

fun parseActions(csv: String): Set<TripActionType> =
    csv.split(',')
        .filter { it.isNotBlank() }
        .map(TripActionType::fromWire)
        .filterNot { it == TripActionType.UNKNOWN }
        .toSet()

fun Set<TripActionType>.toCsv(): String = joinToString(",") { it.name }

fun ShiftEntity.toDomain() = Shift(
    id = shiftId,
    status = localStatus,
    startedAt = localStartedAt,
    endedAt = localEndedAt,
    vehiclePlate = vehiclePlate,
    trailerPlate = trailerPlate,
    syncState = syncState,
)

fun initialShiftEntity(now: Instant) = ShiftEntity(
    shiftId = null,
    serverStatus = ShiftStatus.NOT_STARTED,
    serverStartedAt = null,
    serverEndedAt = null,
    localStatus = ShiftStatus.NOT_STARTED,
    localStartedAt = null,
    localEndedAt = null,
    vehiclePlate = null,
    trailerPlate = null,
    syncState = SyncState.SYNCED,
    updatedAt = now,
)

fun newTripEntity(id: String, now: Instant) = TripEntity(
    id = id,
    tripNo = "",
    pickupName = "",
    deliveryName = "",
    plannedStartAt = null,
    containerNo = null,
    pickupAddress = null,
    deliveryAddress = null,
    vehiclePlate = null,
    trailerPlate = null,
    sealNo = null,
    instructions = null,
    serverState = com.dynamictransport.driver.domain.model.TripState.UNKNOWN,
    serverBreakState = BreakState.NONE,
    serverAllowedActions = null,
    localState = com.dynamictransport.driver.domain.model.TripState.UNKNOWN,
    localBreakState = BreakState.NONE,
    syncState = SyncState.SYNCED,
    updatedAt = now,
)

fun DriverProfileEntity.toDomain() = Driver(
    id = id,
    displayName = displayName,
    personnelNo = personnelNo.takeIf { it.isNotBlank() },
    preferredLanguage = preferredLanguage,
)

fun Driver.toEntity() = DriverProfileEntity(
    id = id,
    displayName = displayName,
    personnelNo = personnelNo.orEmpty(),
    preferredLanguage = preferredLanguage,
)

/**
 * Demo backend: it has no requirement checklist, so every category is one trip-level slot without a
 * requirement. The newest local photo decides the state; the queue state is shown separately.
 */
fun DocumentEntity?.toDemoSlot(type: DocumentType) = DocumentSlot(
    key = "demo:${type.name}",
    type = type,
    label = null,
    stopId = null,
    containerId = null,
    required = null,
    state = when {
        this == null -> DocumentSlotState.MISSING
        syncState != SyncState.SYNCED -> DocumentSlotState.PENDING_REVIEW
        remoteStatus == RemoteDocumentStatus.REJECTED -> DocumentSlotState.REJECTED
        remoteStatus == RemoteDocumentStatus.VERIFIED -> DocumentSlotState.APPROVED
        else -> DocumentSlotState.PENDING_REVIEW
    },
    documentId = this?.localId,
    fileName = this?.fileName?.takeIf { it.isNotBlank() },
    reviewNote = null,
    revision = null,
    localSync = this?.syncState,
)

fun DocumentEntity.toTripDocument() = TripDocument(
    id = localId,
    tripId = tripId,
    type = type,
    fileName = fileName.takeIf { it.isNotBlank() },
    contentType = contentType.takeIf { it.isNotBlank() },
    submittedAt = capturedAt,
)

fun PendingActionEntity.toTripActionRecord(): TripActionRecord? = tripAction?.let {
    TripActionRecord(
        action = it,
        occurredAt = createdAt,
        syncState = if (state == PendingActionState.SYNCED) SyncState.SYNCED else SyncState.PENDING,
    )
}

fun PendingActionEntity.toFailedAction() = FailedAction(
    clientActionId = clientActionId,
    type = actionType,
    entityId = entityId,
    tripAction = tripAction,
    errorCode = lastError ?: SyncErrorCode.UNKNOWN,
    createdAt = createdAt,
)
