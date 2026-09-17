package com.dynamictransport.driver.data.repository

import androidx.room.withTransaction
import com.dynamictransport.driver.core.common.IdGenerator
import com.dynamictransport.driver.core.database.DocumentDao
import com.dynamictransport.driver.core.database.DocumentEntity
import com.dynamictransport.driver.core.database.DriverDatabase
import com.dynamictransport.driver.core.database.PendingActionDao
import com.dynamictransport.driver.core.database.PendingEntityType
import com.dynamictransport.driver.core.database.ShiftDao
import com.dynamictransport.driver.core.database.SyncMetaDao
import com.dynamictransport.driver.core.database.SyncMetaEntity
import com.dynamictransport.driver.core.database.TripDao
import com.dynamictransport.driver.core.security.SessionPreferences
import com.dynamictransport.driver.core.sync.SyncScheduler
import com.dynamictransport.driver.data.local.initialShiftEntity
import com.dynamictransport.driver.data.local.toDemoSlot
import com.dynamictransport.driver.data.local.toDomain
import com.dynamictransport.driver.data.local.toTripDocument
import com.dynamictransport.driver.data.local.toEntity
import com.dynamictransport.driver.data.local.toFailedAction
import com.dynamictransport.driver.data.local.toTripActionRecord
import com.dynamictransport.driver.data.sync.DocumentUploadPayload
import com.dynamictransport.driver.data.sync.IncidentPayload
import com.dynamictransport.driver.data.sync.PendingActionWriter
import com.dynamictransport.driver.data.sync.Projector
import com.dynamictransport.driver.data.sync.SHIFT_ENTITY_ID
import com.dynamictransport.driver.data.sync.SyncEngine
import com.dynamictransport.driver.data.sync.TripActionPayload
import com.dynamictransport.driver.domain.model.ActionRejection
import com.dynamictransport.driver.domain.model.ActionResult
import com.dynamictransport.driver.domain.model.DocumentContentResult
import com.dynamictransport.driver.domain.model.DocumentSlot
import com.dynamictransport.driver.domain.model.DocumentType
import com.dynamictransport.driver.domain.model.IncidentCategory
import com.dynamictransport.driver.domain.model.PendingActionState
import com.dynamictransport.driver.domain.model.PendingActionType
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.model.Shift
import com.dynamictransport.driver.domain.model.SyncOverview
import com.dynamictransport.driver.domain.model.SyncState
import com.dynamictransport.driver.domain.model.Trip
import com.dynamictransport.driver.domain.model.TripActionRecord
import com.dynamictransport.driver.domain.model.TripDocument
import com.dynamictransport.driver.domain.repository.DocumentRepository
import com.dynamictransport.driver.domain.repository.IncidentRepository
import com.dynamictransport.driver.domain.repository.ShiftRepository
import com.dynamictransport.driver.domain.repository.SyncRepository
import com.dynamictransport.driver.domain.repository.TripActionCommand
import com.dynamictransport.driver.domain.repository.TripRepository
import com.dynamictransport.driver.domain.statemachine.ShiftActionType
import com.dynamictransport.driver.domain.statemachine.ShiftStateMachine
import com.dynamictransport.driver.domain.statemachine.TripSnapshot
import com.dynamictransport.driver.domain.statemachine.TripStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

// Every critical driver action follows the same path (CLAUDE.md §4):
// validate against the local projection -> store action + projection atomically -> schedule sync.

@Singleton
class OfflineFirstTripRepository @Inject constructor(
    private val database: DriverDatabase,
    private val tripDao: TripDao,
    private val pendingDao: PendingActionDao,
    private val writer: PendingActionWriter,
    private val projector: Projector,
    private val sessionPreferences: SessionPreferences,
    private val syncScheduler: SyncScheduler,
    private val json: Json,
) : TripRepository {

    override fun observeTrips(): Flow<List<Trip>> = tripDao.observeAll().map { trips -> trips.map { it.toDomain() } }

    override fun observeTrip(tripId: String): Flow<Trip?> = tripDao.observe(tripId).map { it?.toDomain() }

    override fun observeActionHistory(tripId: String): Flow<List<TripActionRecord>> =
        pendingDao.observeTripHistory(tripId).map { actions -> actions.mapNotNull { it.toTripActionRecord() } }

    override fun observeFinishedTripCount(since: Instant): Flow<Int> = pendingDao.observeFinishedTripCount(since)

    // Demo trips are local-first; server pulls run through the sync engine and are not paged.
    override suspend fun refreshTrip(tripId: String): RefreshResult = RefreshResult.SUCCESS

    override suspend fun loadMoreTrips(): RefreshResult = RefreshResult.SUCCESS

    override fun observeCanLoadMore(): Flow<Boolean> = flowOf(false)

    override suspend fun currentTrips(): List<Trip> = tripDao.getAll().map { it.toDomain() }

    override suspend fun submitAction(command: TripActionCommand): ActionResult {
        val driverId = sessionPreferences.driverId() ?: return ActionResult.Rejected(ActionRejection.NOT_LOGGED_IN)
        val result: ActionResult = database.withTransaction {
            val stored = tripDao.get(command.tripId)
                ?: return@withTransaction ActionResult.Rejected(ActionRejection.NOT_FOUND)
            // Checked against a fresh projection (server snapshot + open queue), never a possibly stale stored row.
            val trip = projector.projectTrip(stored).toDomain()
            val allowed = TripStateMachine.allowedActions(
                TripSnapshot(trip.state, trip.breakState),
                trip.serverAllowedActions,
            )
            // A second tap on the same button is illegal for the already-projected state: no duplicate action.
            if (command.action !in allowed) return@withTransaction ActionResult.Rejected(ActionRejection.NOT_ALLOWED)

            writer.enqueue(
                driverId = driverId,
                entityType = PendingEntityType.TRIP,
                entityId = command.tripId,
                actionType = PendingActionType.TRIP_ACTION,
                tripAction = command.action,
                payloadJson = json.encodeToString(
                    TripActionPayload.serializer(),
                    TripActionPayload(reasonCode = command.rejectReason?.name, note = command.note),
                ),
            )
            projector.reproject(PendingEntityType.TRIP, command.tripId)
            ActionResult.Queued
        }
        if (result is ActionResult.Queued) syncScheduler.requestSync()
        return result
    }
}

@Singleton
class OfflineFirstShiftRepository @Inject constructor(
    private val database: DriverDatabase,
    private val shiftDao: ShiftDao,
    private val writer: PendingActionWriter,
    private val projector: Projector,
    private val sessionPreferences: SessionPreferences,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : ShiftRepository {

    override fun observeShift(): Flow<Shift> = shiftDao.observe().map { it?.toDomain() ?: Shift.NotStarted }

    override suspend fun currentShift(): Shift = shiftDao.get()?.toDomain() ?: Shift.NotStarted

    override suspend fun startShift(): ActionResult = submit(ShiftActionType.START_SHIFT)

    override suspend fun endShift(): ActionResult = submit(ShiftActionType.END_SHIFT)

    private suspend fun submit(action: ShiftActionType): ActionResult {
        val driverId = sessionPreferences.driverId() ?: return ActionResult.Rejected(ActionRejection.NOT_LOGGED_IN)
        val result: ActionResult = database.withTransaction {
            val stored = shiftDao.get() ?: initialShiftEntity(clock.instant()).also { shiftDao.upsert(it) }
            val current = projector.projectShift(stored)
            if (!ShiftStateMachine.isLegal(current.localStatus, action)) {
                return@withTransaction ActionResult.Rejected(ActionRejection.NOT_ALLOWED)
            }
            writer.enqueue(
                driverId = driverId,
                entityType = PendingEntityType.SHIFT,
                entityId = SHIFT_ENTITY_ID,
                actionType = if (action == ShiftActionType.START_SHIFT) PendingActionType.SHIFT_START else PendingActionType.SHIFT_END,
            )
            projector.reproject(PendingEntityType.SHIFT, SHIFT_ENTITY_ID)
            ActionResult.Queued
        }
        if (result is ActionResult.Queued) syncScheduler.requestSync()
        return result
    }
}

@Singleton
class OfflineFirstDocumentRepository @Inject constructor(
    private val database: DriverDatabase,
    private val tripDao: TripDao,
    private val documentDao: DocumentDao,
    private val writer: PendingActionWriter,
    private val sessionPreferences: SessionPreferences,
    private val syncScheduler: SyncScheduler,
    private val ids: IdGenerator,
    private val json: Json,
    private val clock: Clock,
) : DocumentRepository {

    override fun observeSlots(tripId: String): Flow<List<DocumentSlot>?> =
        documentDao.observeForTrip(tripId).map { documents ->
            DocumentType.entries.map { type ->
                documents.filter { it.type == type }
                    .maxWithOrNull(compareBy(nullsFirst()) { it.capturedAt })
                    .toDemoSlot(type)
            }
        }

    override fun observeFiles(tripId: String): Flow<List<TripDocument>> =
        documentDao.observeForTrip(tripId).map { documents ->
            documents.sortedWith(compareByDescending(nullsFirst()) { it.capturedAt }).map { it.toTripDocument() }
        }

    override suspend fun refreshDocuments(tripId: String): RefreshResult = RefreshResult.SUCCESS

    override suspend fun openDocument(tripId: String, documentId: String, contentTypeHint: String?): DocumentContentResult {
        val document = documentDao.get(documentId)
        val path = document?.localFilePath?.takeIf { File(it).exists() }
            ?: return DocumentContentResult.Unavailable(ActionRejection.NOT_SUPPORTED)
        return DocumentContentResult.Available(path, document.contentType.ifBlank { contentTypeHint ?: "image/jpeg" })
    }

    override suspend fun upload(tripId: String, slot: DocumentSlot, localFilePath: String): ActionResult =
        addDocument(tripId, slot.type ?: DocumentType.OTHER, localFilePath)

    private suspend fun addDocument(tripId: String, type: DocumentType, localFilePath: String): ActionResult {
        val driverId = sessionPreferences.driverId() ?: return ActionResult.Rejected(ActionRejection.NOT_LOGGED_IN)
        val file = File(localFilePath)
        if (!file.exists() || file.length() == 0L) return ActionResult.Rejected(ActionRejection.FILE_ERROR)

        val result: ActionResult = database.withTransaction {
            val trip = tripDao.get(tripId) ?: return@withTransaction ActionResult.Rejected(ActionRejection.NOT_FOUND)
            val localId = ids.newId()
            val clientActionId = ids.newId()
            documentDao.upsert(
                DocumentEntity(
                    localId = localId,
                    tripId = tripId,
                    type = type,
                    clientActionId = clientActionId,
                    localFilePath = file.absolutePath,
                    fileName = file.name,
                    contentType = contentTypeOf(file),
                    capturedAt = clock.instant(),
                    containerNo = trip.containerNo.takeIf { type == DocumentType.CONTAINER_PHOTO || type == DocumentType.SEAL_PHOTO },
                    sealNo = trip.sealNo.takeIf { type == DocumentType.SEAL_PHOTO },
                    remoteId = null,
                    remoteStatus = null,
                    thumbnailUrl = null,
                    syncState = SyncState.PENDING,
                ),
            )
            // Metadata is queued first; media upload happens in the sync pipeline (CLAUDE.md §6).
            writer.enqueue(
                driverId = driverId,
                entityType = PendingEntityType.DOCUMENT,
                entityId = localId,
                actionType = PendingActionType.DOCUMENT_UPLOAD,
                payloadJson = json.encodeToString(DocumentUploadPayload.serializer(), DocumentUploadPayload(localId)),
                clientActionId = clientActionId,
            )
            ActionResult.Queued
        }
        if (result is ActionResult.Queued) syncScheduler.requestSync()
        return result
    }

    private fun contentTypeOf(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        else -> "application/octet-stream"
    }
}

@Singleton
class OfflineFirstIncidentRepository @Inject constructor(
    private val writer: PendingActionWriter,
    private val sessionPreferences: SessionPreferences,
    private val syncScheduler: SyncScheduler,
    private val ids: IdGenerator,
    private val json: Json,
) : IncidentRepository {

    override suspend fun report(tripId: String?, category: IncidentCategory, note: String?): ActionResult {
        val driverId = sessionPreferences.driverId() ?: return ActionResult.Rejected(ActionRejection.NOT_LOGGED_IN)
        val clientActionId = ids.newId()
        writer.enqueue(
            driverId = driverId,
            entityType = PendingEntityType.INCIDENT,
            entityId = clientActionId,
            actionType = PendingActionType.INCIDENT_REPORT,
            payloadJson = json.encodeToString(IncidentPayload.serializer(), IncidentPayload(tripId, category, note)),
            clientActionId = clientActionId,
        )
        syncScheduler.requestSync()
        return ActionResult.Queued
    }
}

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val database: DriverDatabase,
    private val pendingDao: PendingActionDao,
    private val syncMetaDao: SyncMetaDao,
    private val projector: Projector,
    private val syncScheduler: SyncScheduler,
    private val syncEngine: SyncEngine,
    private val clock: Clock,
) : SyncRepository {

    override fun observeOverview(): Flow<SyncOverview> = combine(
        pendingDao.observeOpenCount(),
        pendingDao.observeUnacknowledgedFailures(),
        syncMetaDao.observe(SyncMetaEntity.LAST_SUCCESSFUL_SYNC),
    ) { openCount, failures, lastSync ->
        SyncOverview(
            pendingCount = openCount,
            failures = failures.map { it.toFailedAction() },
            lastSuccessfulSyncAt = lastSync?.instant,
        )
    }

    override fun requestSync() = syncScheduler.requestSync()

    override suspend fun retryFailed() {
        database.withTransaction {
            pendingDao.getUnacknowledgedFailures().forEach { action ->
                pendingDao.updateState(action.id, PendingActionState.PENDING, action.retryCount, null, clock.instant())
                projector.reproject(action.entityType, action.entityId)
            }
        }
        syncScheduler.requestSync()
    }

    override suspend fun acknowledgeFailure(clientActionId: String) {
        database.withTransaction {
            val action = pendingDao.getByClientActionId(clientActionId) ?: return@withTransaction
            pendingDao.acknowledge(clientActionId)
            projector.reproject(action.entityType, action.entityId)
        }
    }

    override suspend fun refreshFromServer(): RefreshResult = syncEngine.refresh()

    // Local-first: screens always show the stored projection; send failures surface as sync banners.
    override fun observeLastRefresh(): Flow<RefreshResult?> = flowOf(RefreshResult.SUCCESS)
}
