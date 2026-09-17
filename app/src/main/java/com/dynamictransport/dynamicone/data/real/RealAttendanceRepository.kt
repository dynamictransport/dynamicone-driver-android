package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.DeviceUptime
import com.dynamictransport.dynamicone.core.common.IdGenerator
import com.dynamictransport.dynamicone.core.database.AttendancePunchDao
import com.dynamictransport.dynamicone.core.database.AttendancePunchEntity
import com.dynamictransport.dynamicone.core.network.AttendancePunchRequestDto
import com.dynamictransport.dynamicone.core.network.DriverErrorCodes
import com.dynamictransport.dynamicone.core.network.MobileApi
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.core.sync.AttendanceSyncScheduler
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendanceMonth
import com.dynamictransport.dynamicone.domain.model.AttendanceStatus
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.LocalPunchState
import com.dynamictransport.dynamicone.domain.model.PunchFailure
import com.dynamictransport.dynamicone.domain.model.PunchResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.repository.AttendanceRepository
import com.dynamictransport.dynamicone.domain.rules.AttendanceRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Office attendance against the Mobile API. A scan is written to the phone with a new command id before it is sent;
 * when the server does not take it it stays waiting and is sent again automatically with the same command id (the
 * server is idempotent, docs/15 §5.3). Only 400 and 409 are final answers; a rejected punch is never shown as recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RealAttendanceRepository @Inject constructor(
    private val api: MobileApi,
    private val tracker: ApiActivityTracker,
    private val dao: AttendancePunchDao,
    private val sessionPreferences: SessionPreferences,
    private val scheduler: AttendanceSyncScheduler,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val uptime: DeviceUptime,
) : AttendanceRepository {

    private val status = MutableStateFlow<AttendanceStatus?>(null)
    private val months = MutableStateFlow<Map<YearMonth, AttendanceMonth>>(emptyMap())
    private val sendMutex = Mutex()

    override fun observeStatus(): Flow<AttendanceStatus?> = status

    override fun observeLocalPunches(): Flow<List<LocalPunch>> =
        sessionPreferences.userIdChanges.flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList()) else dao.observe(userId).map { rows -> rows.map(::toLocalPunch) }
        }

    override suspend fun refreshStatus(): RefreshResult = read {
        val response = tracker.call { api.attendanceStatus() }
        status.value = AttendanceMapping.status(response) ?: throw RemoteException.InvalidResponse()
        tracker.markReadSucceeded()
    }

    override suspend fun recordPunch(direction: AttendanceDirection, tagUid: String): PunchResult {
        val userId = sessionPreferences.userId() ?: return PunchResult.NotLoggedIn
        val uid = AttendanceRules.tagUid(tagUid) ?: return PunchResult.UnreadableTag
        val now = clock.instant()
        val punch = AttendancePunchEntity(
            commandId = ids.newId(),
            userId = userId,
            direction = direction,
            tagUid = uid,
            scannedAt = now,
            scannedElapsedMillis = uptime.elapsedRealtimeMillis(),
            bootCount = uptime.bootCount(),
            state = LocalPunchState.PENDING,
            failure = null,
            occurredAt = null,
            pointName = null,
            createdAt = now,
            updatedAt = now,
        )
        // Stored before anything is sent: a lost response or a closed app cannot lose the scan.
        dao.insert(punch)
        return sendMutex.withLock {
            // A background send may have handled it while this call waited for the lock. A recorded punch is sent again
            // to get the server's answer (same command id, same result).
            val current = dao.get(punch.commandId) ?: return@withLock PunchResult.NotLoggedIn
            val failure = current.failure
            if (current.state == LocalPunchState.FAILED && failure != null) PunchResult.Failed(failure) else send(current, scheduleRetry = true)
        }
    }

    override suspend fun sendPending(): Boolean = sendMutex.withLock {
        // Without the account's session nothing can be sent; signing in again starts the next attempt.
        val userId = sessionPreferences.userId() ?: return@withLock true
        for (punch in dao.getUnsent(userId)) {
            // The server is not reachable: stop here, the worker tries again with backoff.
            if (send(punch, scheduleRetry = false) is PunchResult.Saved) return@withLock false
        }
        true
    }

    override suspend fun resend(commandId: String): PunchResult = sendMutex.withLock {
        val punch = dao.get(commandId)
        if (punch == null || punch.userId != sessionPreferences.userId()) return@withLock PunchResult.NotLoggedIn
        send(punch, scheduleRetry = true)
    }

    override suspend fun dismiss(commandId: String) = sendMutex.withLock {
        if (dao.get(commandId)?.state == LocalPunchState.FAILED) dao.delete(commandId)
    }

    override fun observeMonth(month: YearMonth): Flow<AttendanceMonth?> = months.map { it[month] }.distinctUntilChanged()

    override suspend fun refreshMonth(month: YearMonth): RefreshResult = read {
        // A future end date is limited to today by the server (docs/15 §5.5).
        val response = tracker.call { api.attendanceDays(month.atDay(1).toString(), month.atEndOfMonth().toString()) }
        val mapped = AttendanceMapping.month(month, response) ?: throw RemoteException.InvalidResponse()
        months.update { it + (month to mapped) }
        tracker.markReadSucceeded()
    }

    /** Server data held in memory. Punches on the phone are kept (see [keepOnly]). */
    fun clear() {
        status.value = null
        months.value = emptyMap()
    }

    /** Another account signed in on this phone: earlier accounts' punches must never be sent with this session. */
    suspend fun keepOnly(userId: String) = dao.deleteForOtherUsers(userId)

    private suspend fun send(punch: AttendancePunchEntity, scheduleRetry: Boolean): PunchResult {
        dao.updateState(punch.commandId, LocalPunchState.SENDING, null, clock.instant())
        val request = AttendancePunchRequestDto(
            commandId = punch.commandId,
            direction = AttendanceMapping.wire(punch.direction),
            tagUid = punch.tagUid,
            scannedAtUtc = punch.scannedAt.toString(),
            elapsedSinceScanMs = elapsedSinceScan(punch),
            installationId = sessionPreferences.installationId(),
        )
        val response = try {
            tracker.call { api.recordPunch(request) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.updateState(punch.commandId, LocalPunchState.PENDING, null, clock.instant()) }
            throw e
        } catch (e: RemoteException) {
            val failure = AttendanceMapping.failure(e)
            if (failure != null) {
                dao.updateState(punch.commandId, LocalPunchState.FAILED, failure, clock.instant())
                return PunchResult.Failed(failure)
            }
            // Not recorded and not rejected either (403, 404, 429, 5xx, offline): the same command id is sent again.
            return waitForRetry(
                punch = punch,
                scheduleRetry = scheduleRetry,
                hold = AttendanceMapping.hold(e),
                delaySeconds = (e as? RemoteException.RateLimited)?.retryAfterSeconds ?: 0L,
            )
        }
        // A success without the contract body: resending returns the recorded punch (idempotent).
        val recorded = response.punch?.let(AttendanceMapping::punch) ?: return waitForRetry(punch, scheduleRetry)
        dao.markSynced(punch.commandId, recorded.occurredAt, recorded.pointName, clock.instant())
        response.status?.let(AttendanceMapping::status)?.let { status.value = it }
        dao.deleteSyncedBefore(clock.instant().minus(SYNCED_RETENTION))
        return PunchResult.Recorded(recorded)
    }

    private suspend fun waitForRetry(
        punch: AttendancePunchEntity,
        scheduleRetry: Boolean,
        hold: PunchFailure? = null,
        delaySeconds: Long = 0L,
    ): PunchResult {
        dao.updateState(punch.commandId, LocalPunchState.PENDING, hold, clock.instant())
        if (scheduleRetry) scheduler.requestSend(delaySeconds)
        return PunchResult.Saved(punch.commandId, hold)
    }

    /** Monotonic time since the scan; null after a restart, when the boot-relative clocks cannot be compared. */
    private fun elapsedSinceScan(punch: AttendancePunchEntity): Long? {
        val scanBoot = punch.bootCount ?: return null
        if (uptime.bootCount() != scanBoot) return null
        return (uptime.elapsedRealtimeMillis() - punch.scannedElapsedMillis).takeIf { it >= 0 }
    }

    private inline fun read(block: () -> Unit): RefreshResult = try {
        block()
        RefreshResult.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        when {
            // `403 attendance_access_required`: the account may not record office attendance (any more).
            e is RemoteException.Forbidden && e.problem?.code != DriverErrorCodes.PASSWORD_CHANGE_REQUIRED ->
                RefreshResult.ACCESS_REQUIRED
            // The Mobile API is not live on this server yet.
            e is RemoteException.NotFound && !e.isApiDisabled -> RefreshResult.NOT_SUPPORTED
            else -> e.toRefreshResult()
        }
    }

    private fun toLocalPunch(entity: AttendancePunchEntity) = LocalPunch(
        commandId = entity.commandId,
        direction = entity.direction,
        scannedAt = entity.scannedAt,
        state = entity.state,
        failure = entity.failure,
        occurredAt = entity.occurredAt,
        pointName = entity.pointName,
    )

    private companion object {
        /** Recorded punches stay on the phone long enough to know today's state offline. */
        val SYNCED_RETENTION: Duration = Duration.ofDays(3)
    }
}
