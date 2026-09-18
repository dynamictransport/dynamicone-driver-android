package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.AppDirectories
import com.dynamictransport.dynamicone.core.common.DeviceUptime
import com.dynamictransport.dynamicone.core.common.IdGenerator
import com.dynamictransport.dynamicone.core.common.IoDispatcher
import com.dynamictransport.dynamicone.core.database.CheckPhotoDao
import com.dynamictransport.dynamicone.core.database.CheckPhotoEntity
import com.dynamictransport.dynamicone.core.database.DriverCacheEntity
import com.dynamictransport.dynamicone.core.database.ShiftCommandDao
import com.dynamictransport.dynamicone.core.database.ShiftCommandEntity
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.network.ShiftEndRequestDto
import com.dynamictransport.dynamicone.core.network.ShiftStartRequestDto
import com.dynamictransport.dynamicone.core.network.ShiftStateResponseDto
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.core.sync.DriverDayWorkScheduler
import com.dynamictransport.dynamicone.core.sync.ShiftSyncScheduler
import com.dynamictransport.dynamicone.data.media.FileCheckPhotoStore
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.LocalShiftCommand
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftCommandFailure
import com.dynamictransport.dynamicone.domain.model.ShiftCommandResult
import com.dynamictransport.dynamicone.domain.model.ShiftCommandState
import com.dynamictransport.dynamicone.domain.model.ShiftCommandType
import com.dynamictransport.dynamicone.domain.model.ShiftStartDraft
import com.dynamictransport.dynamicone.domain.model.VehicleIdentification
import com.dynamictransport.dynamicone.domain.model.VehicleInfo
import com.dynamictransport.dynamicone.domain.repository.DailyShiftRepository
import com.dynamictransport.dynamicone.domain.repository.DriverTimesRepository
import com.dynamictransport.dynamicone.domain.repository.ShiftRepository
import com.dynamictransport.dynamicone.domain.rules.DailyShiftRules
import com.dynamictransport.dynamicone.domain.rules.VehicleCheckRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The driver's daily shift against the Driver API (docs/16_DRIVER_SHIFT_REST_AND_VEHICLE_CHECK.md). A start or end is
 * written to the phone with its command id before it is sent; when the server does not take it, it stays waiting and is
 * sent again automatically with the same command id and payload (the server is idempotent, §2). Only 400 and 409 are
 * final answers. Defect photos follow once the start is recorded. A rejected command is never shown as recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ApiShiftRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
    private val commandDao: ShiftCommandDao,
    private val photoDao: CheckPhotoDao,
    store: DriverCacheStore,
    private val sessionPreferences: SessionPreferences,
    private val scheduler: ShiftSyncScheduler,
    private val dayWork: DriverDayWorkScheduler,
    private val driverTimes: DriverTimesRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val uptime: DeviceUptime,
    private val json: Json,
    directories: AppDirectories,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ShiftRepository, DailyShiftRepository {

    override val requiresVehicleCheck: Boolean = true

    private val server = DriverScopedCache(store, DriverCacheEntity.SHIFT, ShiftStateResponseDto.serializer())
    private val sendMutex = Mutex()

    /** The server answered that it has no shift endpoints (docs/16 not deployed): shown as not connected, not startable. */
    private val notServed = MutableStateFlow(false)
    private val preparer by lazy { DocumentUploadPreparer(File(directories.cacheDir, UPLOAD_DIRECTORY)) }
    private val photoDirectory = File(directories.filesDir, FileCheckPhotoStore.DIRECTORY)

    override fun observeShift(): Flow<Shift> =
        sessionPreferences.driverIdChanges.flatMapLatest { driverId ->
            if (driverId == null) {
                flowOf(NO_SHIFT)
            } else {
                combine(serverState(driverId), commands(driverId), driverTimes.observeDriverTimes(), notServed) { state, commands, times, off ->
                    if (off) Shift.Unavailable else DailyShiftRules.resolve(state?.let(DriverShiftMapping::shiftState), commands, times)
                }
            }
        }.distinctUntilChanged()

    override suspend fun currentShift(): Shift {
        if (notServed.value) return Shift.Unavailable
        val driverId = sessionPreferences.driverId() ?: return NO_SHIFT
        val state = server.load(driverId)?.let(DriverShiftMapping::shiftState)
        val commands = commandDao.getAll(driverId).map { toLocalCommand(it, emptyList()) }
        return DailyShiftRules.resolve(state, commands, driverTimes.current())
    }

    /** A daily shift starts at the vehicle ([startShift] with the check), never with a single tap. */
    override suspend fun startShift(): ActionResult = ActionResult.Rejected(ActionRejection.NOT_ALLOWED)

    override suspend fun endShift(): ActionResult {
        val driverId = sessionPreferences.driverId() ?: return ActionResult.Rejected(ActionRejection.NOT_LOGGED_IN)
        val result = sendMutex.withLock {
            val shift = currentShift()
            if (!shift.isActive) return ActionResult.Rejected(ActionRejection.NOT_ALLOWED)
            val now = clock.instant()
            val request = ShiftEndRequestDto(
                commandId = ids.newId(),
                shiftId = shift.id,
                startCommandId = shift.startCommandId,
                endedAtUtc = now.toString(),
                installationId = sessionPreferences.installationId(),
            )
            val command = ShiftCommandEntity(
                commandId = request.commandId,
                driverId = driverId,
                type = ShiftCommandType.END,
                payloadJson = json.encodeToString(ShiftEndRequestDto.serializer(), request),
                occurredAt = now,
                occurredElapsedMillis = uptime.elapsedRealtimeMillis(),
                bootCount = uptime.bootCount(),
                state = ShiftCommandState.PENDING,
                failure = null,
                vehiclePlate = shift.vehiclePlate,
                identifiedBy = null,
                defectCount = 0,
                startCommandId = shift.startCommandId,
                checkId = null,
                createdAt = now,
                updatedAt = now,
            )
            // Stored before anything is sent: a lost response or a closed app cannot lose the end.
            commandDao.insert(command)
            // The rest shows in the tachograph summary a few minutes later: read it then to set the alarm.
            dayWork.refreshAfter(REST_READ_DELAY_MINUTES)
            sendAllLocked(driverId, scheduleRetry = true)
            resultOf(command.commandId)
        }
        return when (result) {
            ShiftCommandResult.Recorded, is ShiftCommandResult.Saved -> ActionResult.Queued
            is ShiftCommandResult.Failed -> ActionResult.Rejected(result.failure.toRejection())
            is ShiftCommandResult.NotAllowed -> ActionResult.Rejected(result.reason)
            ShiftCommandResult.NotLoggedIn -> ActionResult.Rejected(ActionRejection.NOT_LOGGED_IN)
        }
    }

    override fun observePlannedVehicle(): Flow<VehicleInfo?> =
        sessionPreferences.driverIdChanges.flatMapLatest { driverId ->
            if (driverId == null) flowOf(null) else serverState(driverId).map { state -> state?.plannedVehicle?.let(DriverShiftMapping::plannedVehicle) }
        }.distinctUntilChanged()

    override fun observeCommands(): Flow<List<LocalShiftCommand>> =
        sessionPreferences.driverIdChanges.flatMapLatest { driverId -> if (driverId == null) flowOf(emptyList()) else commands(driverId) }

    override suspend fun refresh(): RefreshResult {
        val driverId = sessionPreferences.driverId() ?: return RefreshResult.SESSION_EXPIRED
        val result = driverRead(tracker) {
            val response = tracker.call { api.shift() }
            server.put(driverId, response)
        }
        when (result) {
            RefreshResult.SUCCESS -> notServed.value = false
            RefreshResult.NOT_SUPPORTED -> notServed.value = true
            else -> Unit
        }
        return result
    }

    override suspend fun startShift(draft: ShiftStartDraft): ShiftCommandResult {
        val driverId = sessionPreferences.driverId() ?: return ShiftCommandResult.NotLoggedIn
        VehicleCheckRules.validate(draft)?.let { return ShiftCommandResult.NotAllowed(it) }
        return sendMutex.withLock {
            val existing = commandDao.get(draft.commandId)
            when {
                // The same confirmation again (double tap, restored screen): never a second start.
                existing != null && existing.driverId != driverId -> return@withLock ShiftCommandResult.NotLoggedIn
                existing == null -> {
                    if (currentShift().isActive) return@withLock ShiftCommandResult.NotAllowed(ActionRejection.NOT_ALLOWED)
                    store(driverId, draft)
                }
            }
            sendAllLocked(driverId, scheduleRetry = true)
            resultOf(draft.commandId)
        }
    }

    override suspend fun sendPending(): Boolean = sendMutex.withLock {
        // Without the driver's session nothing can be sent; signing in again starts the next attempt.
        val driverId = sessionPreferences.driverId() ?: return@withLock true
        sendAllLocked(driverId, scheduleRetry = false)
    }

    override suspend fun resend(commandId: String): ShiftCommandResult = sendMutex.withLock {
        val driverId = sessionPreferences.driverId()
        val command = commandDao.get(commandId)
        if (command == null || command.driverId != driverId) return@withLock ShiftCommandResult.NotLoggedIn
        if (command.state == ShiftCommandState.FAILED) {
            commandDao.updateState(commandId, ShiftCommandState.PENDING, null, clock.instant())
        }
        sendAllLocked(driverId, scheduleRetry = true)
        resultOf(commandId)
    }

    override suspend fun discard(commandId: String) = sendMutex.withLock {
        val command = commandDao.get(commandId) ?: return@withLock
        if (command.state != ShiftCommandState.FAILED) return@withLock
        photoDao.forCommand(commandId).forEach { deletePhotoFile(it.filePath) }
        photoDao.deleteForCommand(commandId)
        commandDao.delete(commandId)
    }

    /** Server data held in memory. Commands on the phone are kept (see [keepOnly]). */
    fun clear() = server.clearMemory()

    /** Another driver signed in on this phone: earlier drivers' commands must never be sent with this session. */
    suspend fun keepOnly(driverId: String) = sendMutex.withLock {
        commandDao.deleteForOtherDrivers(driverId)
        deleteOrphanPhotos()
    }

    private fun serverState(driverId: String): Flow<ShiftStateResponseDto?> =
        server.values.onStart { server.load(driverId) }.map { it[driverId] }.distinctUntilChanged()

    private fun commands(driverId: String): Flow<List<LocalShiftCommand>> =
        combine(commandDao.observe(driverId), photoDao.observeAll()) { commands, photos ->
            val byCommand = photos.groupBy { it.commandId }
            commands.map { toLocalCommand(it, byCommand[it.commandId].orEmpty()) }
        }

    private suspend fun store(driverId: String, draft: ShiftStartDraft) {
        val now = clock.instant()
        val request = DriverShiftMapping.startRequest(draft, startedAt = now, installationId = sessionPreferences.installationId())
        // Photos first: a start is never stored without the photos it names (an interrupted store leaves only photos,
        // which are cleaned up as orphans).
        photoDao.insertAll(
            draft.photos.map { CheckPhotoEntity(it.photoId, draft.commandId, it.filePath, ShiftCommandState.PENDING, now) },
        )
        commandDao.insert(
            ShiftCommandEntity(
                commandId = draft.commandId,
                driverId = driverId,
                type = ShiftCommandType.START,
                payloadJson = json.encodeToString(ShiftStartRequestDto.serializer(), request),
                occurredAt = now,
                occurredElapsedMillis = uptime.elapsedRealtimeMillis(),
                bootCount = uptime.bootCount(),
                state = ShiftCommandState.PENDING,
                failure = null,
                vehiclePlate = (draft.vehicle ?: draft.manualVehicle)?.registrationNumber,
                identifiedBy = if (draft.tagUid != null) VehicleIdentification.NFC else VehicleIdentification.MANUAL,
                defectCount = draft.defectCount,
                startCommandId = null,
                checkId = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Sends the driver's waiting commands oldest first (an end never overtakes its start), then the photos of recorded
     * starts. Stops at the first command the server could not take; true when nothing is left to send.
     */
    private suspend fun sendAllLocked(driverId: String, scheduleRetry: Boolean): Boolean {
        for (command in commandDao.getUnsent(driverId)) {
            val result = send(command)
            if (result is Attempt.Waiting) {
                if (scheduleRetry) scheduler.requestSend(result.delaySeconds)
                return false
            }
        }
        val photosSent = sendPhotos(driverId)
        if (!photosSent && scheduleRetry) scheduler.requestSend()
        cleanUp()
        return photosSent
    }

    private sealed interface Attempt {
        data object Done : Attempt

        data class Waiting(val delaySeconds: Long) : Attempt
    }

    private suspend fun send(command: ShiftCommandEntity): Attempt {
        commandDao.updateState(command.commandId, ShiftCommandState.SENDING, command.failure, clock.instant())
        try {
            when (command.type) {
                ShiftCommandType.START -> {
                    val request = json.decodeFromString(ShiftStartRequestDto.serializer(), command.payloadJson)
                        .copy(elapsedSinceStartMs = elapsedSince(command))
                    val response = tracker.call { api.startShift(request) }
                    // A success without the contract body: resending returns the recorded start (idempotent).
                    val shift = response.shift ?: return waitForRetry(command, hold = null)
                    commandDao.markSynced(command.commandId, response.vehicleCheck?.id?.takeIf { it.isNotBlank() }, clock.instant())
                    updateServer(command.driverId) { it.copy(active = shift) }
                }
                ShiftCommandType.END -> {
                    val request = json.decodeFromString(ShiftEndRequestDto.serializer(), command.payloadJson)
                        .copy(elapsedSinceEndMs = elapsedSince(command))
                    val response = tracker.call { api.endShift(request) }
                    val shift = response.shift ?: return waitForRetry(command, hold = null)
                    commandDao.markSynced(command.commandId, null, clock.instant())
                    updateServer(command.driverId) { it.copy(active = null, lastEnded = shift) }
                }
            }
            return Attempt.Done
        } catch (e: CancellationException) {
            withContext(NonCancellable) { commandDao.updateState(command.commandId, ShiftCommandState.PENDING, command.failure, clock.instant()) }
            throw e
        } catch (e: RemoteException) {
            val failure = DriverShiftMapping.failure(e)
            if (failure != null) {
                commandDao.updateState(command.commandId, ShiftCommandState.FAILED, failure, clock.instant())
                // The server knows a different shift state than the phone: show it.
                if (failure == ShiftCommandFailure.ALREADY_ACTIVE || failure == ShiftCommandFailure.NOT_ACTIVE) refresh()
                return Attempt.Done
            }
            // Not recorded and not rejected either (403, 404, 429, 5xx, offline): the same command id is sent again.
            return waitForRetry(command, DriverShiftMapping.hold(e), (e as? RemoteException.RateLimited)?.retryAfterSeconds ?: 0L)
        }
    }

    private suspend fun waitForRetry(command: ShiftCommandEntity, hold: ShiftCommandFailure?, delaySeconds: Long = 0L): Attempt {
        commandDao.updateState(command.commandId, ShiftCommandState.PENDING, hold, clock.instant())
        return Attempt.Waiting(delaySeconds)
    }

    /** Photos of recorded starts; a final rejection is not retried (the office sees the missing photo). */
    private suspend fun sendPhotos(driverId: String): Boolean {
        for (command in commandDao.getAll(driverId).filter { it.type == ShiftCommandType.START && it.state == ShiftCommandState.SYNCED }) {
            val checkId = command.checkId ?: continue
            for (photo in photoDao.forCommand(command.commandId)) {
                if (photo.state == ShiftCommandState.SYNCED || photo.state == ShiftCommandState.FAILED) continue
                if (!sendPhoto(checkId, photo)) return false
            }
        }
        return true
    }

    /** False when the photo must be tried again later. */
    private suspend fun sendPhoto(checkId: String, photo: CheckPhotoEntity): Boolean {
        val source = File(photo.filePath)
        val prepared = withContext(io) { preparer.prepare(source) }
        if (prepared !is PreparedUpload.Ready) {
            finishPhoto(photo, ShiftCommandState.FAILED)
            return true
        }
        try {
            val part = MultipartBody.Part.createFormData(PHOTO_FIELD, prepared.fileName, prepared.file.asRequestBody(prepared.contentType.toMediaType()))
            photoDao.updateState(photo.photoId, ShiftCommandState.SENDING, clock.instant())
            tracker.call { api.uploadCheckPhoto(checkId, photo.photoId.toRequestBody(TEXT_PLAIN), part) }
            finishPhoto(photo, ShiftCommandState.SYNCED)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { photoDao.updateState(photo.photoId, ShiftCommandState.PENDING, clock.instant()) }
            throw e
        } catch (e: RemoteException) {
            val final = e is RemoteException.Validation || e is RemoteException.PayloadTooLarge ||
                e is RemoteException.Rejected || e is RemoteException.Conflict ||
                (e is RemoteException.NotFound && !e.isApiDisabled)
            if (final) {
                finishPhoto(photo, ShiftCommandState.FAILED)
                return true
            }
            photoDao.updateState(photo.photoId, ShiftCommandState.PENDING, clock.instant())
            return false
        } finally {
            if (prepared.temporary) withContext(NonCancellable + io) { prepared.file.delete() }
        }
    }

    /** Sent or finally rejected: the photo is not needed on the phone any more. */
    private suspend fun finishPhoto(photo: CheckPhotoEntity, state: ShiftCommandState) {
        photoDao.updateState(photo.photoId, state, clock.instant())
        deletePhotoFile(photo.filePath)
    }

    private suspend fun updateServer(driverId: String, transform: (ShiftStateResponseDto) -> ShiftStateResponseDto) {
        val current = server.load(driverId) ?: ShiftStateResponseDto()
        server.put(driverId, transform(current))
    }

    private suspend fun resultOf(commandId: String): ShiftCommandResult {
        val command = commandDao.get(commandId) ?: return ShiftCommandResult.NotLoggedIn
        val failure = command.failure
        return when (command.state) {
            ShiftCommandState.SYNCED -> ShiftCommandResult.Recorded
            ShiftCommandState.FAILED -> ShiftCommandResult.Failed(failure ?: ShiftCommandFailure.REJECTED)
            ShiftCommandState.PENDING, ShiftCommandState.SENDING -> ShiftCommandResult.Saved(failure)
        }
    }

    private suspend fun cleanUp() {
        commandDao.deleteSyncedBefore(clock.instant().minus(SYNCED_RETENTION))
        deleteOrphanPhotos()
        // Photos of checks that were abandoned before sending belong to no command.
        val referenced = photoDao.allFilePaths().toSet()
        val cutoff = clock.instant().minus(ABANDONED_PHOTO_AGE).toEpochMilli()
        withContext(io) {
            photoDirectory.listFiles()
                ?.filter { it.isFile && it.absolutePath !in referenced && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    private suspend fun deleteOrphanPhotos() {
        photoDao.getOrphans().forEach { photo ->
            deletePhotoFile(photo.filePath)
            photoDao.delete(photo.photoId)
        }
    }

    private suspend fun deletePhotoFile(path: String) = withContext(NonCancellable + io) {
        val file = File(path)
        if (file.parentFile?.name == FileCheckPhotoStore.DIRECTORY) file.delete()
    }

    /** Monotonic time since the start or end; null after a restart, when the boot-relative clocks cannot be compared. */
    private fun elapsedSince(command: ShiftCommandEntity): Long? {
        val boot = command.bootCount ?: return null
        if (uptime.bootCount() != boot) return null
        return (uptime.elapsedRealtimeMillis() - command.occurredElapsedMillis).takeIf { it >= 0 }
    }

    private fun toLocalCommand(entity: ShiftCommandEntity, photos: List<CheckPhotoEntity>) = LocalShiftCommand(
        commandId = entity.commandId,
        type = entity.type,
        occurredAt = entity.occurredAt,
        state = entity.state,
        failure = entity.failure,
        vehiclePlate = entity.vehiclePlate,
        identifiedBy = entity.identifiedBy,
        defectCount = entity.defectCount,
        photosWaiting = photos.count { it.state == ShiftCommandState.PENDING || it.state == ShiftCommandState.SENDING },
        photosFailed = photos.count { it.state == ShiftCommandState.FAILED },
        startCommandId = entity.startCommandId,
    )

    private companion object {
        val NO_SHIFT = Shift.NotStarted.copy(requiresVehicleCheck = true)

        /** Recorded commands stay on the phone for a few days so the shift is known offline. */
        val SYNCED_RETENTION: Duration = Duration.ofDays(3)

        /** A check takes minutes; a photo this old without a stored start was abandoned. */
        val ABANDONED_PHOTO_AGE: Duration = Duration.ofDays(2)
        const val UPLOAD_DIRECTORY = "check_upload"

        /** Webfleet synchronises the driver times every five minutes; the rest is visible after a few rounds. */
        const val REST_READ_DELAY_MINUTES = 15L
        const val PHOTO_FIELD = "photo"
        val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
    }
}

internal fun ShiftCommandFailure.toRejection(): ActionRejection = when (this) {
    ShiftCommandFailure.NOT_ACTIVE, ShiftCommandFailure.ALREADY_ACTIVE, ShiftCommandFailure.CONFLICT -> ActionRejection.CONFLICT
    ShiftCommandFailure.ACCESS_REQUIRED -> ActionRejection.FORBIDDEN
    else -> ActionRejection.FAILED
}
