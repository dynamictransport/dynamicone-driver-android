package com.dynamictransport.dynamicone.data.sync

import androidx.room.withTransaction
import com.dynamictransport.dynamicone.core.common.AppLogger
import com.dynamictransport.dynamicone.core.database.DocumentDao
import com.dynamictransport.dynamicone.core.database.DocumentEntity
import com.dynamictransport.dynamicone.core.database.AppDatabase
import com.dynamictransport.dynamicone.core.database.DriverProfileDao
import com.dynamictransport.dynamicone.core.database.PendingActionDao
import com.dynamictransport.dynamicone.core.database.PendingActionEntity
import com.dynamictransport.dynamicone.core.database.PendingEntityType
import com.dynamictransport.dynamicone.core.database.ShiftDao
import com.dynamictransport.dynamicone.core.database.SyncMetaDao
import com.dynamictransport.dynamicone.core.database.SyncMetaEntity
import com.dynamictransport.dynamicone.core.database.TripDao
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.core.sync.SyncRunResult
import com.dynamictransport.dynamicone.core.sync.SyncRunner
import com.dynamictransport.dynamicone.data.local.initialShiftEntity
import com.dynamictransport.dynamicone.data.local.newTripEntity
import com.dynamictransport.dynamicone.data.local.toCsv
import com.dynamictransport.dynamicone.data.local.toEntity
import com.dynamictransport.dynamicone.data.remote.DriverActionRequest
import com.dynamictransport.dynamicone.data.remote.DriverRemoteDataSource
import com.dynamictransport.dynamicone.data.remote.RemoteTripSummary
import com.dynamictransport.dynamicone.data.remote.TripActionRequest
import com.dynamictransport.dynamicone.domain.model.BreakState
import com.dynamictransport.dynamicone.domain.model.DocumentUploadMetadata
import com.dynamictransport.dynamicone.domain.model.IncidentReport
import com.dynamictransport.dynamicone.domain.model.PendingActionState
import com.dynamictransport.dynamicone.domain.model.PendingActionType
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.RemoteDocument
import com.dynamictransport.dynamicone.domain.model.RemoteDocumentStatus
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.SyncErrorCode
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes the offline queue in creation order (FIFO per entity) and reconciles with the server.
 * Server is authoritative: a rejected action becomes a visible failure, never a silent loss.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val database: AppDatabase,
    private val tripDao: TripDao,
    private val shiftDao: ShiftDao,
    private val documentDao: DocumentDao,
    private val driverProfileDao: DriverProfileDao,
    private val pendingDao: PendingActionDao,
    private val syncMetaDao: SyncMetaDao,
    private val remote: DriverRemoteDataSource,
    private val sessionPreferences: SessionPreferences,
    private val projector: Projector,
    private val json: Json,
    private val clock: Clock,
    private val logger: AppLogger,
) : SyncRunner {

    private val mutex = Mutex()

    private sealed interface Outcome {
        data object Success : Outcome
        data class Retry(val code: SyncErrorCode) : Outcome
        data class Deferred(val code: SyncErrorCode) : Outcome
        data class Failed(val code: SyncErrorCode) : Outcome
        data object SessionBlocked : Outcome
    }

    private enum class PushResult { DONE, NEEDS_RETRY, SESSION_BLOCKED }

    override suspend fun sync(): SyncRunResult = mutex.withLock {
        val driverId = sessionPreferences.driverId() ?: return@withLock SyncRunResult.COMPLETED
        val push = pushPending(driverId)
        if (push == PushResult.SESSION_BLOCKED) return@withLock SyncRunResult.RETRY
        val pull = pullLocked()
        if (push == PushResult.DONE && pull == RefreshResult.SUCCESS) markSynced()
        logger.event("sync_finished", mapOf("push" to push.name, "pull" to pull.name))
        if (push == PushResult.NEEDS_RETRY) SyncRunResult.RETRY else SyncRunResult.COMPLETED
    }

    /** Pull-only refresh (pull-to-refresh, screen open). */
    suspend fun refresh(): RefreshResult = mutex.withLock {
        if (sessionPreferences.driverId() == null) return@withLock RefreshResult.SESSION_EXPIRED
        pullLocked().also { if (it == RefreshResult.SUCCESS) markSynced() }
    }

    /** Called on app start: actions interrupted by process death are resent (idempotent server). */
    suspend fun recoverAfterRestart() = mutex.withLock {
        database.withTransaction {
            pendingDao.resetInFlight()
            PendingEntityType.entries.forEach { type ->
                pendingDao.getEntityIdsWithUnsyncedActions(type).forEach { projector.reproject(type, it) }
            }
        }
    }

    private suspend fun markSynced() {
        syncMetaDao.upsert(SyncMetaEntity(SyncMetaEntity.LAST_SUCCESSFUL_SYNC, clock.instant()))
    }

    private suspend fun pushPending(driverId: String): PushResult {
        val attempted = mutableSetOf<Long>()
        val blocked = mutableSetOf<Pair<PendingEntityType, String>>()
        var needsRetry = false

        while (true) {
            val action = pendingDao.getSendable(driverId)
                .firstOrNull { it.id !in attempted && (it.entityType to it.entityId) !in blocked }
                ?: break
            attempted += action.id
            val key = action.entityType to action.entityId
            pendingDao.updateState(action.id, PendingActionState.SENDING, action.retryCount, action.lastError, clock.instant())

            val outcome = dispatch(action)
            // Queue state and projection change in one transaction, so an action the driver records
            // meanwhile can never be overwritten by a projection computed without it.
            database.withTransaction {
                when (outcome) {
                    Outcome.Success -> update(action, PendingActionState.SYNCED, action.retryCount, null)
                    is Outcome.Retry -> update(action, PendingActionState.FAILED_RETRYABLE, action.retryCount + 1, outcome.code)
                    is Outcome.Deferred -> update(action, PendingActionState.FAILED_RETRYABLE, action.retryCount, outcome.code)
                    is Outcome.Failed -> {
                        update(action, PendingActionState.FAILED_PERMANENT, action.retryCount + 1, outcome.code)
                        failDependents(action)
                    }
                    Outcome.SessionBlocked -> update(action, PendingActionState.PENDING, action.retryCount, SyncErrorCode.SESSION_EXPIRED)
                }
                projector.reproject(action.entityType, action.entityId)
            }

            when (outcome) {
                Outcome.Success -> Unit
                is Outcome.Retry -> {
                    blocked += key
                    needsRetry = true
                }
                is Outcome.Deferred -> {
                    blocked += key
                }
                is Outcome.Failed -> {
                    blocked += key
                    refreshEntityAfterFailure(action)
                    logger.event("sync_action_failed", mapOf("type" to action.actionType.name, "code" to outcome.code.name))
                }
                Outcome.SessionBlocked -> return PushResult.SESSION_BLOCKED
            }
        }
        return if (needsRetry) PushResult.NEEDS_RETRY else PushResult.DONE
    }

    private suspend fun update(action: PendingActionEntity, state: PendingActionState, retryCount: Int, code: SyncErrorCode?) {
        pendingDao.updateState(action.id, state, retryCount, code, clock.instant())
    }

    /** Later actions on the same entity depend on the failed one and must not be sent out of order. */
    private suspend fun failDependents(failed: PendingActionEntity) {
        pendingDao.getOpenForEntity(failed.entityType, failed.entityId)
            .filter { it.id > failed.id }
            .forEach { update(it, PendingActionState.FAILED_PERMANENT, it.retryCount, SyncErrorCode.PRECEDING_ACTION_FAILED) }
    }

    private suspend fun refreshEntityAfterFailure(action: PendingActionEntity) {
        try {
            when (action.entityType) {
                PendingEntityType.TRIP -> applyServerTrip(remote.getTrip(action.entityId))
                PendingEntityType.SHIFT -> applyServerShift(remote.getCurrentShift())
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            logger.warn("refresh_after_failure_skipped", e)
        }
    }

    private suspend fun dispatch(action: PendingActionEntity): Outcome = try {
        when (action.actionType) {
            PendingActionType.TRIP_ACTION -> {
                val payload = json.decodeFromString(TripActionPayload.serializer(), action.payloadJson)
                val tripAction = requireNotNull(action.tripAction)
                val trip = remote.submitTripAction(
                    action.entityId,
                    TripActionRequest(action.clientActionId, action.createdAt, tripAction, payload.reasonCode, payload.note),
                )
                applyServerTrip(trip)
                Outcome.Success
            }

            PendingActionType.SHIFT_START -> {
                val shift = remote.startShift(DriverActionRequest(action.clientActionId, action.createdAt))
                applyServerShift(shift ?: remote.getCurrentShift())
                Outcome.Success
            }

            PendingActionType.SHIFT_END -> {
                val shift = remote.endShift(DriverActionRequest(action.clientActionId, action.createdAt))
                applyServerShift(shift ?: remote.getCurrentShift())
                Outcome.Success
            }

            PendingActionType.INCIDENT_REPORT -> {
                val payload = json.decodeFromString(IncidentPayload.serializer(), action.payloadJson)
                remote.createIncident(
                    IncidentReport(action.clientActionId, payload.tripId, payload.category, payload.note, action.createdAt),
                )
                Outcome.Success
            }

            PendingActionType.DOCUMENT_UPLOAD -> uploadDocument(action)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        classify(e)
    } catch (e: SerializationException) {
        logger.warn("sync_payload_invalid", e)
        Outcome.Failed(SyncErrorCode.UNKNOWN)
    } catch (e: IllegalArgumentException) {
        logger.warn("sync_action_invalid", e)
        Outcome.Failed(SyncErrorCode.UNKNOWN)
    }

    private suspend fun uploadDocument(action: PendingActionEntity): Outcome {
        val payload = json.decodeFromString(DocumentUploadPayload.serializer(), action.payloadJson)
        val document = documentDao.get(payload.documentLocalId) ?: return Outcome.Failed(SyncErrorCode.UNKNOWN)
        val file = document.localFilePath?.let(::File)
        if (file == null || !file.exists()) return Outcome.Failed(SyncErrorCode.UNKNOWN)
        val uploaded = remote.uploadDocument(
            DocumentUploadMetadata(
                clientActionId = document.clientActionId,
                tripId = document.tripId,
                type = document.type,
                fileName = document.fileName,
                contentType = document.contentType,
                capturedAt = document.capturedAt ?: action.createdAt,
                containerNo = document.containerNo,
                sealNo = document.sealNo,
            ),
            file,
        )
        documentDao.upsert(
            document.copy(
                remoteId = uploaded?.id ?: document.remoteId,
                remoteStatus = uploaded?.status ?: RemoteDocumentStatus.UPLOADED,
                thumbnailUrl = uploaded?.thumbnailUrl ?: document.thumbnailUrl,
                syncState = SyncState.SYNCED,
            ),
        )
        return Outcome.Success
    }

    private fun classify(e: RemoteException): Outcome = when (e) {
        is RemoteException.Offline -> Outcome.Retry(SyncErrorCode.OFFLINE)
        is RemoteException.ServerUnavailable -> Outcome.Retry(SyncErrorCode.SERVER_UNAVAILABLE)
        // The server may have applied the action; resending with the same clientActionId is safe.
        is RemoteException.InvalidResponse -> Outcome.Retry(SyncErrorCode.SERVER_UNAVAILABLE)
        is RemoteException.Unauthorized -> Outcome.SessionBlocked
        is RemoteException.Conflict -> Outcome.Failed(SyncErrorCode.CONFLICT)
        is RemoteException.Rejected -> Outcome.Failed(SyncErrorCode.REJECTED_BY_SERVER)
        is RemoteException.NotSupported -> Outcome.Deferred(SyncErrorCode.NOT_SUPPORTED_YET)
        is RemoteException.RateLimited, is RemoteException.ServerError -> Outcome.Retry(SyncErrorCode.SERVER_UNAVAILABLE)
        is RemoteException.Forbidden, is RemoteException.NotFound, is RemoteException.Validation,
        is RemoteException.PayloadTooLarge,
        -> Outcome.Failed(SyncErrorCode.REJECTED_BY_SERVER)
    }

    private suspend fun pullLocked(): RefreshResult = try {
        driverProfileDao.upsert(remote.getProfile().toEntity())
        applyServerShift(remote.getCurrentShift())

        val summaries = remote.getTrips()
        val serverIds = summaries.map { it.id }.toSet()
        summaries.forEach { applySummary(it) }
        database.withTransaction {
            tripDao.getAll()
                .filter { it.id !in serverIds && pendingDao.getOpenForEntity(PendingEntityType.TRIP, it.id).isEmpty() }
                .forEach { tripDao.delete(it.id) }
        }

        // Detail (break state + nextAllowedActions) for every trip that can still change.
        tripDao.getAll()
            .filter { !it.serverState.isTerminal && it.serverState != TripState.UNKNOWN }
            .forEach { applyServerTrip(remote.getTrip(it.id)) }

        tripDao.getAll()
            .filter { it.localState == TripState.ACCEPTED || it.localState.isInProgress }
            .forEach { mergeRemoteDocuments(it.id, remote.getDocuments(it.id)) }

        RefreshResult.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        when (e) {
            is RemoteException.Offline -> RefreshResult.OFFLINE
            is RemoteException.Unauthorized -> RefreshResult.SESSION_EXPIRED
            else -> RefreshResult.FAILED
        }
    }

    // Server data is merged read-merge-project-write inside a transaction (see pushPending).
    private suspend fun applySummary(summary: RemoteTripSummary) = database.withTransaction {
        val now = clock.instant()
        val existing = tripDao.get(summary.id)
        val base = existing ?: newTripEntity(summary.id, now)
        val stateChanged = existing == null || existing.serverState != summary.state
        val merged = base.copy(
            tripNo = summary.tripNo,
            pickupName = summary.pickupName,
            deliveryName = summary.deliveryName,
            plannedStartAt = summary.plannedStartAt,
            containerNo = summary.containerNo,
            serverState = summary.state,
            serverBreakState = if (stateChanged && !summary.state.isInProgress) BreakState.NONE else base.serverBreakState,
            serverAllowedActions = if (stateChanged) null else base.serverAllowedActions,
            localState = if (existing == null) summary.state else base.localState,
            updatedAt = now,
        )
        tripDao.upsert(projector.projectTrip(merged))
    }

    private suspend fun applyServerTrip(trip: Trip) = database.withTransaction {
        val now = clock.instant()
        val base = tripDao.get(trip.id) ?: newTripEntity(trip.id, now)
        val merged = base.copy(
            tripNo = trip.tripNo,
            pickupName = trip.pickupName.orEmpty(),
            deliveryName = trip.deliveryName.orEmpty(),
            plannedStartAt = trip.plannedStartAt,
            containerNo = trip.containerNo,
            pickupAddress = trip.pickupAddress,
            deliveryAddress = trip.deliveryAddress,
            vehiclePlate = trip.vehiclePlate,
            trailerPlate = trip.trailerPlate,
            sealNo = trip.sealNo,
            instructions = trip.instructions,
            serverState = trip.state,
            serverBreakState = trip.breakState,
            serverAllowedActions = trip.serverAllowedActions?.toCsv(),
            updatedAt = now,
        )
        tripDao.upsert(projector.projectTrip(merged))
    }

    private suspend fun applyServerShift(shift: Shift) = database.withTransaction {
        val now = clock.instant()
        val base = shiftDao.get() ?: initialShiftEntity(now)
        val merged = base.copy(
            shiftId = shift.id,
            serverStatus = shift.status,
            serverStartedAt = shift.startedAt,
            serverEndedAt = shift.endedAt,
            vehiclePlate = shift.vehiclePlate,
            trailerPlate = shift.trailerPlate,
            updatedAt = now,
        )
        shiftDao.upsert(projector.projectShift(merged))
    }

    private suspend fun mergeRemoteDocuments(tripId: String, remoteDocuments: List<RemoteDocument>) = database.withTransaction {
        val local = documentDao.getForTrip(tripId)
        remoteDocuments.forEach { remoteDocument ->
            val match = local.firstOrNull { it.remoteId == remoteDocument.id }
            if (match != null) {
                documentDao.upsert(
                    match.copy(
                        remoteStatus = remoteDocument.status,
                        thumbnailUrl = remoteDocument.thumbnailUrl ?: match.thumbnailUrl,
                    ),
                )
            } else {
                documentDao.upsert(
                    DocumentEntity(
                        localId = "remote-${remoteDocument.id}",
                        tripId = tripId,
                        type = remoteDocument.type,
                        clientActionId = "remote-${remoteDocument.id}",
                        localFilePath = null,
                        fileName = "",
                        contentType = "",
                        capturedAt = remoteDocument.capturedAt,
                        containerNo = null,
                        sealNo = null,
                        remoteId = remoteDocument.id,
                        remoteStatus = remoteDocument.status,
                        thumbnailUrl = remoteDocument.thumbnailUrl,
                        syncState = SyncState.SYNCED,
                    ),
                )
            }
        }
    }
}
