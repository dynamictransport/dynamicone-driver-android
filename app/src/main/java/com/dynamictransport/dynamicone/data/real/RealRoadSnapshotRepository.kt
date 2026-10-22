package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.AppDirectories
import com.dynamictransport.dynamicone.core.common.IoDispatcher
import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.database.SnapshotRequestDao
import com.dynamictransport.dynamicone.core.database.SnapshotRequestEntity
import com.dynamictransport.dynamicone.core.database.SnapshotRowState
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.network.SnapshotRequestDto
import com.dynamictransport.dynamicone.core.network.SnapshotResultRequestDto
import com.dynamictransport.dynamicone.core.network.SnapshotWireNames
import com.dynamictransport.dynamicone.core.network.requireSuccess
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.core.sync.SnapshotSyncScheduler
import com.dynamictransport.dynamicone.data.media.SnapshotPhotoPreparer
import com.dynamictransport.dynamicone.domain.model.SnapshotCamera
import com.dynamictransport.dynamicone.domain.model.SnapshotDecision
import com.dynamictransport.dynamicone.domain.model.SnapshotFailure
import com.dynamictransport.dynamicone.domain.model.SnapshotOutcome
import com.dynamictransport.dynamicone.domain.model.SnapshotOutcomeState
import com.dynamictransport.dynamicone.domain.model.SnapshotRequest
import com.dynamictransport.dynamicone.domain.model.SnapshotRequestStatus
import com.dynamictransport.dynamicone.domain.repository.RoadSnapshotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote road snapshots against the Driver API (docs/24). Requests are stored per driver; photos stay in the
 * tablet's private files until the server has them (also across restarts and after signing out), and every
 * send is idempotent by request id, so an interrupted upload is simply repeated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RealRoadSnapshotRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
    private val dao: SnapshotRequestDao,
    private val sessionPreferences: SessionPreferences,
    private val scheduler: SnapshotSyncScheduler,
    private val preparer: SnapshotPhotoPreparer,
    private val directories: AppDirectories,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : RoadSnapshotRepository {

    private val photoDirectory: File get() = File(directories.filesDir, PHOTO_DIRECTORY)

    override fun observeLatest(): Flow<SnapshotOutcome?> = sessionPreferences.driverIdChanges
        .flatMapLatest { driverId -> if (driverId == null) flowOf(null) else dao.observeLatest(driverId) }
        .map { row -> row?.toOutcome() }
        .distinctUntilChanged()

    override suspend fun onRequestSignal(tripId: String, requestId: String): SnapshotDecision {
        val driverId = sessionPreferences.driverId() ?: return SnapshotDecision.Ignore
        // A request this tablet already answered is not taken again (a second push, a restart).
        dao.get(requestId)?.let { if (it.state != SnapshotRowState.REQUESTED) return SnapshotDecision.Ignore }
        val request = try {
            SnapshotMapping.request(tracker.call { api.snapshotRequest(tripId, requestId) }) ?: return SnapshotDecision.Ignore
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            // Closed, cancelled, another driver's, or unreachable: the server owns the expiry, nothing is invented.
            if (e is RemoteException.Conflict || e is RemoteException.NotFound) closeStored(requestId)
            return SnapshotDecision.Ignore
        }
        if (request.status != SnapshotRequestStatus.PENDING || request.isExpiredAt(clock.instant())) {
            closeStored(requestId)
            return SnapshotDecision.Ignore
        }
        dao.upsert(
            SnapshotRequestEntity(
                requestId = request.requestId,
                tripId = request.tripId,
                driverId = driverId,
                requestedByDisplayName = request.requestedByDisplayName,
                requestedAt = request.requestedAt,
                expiresAt = request.expiresAt,
                preferredCamera = request.preferredCamera.wire,
                state = SnapshotRowState.REQUESTED,
                photoPath = null,
                capturedAt = null,
                camera = null,
                failureReason = null,
                failedAt = null,
                updatedAt = clock.instant(),
            ),
        )
        return SnapshotDecision.Capture(request)
    }

    override suspend fun pendingRequest(requestId: String): SnapshotRequest? {
        val row = dao.get(requestId) ?: return null
        if (row.state != SnapshotRowState.REQUESTED) return null
        val request = row.toRequest()
        if (request.isExpiredAt(clock.instant())) {
            reportFailure(requestId, SnapshotFailure.EXPIRED)
            return null
        }
        return request
    }

    override suspend fun storePhoto(requestId: String, source: File, capturedAt: Instant, camera: SnapshotCamera): Boolean {
        val row = dao.get(requestId) ?: run {
            withContext(io) { source.delete() }
            return false
        }
        val target = File(photoDirectory, "$requestId.jpg")
        val ready = withContext(io) { preparer.prepare(source, target) }
        if (!ready) {
            reportFailure(requestId, SnapshotFailure.CAMERA_UNAVAILABLE)
            return false
        }
        if (row.state != SnapshotRowState.REQUESTED) {
            // Answered meanwhile (dismissed from the notification, expired): the photo is not needed.
            withContext(io) { target.delete() }
            return false
        }
        dao.markCaptured(requestId, SnapshotRowState.CAPTURED, target.absolutePath, capturedAt, camera.wire, clock.instant())
        scheduler.requestSend()
        return true
    }

    override suspend fun reportFailure(requestId: String, failure: SnapshotFailure) {
        val row = dao.get(requestId) ?: return
        if (row.state != SnapshotRowState.REQUESTED) return
        dao.markFailed(requestId, SnapshotRowState.FAILED_PENDING, failure.wire, clock.instant(), clock.instant())
        scheduler.requestSend()
    }

    override suspend fun sendPending(): Boolean {
        val driverId = sessionPreferences.driverId() ?: return true
        deleteOld()
        for (row in dao.pendingSends(driverId)) {
            val done = when (row.state) {
                SnapshotRowState.CAPTURED, SnapshotRowState.SENDING -> sendPhoto(row)
                SnapshotRowState.FAILED_PENDING -> sendFailure(row)
                else -> true
            }
            if (!done) return false
        }
        return true
    }

    /** False when the upload must be tried again later. */
    private suspend fun sendPhoto(row: SnapshotRequestEntity): Boolean {
        val path = row.photoPath
        val capturedAt = row.capturedAt
        val file = path?.let(::File)
        if (file == null || capturedAt == null || !file.isFile) {
            // The file is gone (storage cleared): the server will time the request out.
            dao.markFailed(row.requestId, SnapshotRowState.FAILED_PENDING, SnapshotFailure.CAMERA_UNAVAILABLE.wire, clock.instant(), clock.instant())
            return sendFailure(dao.get(row.requestId) ?: return true)
        }
        dao.updateState(row.requestId, SnapshotRowState.SENDING, clock.instant())
        try {
            val part = MultipartBody.Part.createFormData(PHOTO_FIELD, "${row.requestId}.jpg", file.asRequestBody(JPEG))
            tracker.call {
                api.uploadSnapshotPhoto(
                    row.tripId,
                    row.requestId,
                    capturedAt.toString().toRequestBody(TEXT_PLAIN),
                    (row.camera ?: SnapshotCamera.BACK.wire).toRequestBody(TEXT_PLAIN),
                    part,
                )
            }
            finishPhoto(row, SnapshotRowState.UPLOADED)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.updateState(row.requestId, SnapshotRowState.CAPTURED, clock.instant()) }
            throw e
        } catch (e: RemoteException) {
            return when {
                // Closed on the server (expired, cancelled, another driver): nothing to show as an error.
                e is RemoteException.Conflict || (e is RemoteException.NotFound && !e.isApiDisabled) -> {
                    finishPhoto(row, SnapshotRowState.CLOSED)
                    true
                }
                // The server refuses this photo for good: the planner learns that no photo comes.
                e is RemoteException.Validation || e is RemoteException.PayloadTooLarge || e is RemoteException.Rejected -> {
                    withContext(io) { file.delete() }
                    dao.clearPhoto(row.requestId, clock.instant())
                    dao.markFailed(row.requestId, SnapshotRowState.FAILED_PENDING, SnapshotFailure.CAMERA_UNAVAILABLE.wire, clock.instant(), clock.instant())
                    sendFailure(dao.get(row.requestId) ?: return true)
                }
                else -> {
                    dao.updateState(row.requestId, SnapshotRowState.CAPTURED, clock.instant())
                    false
                }
            }
        }
    }

    private suspend fun finishPhoto(row: SnapshotRequestEntity, state: SnapshotRowState) {
        row.photoPath?.let { path -> withContext(io) { File(path).delete() } }
        dao.clearPhoto(row.requestId, clock.instant())
        dao.updateState(row.requestId, state, clock.instant())
    }

    /** False when the report must be tried again later. */
    private suspend fun sendFailure(row: SnapshotRequestEntity): Boolean {
        val reason = row.failureReason ?: SnapshotFailure.CAMERA_UNAVAILABLE.wire
        val occurredAt = row.failedAt ?: clock.instant()
        try {
            tracker.call {
                api.reportSnapshotResult(row.tripId, row.requestId, SnapshotResultRequestDto(SnapshotWireNames.STATUS_FAILED, reason, occurredAt.toString()))
                    .requireSuccess()
            }
            dao.updateState(row.requestId, SnapshotRowState.FAILED, clock.instant())
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            val final = e is RemoteException.Validation || e is RemoteException.Conflict || e is RemoteException.Rejected ||
                e is RemoteException.Forbidden || (e is RemoteException.NotFound && !e.isApiDisabled)
            if (final) {
                dao.updateState(row.requestId, SnapshotRowState.FAILED, clock.instant())
                return true
            }
            return false
        }
    }

    /** Rows and files older than a week are not needed by any screen. */
    private suspend fun deleteOld() {
        val old = dao.olderThan(clock.instant().minus(KEEP_FOR))
        if (old.isEmpty()) return
        withContext(io) { old.forEach { row -> row.photoPath?.let { File(it).delete() } } }
        dao.delete(old.map { it.requestId })
    }

    private suspend fun closeStored(requestId: String) {
        val row = dao.get(requestId) ?: return
        if (row.state == SnapshotRowState.REQUESTED) dao.updateState(requestId, SnapshotRowState.CLOSED, clock.instant())
    }

    private fun SnapshotRequestEntity.toRequest() = SnapshotRequest(
        requestId = requestId,
        tripId = tripId,
        status = SnapshotRequestStatus.PENDING,
        requestedAt = requestedAt,
        expiresAt = expiresAt,
        requestedByDisplayName = requestedByDisplayName,
        preferredCamera = SnapshotCamera.fromWire(preferredCamera),
    )

    private fun SnapshotRequestEntity.toOutcome(): SnapshotOutcome? {
        val outcomeState = when (state) {
            SnapshotRowState.REQUESTED -> SnapshotOutcomeState.WAITING_FOR_DRIVER
            SnapshotRowState.CAPTURED, SnapshotRowState.SENDING -> SnapshotOutcomeState.CAPTURED
            SnapshotRowState.UPLOADED -> SnapshotOutcomeState.UPLOADED
            SnapshotRowState.FAILED_PENDING, SnapshotRowState.FAILED -> SnapshotOutcomeState.FAILED
            SnapshotRowState.CLOSED -> return null
        }
        return SnapshotOutcome(
            requestId = requestId,
            tripId = tripId,
            requestedByDisplayName = requestedByDisplayName,
            requestedAt = requestedAt,
            expiresAt = expiresAt,
            state = outcomeState,
            capturedAt = capturedAt,
            failure = failureReason?.let { wire -> SnapshotFailure.entries.firstOrNull { it.wire == wire } },
            updatedAt = updatedAt,
        )
    }

    companion object {
        const val PHOTO_DIRECTORY = "road_snapshots"
        private const val PHOTO_FIELD = "photo"
        private val JPEG = "image/jpeg".toMediaType()
        private val TEXT_PLAIN = "text/plain".toMediaType()
        private val KEEP_FOR: Duration = Duration.ofDays(7)
    }
}

/** Wire → domain for docs/24; an answer without the fields the tablet needs is treated as no request. */
object SnapshotMapping {
    fun request(dto: SnapshotRequestDto): SnapshotRequest? {
        val requestId = dto.requestId?.takeIf { it.isNotBlank() } ?: return null
        val tripId = dto.tripId?.takeIf { it.isNotBlank() } ?: return null
        val status = SnapshotRequestStatus.fromWire(dto.status) ?: return null
        val requestedAt = instant(dto.requestedAt) ?: return null
        val expiresAt = instant(dto.expiresAt) ?: return null
        return SnapshotRequest(
            requestId = requestId,
            tripId = tripId,
            status = status,
            requestedAt = requestedAt,
            expiresAt = expiresAt,
            requestedByDisplayName = dto.requestedByDisplayName?.takeIf { it.isNotBlank() },
            preferredCamera = SnapshotCamera.fromWire(dto.preferredCamera),
        )
    }

    fun instant(text: String?): Instant? = text?.toInstantOrNull()
}
