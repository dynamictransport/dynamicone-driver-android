package com.dynamictransport.driver.data.sync

import com.dynamictransport.driver.core.common.IdGenerator
import com.dynamictransport.driver.core.database.DocumentDao
import com.dynamictransport.driver.core.database.PendingActionDao
import com.dynamictransport.driver.core.database.PendingActionEntity
import com.dynamictransport.driver.core.database.PendingEntityType
import com.dynamictransport.driver.core.database.ShiftDao
import com.dynamictransport.driver.core.database.ShiftEntity
import com.dynamictransport.driver.core.database.TripDao
import com.dynamictransport.driver.core.database.TripEntity
import com.dynamictransport.driver.domain.model.IncidentCategory
import com.dynamictransport.driver.domain.model.PendingActionState
import com.dynamictransport.driver.domain.model.PendingActionType
import com.dynamictransport.driver.domain.model.SyncState
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.statemachine.ShiftActionType
import com.dynamictransport.driver.domain.statemachine.ShiftStateMachine
import com.dynamictransport.driver.domain.statemachine.TransitionResult
import com.dynamictransport.driver.domain.statemachine.TripSnapshot
import com.dynamictransport.driver.domain.statemachine.TripStateMachine
import kotlinx.serialization.Serializable
import java.time.Clock
import javax.inject.Inject

const val SHIFT_ENTITY_ID = "current"

@Serializable
data class TripActionPayload(
    val reasonCode: String? = null,
    val note: String? = null,
)

@Serializable
data class IncidentPayload(
    val tripId: String? = null,
    val category: IncidentCategory,
    val note: String? = null,
)

@Serializable
data class DocumentUploadPayload(
    val documentLocalId: String,
)

class PendingActionWriter @Inject constructor(
    private val dao: PendingActionDao,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun enqueue(
        driverId: String,
        entityType: PendingEntityType,
        entityId: String,
        actionType: PendingActionType,
        tripAction: TripActionType? = null,
        payloadJson: String = "{}",
        clientActionId: String = ids.newId(),
    ): PendingActionEntity {
        val now = clock.instant()
        val entity = PendingActionEntity(
            clientActionId = clientActionId,
            driverId = driverId,
            entityType = entityType,
            entityId = entityId,
            actionType = actionType,
            tripAction = tripAction,
            payloadJson = payloadJson,
            createdAt = now,
            retryCount = 0,
            state = PendingActionState.PENDING,
            lastError = null,
            acknowledged = false,
            updatedAt = now,
        )
        return entity.copy(id = dao.insert(entity))
    }
}

internal fun syncStateOf(hasOpenActions: Boolean, hasUnacknowledgedFailure: Boolean): SyncState = when {
    hasUnacknowledgedFailure -> SyncState.FAILED
    hasOpenActions -> SyncState.PENDING
    else -> SyncState.SYNCED
}

/** Recomputes local projections: last server snapshot + replay of open actions through the state machine. */
class Projector @Inject constructor(
    private val tripDao: TripDao,
    private val shiftDao: ShiftDao,
    private val documentDao: DocumentDao,
    private val pendingDao: PendingActionDao,
) {
    suspend fun reproject(entityType: PendingEntityType, entityId: String) {
        when (entityType) {
            PendingEntityType.TRIP -> tripDao.get(entityId)?.let { tripDao.upsert(projectTrip(it)) }
            PendingEntityType.SHIFT -> shiftDao.get()?.let { shiftDao.upsert(projectShift(it)) }
            PendingEntityType.DOCUMENT -> reprojectDocument(entityId)
            PendingEntityType.INCIDENT -> Unit
        }
    }

    suspend fun projectTrip(trip: TripEntity): TripEntity {
        val open = pendingDao.getOpenForEntity(PendingEntityType.TRIP, trip.id)
        var snapshot = TripSnapshot(trip.serverState, trip.serverBreakState)
        open.forEach { action ->
            val type = action.tripAction ?: return@forEach
            // Actions the server already applied (response lost) are illegal here and simply skipped.
            (TripStateMachine.transition(snapshot, type) as? TransitionResult.Applied)?.let { snapshot = it.snapshot }
        }
        val failed = pendingDao.countUnacknowledgedFailures(PendingEntityType.TRIP, trip.id) > 0
        return trip.copy(
            localState = snapshot.state,
            localBreakState = snapshot.breakState,
            syncState = syncStateOf(open.isNotEmpty(), failed),
        )
    }

    suspend fun projectShift(shift: ShiftEntity): ShiftEntity {
        val open = pendingDao.getOpenForEntity(PendingEntityType.SHIFT, SHIFT_ENTITY_ID)
        var status = shift.serverStatus
        var startedAt = shift.serverStartedAt
        var endedAt = shift.serverEndedAt
        open.forEach { action ->
            val type = when (action.actionType) {
                PendingActionType.SHIFT_START -> ShiftActionType.START_SHIFT
                PendingActionType.SHIFT_END -> ShiftActionType.END_SHIFT
                else -> return@forEach
            }
            val next = ShiftStateMachine.apply(status, type) ?: return@forEach
            status = next
            if (type == ShiftActionType.START_SHIFT) {
                startedAt = action.createdAt
                endedAt = null
            } else {
                endedAt = action.createdAt
            }
        }
        val failed = pendingDao.countUnacknowledgedFailures(PendingEntityType.SHIFT, SHIFT_ENTITY_ID) > 0
        return shift.copy(
            localStatus = status,
            localStartedAt = startedAt,
            localEndedAt = endedAt,
            syncState = syncStateOf(open.isNotEmpty(), failed),
        )
    }

    private suspend fun reprojectDocument(localId: String) {
        val latest = pendingDao.getLatestForEntity(PendingEntityType.DOCUMENT, localId) ?: return
        val syncState = when (latest.state) {
            PendingActionState.SYNCED -> SyncState.SYNCED
            PendingActionState.FAILED_PERMANENT -> SyncState.FAILED
            else -> SyncState.PENDING
        }
        documentDao.updateSyncState(localId, syncState)
    }
}
