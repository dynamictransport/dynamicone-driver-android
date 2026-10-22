package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.database.WakeUpCallDao
import com.dynamictransport.dynamicone.core.database.WakeUpCallEntity
import com.dynamictransport.dynamicone.core.database.WakeUpRowState
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.network.WakeUpCallDto
import com.dynamictransport.dynamicone.core.network.WakeUpResultRequestDto
import com.dynamictransport.dynamicone.core.network.WakeUpRingingRequestDto
import com.dynamictransport.dynamicone.core.network.WakeUpWireNames
import com.dynamictransport.dynamicone.core.network.requireSuccess
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.core.sync.WakeUpSyncScheduler
import com.dynamictransport.dynamicone.domain.model.WakeUpCall
import com.dynamictransport.dynamicone.domain.model.WakeUpCallStatus
import com.dynamictransport.dynamicone.domain.model.WakeUpDecision
import com.dynamictransport.dynamicone.domain.model.WakeUpOutcome
import com.dynamictransport.dynamicone.domain.model.WakeUpOutcomeState
import com.dynamictransport.dynamicone.domain.model.WakeUpReason
import com.dynamictransport.dynamicone.domain.repository.WakeUpCallRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wake-up calls against the Driver API (docs/25). A call is stored per driver when its push arrives; the driver's
 * answer (or the ring-out) waits on the device until the server has it, idempotent by `clientActionId`, so an
 * interrupted send is simply repeated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RealWakeUpCallRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
    private val dao: WakeUpCallDao,
    private val sessionPreferences: SessionPreferences,
    private val scheduler: WakeUpSyncScheduler,
    private val clock: Clock,
) : WakeUpCallRepository {

    override fun observeLatest(): Flow<WakeUpOutcome?> = sessionPreferences.driverIdChanges
        .flatMapLatest { driverId -> if (driverId == null) flowOf(null) else dao.observeLatest(driverId) }
        .map { row -> row?.toOutcome() }
        .distinctUntilChanged()

    override suspend fun onRingSignal(callId: String, reason: String?, expiresAt: Instant?): WakeUpDecision {
        val driverId = sessionPreferences.driverId() ?: return WakeUpDecision.Ignore
        // A call this device already answered or silenced is not rung again (a second push, a restart).
        dao.get(callId)?.let { if (it.state != WakeUpRowState.RINGING) return WakeUpDecision.Ignore }
        val now = clock.instant()
        val call = try {
            val read = WakeUpCallMapping.call(tracker.call { api.wakeUpCall(callId) }) ?: return WakeUpDecision.Ignore
            if (!read.status.isOpen) {
                closeStored(callId)
                return WakeUpDecision.Ignore
            }
            read
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            when (e) {
                // The server says no: closed, cancelled, another driver's, or no session.
                is RemoteException.NotFound, is RemoteException.Conflict, is RemoteException.Forbidden, is RemoteException.Unauthorized -> {
                    closeStored(callId)
                    return WakeUpDecision.Ignore
                }
                // The server could not be asked right now: the push is the call (U1). Without a deadline nothing rings.
                else -> {
                    if (expiresAt == null) return WakeUpDecision.Ignore
                    WakeUpCall(callId, WakeUpCallStatus.PENDING, WakeUpReason.fromWire(reason), now, expiresAt, null, null, null)
                }
            }
        }
        if (call.isExpiredAt(now)) {
            closeStored(callId)
            return WakeUpDecision.Ignore
        }
        val stored = dao.get(callId)
        dao.upsert(
            WakeUpCallEntity(
                callId = call.callId,
                driverId = driverId,
                reason = call.reason.wire,
                requestedByDisplayName = call.requestedByDisplayName,
                requestedAt = call.requestedAt,
                expiresAt = call.expiresAt,
                tripId = call.tripId,
                tripNo = call.tripNo,
                state = WakeUpRowState.RINGING,
                acknowledgedAt = null,
                resultAt = null,
                updatedAt = stored?.updatedAt ?: now,
            ),
        )
        return WakeUpDecision.Ring(call)
    }

    override suspend fun onStopSignal(callId: String) = closeStored(callId)

    override suspend fun ringingCalls(): List<WakeUpCall> {
        val driverId = sessionPreferences.driverId() ?: return emptyList()
        return dao.ringing(driverId).map { it.toCall() }
    }

    override suspend fun reportRinging(callId: String) {
        val occurredAt = notBeforeRequest(dao.get(callId), clock.instant())
        try {
            tracker.call {
                api.reportWakeUpRinging(callId, WakeUpRingingRequestDto(occurredAt.toString(), sessionPreferences.installationId()))
                    .requireSuccess()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: RemoteException) {
            // Best effort (docs/25 §6): the cockpit shows "pending" a little longer, nothing else changes.
        }
    }

    override suspend fun acknowledge(callId: String) {
        val row = dao.get(callId) ?: return
        if (row.state != WakeUpRowState.RINGING) return
        val now = clock.instant()
        dao.markResult(callId, WakeUpRowState.ACKNOWLEDGED_PENDING, acknowledgedAt = now, resultAt = now, updatedAt = now)
        scheduler.requestSend()
    }

    override suspend fun expire(callId: String) {
        val row = dao.get(callId) ?: return
        if (row.state != WakeUpRowState.RINGING) return
        val now = clock.instant()
        dao.markResult(callId, WakeUpRowState.EXPIRED_PENDING, acknowledgedAt = null, resultAt = now, updatedAt = now)
        scheduler.requestSend()
    }

    override suspend fun sendPending(): Boolean {
        val driverId = sessionPreferences.driverId() ?: return true
        deleteOld()
        for (row in dao.pendingSends(driverId)) {
            if (!sendResult(row)) return false
        }
        return true
    }

    /** False when the report must be tried again later. */
    private suspend fun sendResult(row: WakeUpCallEntity): Boolean {
        val acknowledged = row.state == WakeUpRowState.ACKNOWLEDGED_PENDING
        val status = if (acknowledged) WakeUpWireNames.STATUS_ACKNOWLEDGED else WakeUpWireNames.STATUS_EXPIRED
        val done = if (acknowledged) WakeUpRowState.ACKNOWLEDGED else WakeUpRowState.EXPIRED
        val occurredAt = notBeforeRequest(row, row.resultAt ?: clock.instant())
        val body = WakeUpResultRequestDto(
            status = status,
            occurredAt = occurredAt.toString(),
            installationId = sessionPreferences.installationId(),
            clientActionId = clientActionId(row.callId, acknowledged),
        )
        try {
            tracker.call { api.reportWakeUpResult(row.callId, body).requireSuccess() }
            dao.updateState(row.callId, done, clock.instant())
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            // A definite answer (the call is gone, or the server refuses this report for good) ends the retries;
            // the driver's answer is kept on the device as given.
            val final = e is RemoteException.Validation || e is RemoteException.Conflict || e is RemoteException.Rejected ||
                e is RemoteException.Forbidden || (e is RemoteException.NotFound && !e.isApiDisabled)
            if (final) {
                dao.updateState(row.callId, done, clock.instant())
                return true
            }
            return false
        }
    }

    /** Rows older than a week are not needed by any screen. */
    private suspend fun deleteOld() {
        val old = dao.olderThan(clock.instant().minus(KEEP_FOR))
        if (old.isNotEmpty()) dao.delete(old.map { it.callId })
    }

    /**
     * The server refuses a time before its own `requestedAt` (docs/25 §7); a device clock a few seconds behind the
     * server must not lose the driver's answer for good, so the time never falls below the request's.
     */
    private fun notBeforeRequest(row: WakeUpCallEntity?, time: Instant): Instant =
        if (row != null && time.isBefore(row.requestedAt)) row.requestedAt else time

    private suspend fun closeStored(callId: String) {
        val row = dao.get(callId) ?: return
        if (row.state == WakeUpRowState.RINGING) dao.updateState(callId, WakeUpRowState.STOPPED, clock.instant())
    }

    private fun WakeUpCallEntity.toCall() = WakeUpCall(
        callId = callId,
        status = WakeUpCallStatus.RINGING,
        reason = WakeUpReason.fromWire(reason),
        requestedAt = requestedAt,
        expiresAt = expiresAt,
        requestedByDisplayName = requestedByDisplayName,
        tripId = tripId,
        tripNo = tripNo,
    )

    private fun WakeUpCallEntity.toOutcome() = WakeUpOutcome(
        callId = callId,
        reason = WakeUpReason.fromWire(reason),
        requestedByDisplayName = requestedByDisplayName,
        requestedAt = requestedAt,
        expiresAt = expiresAt,
        state = when (state) {
            WakeUpRowState.RINGING -> WakeUpOutcomeState.RINGING
            WakeUpRowState.ACKNOWLEDGED_PENDING -> WakeUpOutcomeState.ACKNOWLEDGED_PENDING
            WakeUpRowState.ACKNOWLEDGED -> WakeUpOutcomeState.ACKNOWLEDGED
            WakeUpRowState.EXPIRED_PENDING, WakeUpRowState.EXPIRED -> WakeUpOutcomeState.NO_ANSWER
            WakeUpRowState.STOPPED -> WakeUpOutcomeState.STOPPED
        },
        acknowledgedAt = acknowledgedAt,
        updatedAt = updatedAt,
    )

    companion object {
        /** `clientActionId` of a result (docs/25 §7): the same call never carries two different answers. */
        fun clientActionId(callId: String, acknowledged: Boolean): String = "$callId-${if (acknowledged) "ack" else "expired"}"

        private val KEEP_FOR: Duration = Duration.ofDays(7)
    }
}

/** Wire → domain for docs/25; an answer without the fields the device needs is treated as no call. */
object WakeUpCallMapping {
    fun call(dto: WakeUpCallDto): WakeUpCall? {
        val callId = dto.callId?.takeIf { it.isNotBlank() } ?: return null
        val status = WakeUpCallStatus.fromWire(dto.status) ?: return null
        val requestedAt = dto.requestedAt?.toInstantOrNull() ?: return null
        val expiresAt = dto.expiresAt?.toInstantOrNull() ?: return null
        return WakeUpCall(
            callId = callId,
            status = status,
            reason = WakeUpReason.fromWire(dto.reason),
            requestedAt = requestedAt,
            expiresAt = expiresAt,
            requestedByDisplayName = dto.requestedByDisplayName?.takeIf { it.isNotBlank() },
            tripId = dto.tripId?.takeIf { it.isNotBlank() },
            tripNo = dto.tripNo?.takeIf { it.isNotBlank() },
        )
    }
}
