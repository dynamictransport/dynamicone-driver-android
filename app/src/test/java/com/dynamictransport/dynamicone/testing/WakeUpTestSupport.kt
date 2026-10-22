package com.dynamictransport.dynamicone.testing

import com.dynamictransport.dynamicone.core.database.WakeUpCallDao
import com.dynamictransport.dynamicone.core.database.WakeUpCallEntity
import com.dynamictransport.dynamicone.core.database.WakeUpRowState
import com.dynamictransport.dynamicone.core.push.WakeUpCallNotifier
import com.dynamictransport.dynamicone.core.sync.WakeUpSyncScheduler
import com.dynamictransport.dynamicone.core.wakeup.AlarmVolumeBooster
import com.dynamictransport.dynamicone.core.wakeup.WakeUpCallPermissions
import com.dynamictransport.dynamicone.core.wakeup.WakeUpPermissionIssue
import com.dynamictransport.dynamicone.domain.model.WakeUpCall
import com.dynamictransport.dynamicone.domain.model.WakeUpCallStatus
import com.dynamictransport.dynamicone.domain.model.WakeUpDecision
import com.dynamictransport.dynamicone.domain.model.WakeUpOutcome
import com.dynamictransport.dynamicone.domain.model.WakeUpReason
import com.dynamictransport.dynamicone.domain.repository.WakeUpCallRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

fun wakeUpCall(
    callId: String = "call-1",
    requestedAt: Instant = Instant.parse("2026-10-22T05:05:00Z"),
    expiresAt: Instant = Instant.parse("2026-10-22T05:10:00Z"),
    requestedBy: String? = "Ayşe Yılmaz",
    reason: WakeUpReason = WakeUpReason.CALL_OFFICE,
    tripNo: String? = null,
) = WakeUpCall(callId, WakeUpCallStatus.PENDING, reason, requestedAt, expiresAt, requestedBy, tripNo?.let { "trp-1" }, tripNo)

/** Same queries as the Room DAO, for JVM tests without a database. WakeUpCallDaoTest covers the SQL. */
class InMemoryWakeUpCallDao : WakeUpCallDao {
    private val rows = MutableStateFlow<Map<String, WakeUpCallEntity>>(emptyMap())

    val all: List<WakeUpCallEntity> get() = rows.value.values.sortedBy { it.requestedAt }

    override suspend fun get(callId: String): WakeUpCallEntity? = rows.value[callId]

    override fun observeLatest(driverId: String): Flow<WakeUpCallEntity?> =
        rows.map { map -> map.values.filter { it.driverId == driverId }.maxByOrNull { it.requestedAt } }

    override suspend fun ringing(driverId: String): List<WakeUpCallEntity> =
        all.filter { it.driverId == driverId && it.state == WakeUpRowState.RINGING }

    override suspend fun pendingSends(driverId: String): List<WakeUpCallEntity> = all.filter {
        it.driverId == driverId && it.state in setOf(WakeUpRowState.ACKNOWLEDGED_PENDING, WakeUpRowState.EXPIRED_PENDING)
    }

    override suspend fun upsert(row: WakeUpCallEntity) = update(row.callId) { row }

    override suspend fun updateState(callId: String, state: WakeUpRowState, updatedAt: Instant) =
        update(callId) { it?.copy(state = state, updatedAt = updatedAt) }

    override suspend fun markResult(callId: String, state: WakeUpRowState, acknowledgedAt: Instant?, resultAt: Instant, updatedAt: Instant) =
        update(callId) { it?.copy(state = state, acknowledgedAt = acknowledgedAt, resultAt = resultAt, updatedAt = updatedAt) }

    override suspend fun olderThan(before: Instant): List<WakeUpCallEntity> = all.filter { it.requestedAt.isBefore(before) }

    override suspend fun delete(callIds: List<String>) {
        rows.value = rows.value - callIds.toSet()
    }

    private fun update(callId: String, transform: (WakeUpCallEntity?) -> WakeUpCallEntity?) {
        val next = transform(rows.value[callId])
        rows.value = if (next == null) rows.value - callId else rows.value + (callId to next)
    }
}

class RecordingWakeUpSyncScheduler : WakeUpSyncScheduler {
    val delaysSeconds = mutableListOf<Long>()
    val requests: Int get() = delaysSeconds.size

    override fun requestSend(delaySeconds: Long) {
        delaysSeconds += delaySeconds
    }
}

class RecordingWakeUpCallNotifier : WakeUpCallNotifier {
    val rung = mutableListOf<String>()
    var cancels = 0
    var channels = 0

    override fun ensureChannel() {
        channels++
    }

    override fun ring(call: WakeUpCall) {
        rung += call.callId
    }

    override fun cancel() {
        cancels++
    }
}

class FakeAlarmVolumeBooster : AlarmVolumeBooster {
    var raised = 0
    var restored = 0

    override fun raise() {
        raised++
    }

    override fun restore() {
        restored++
    }
}

class FakeWakeUpCallPermissions(var issue: WakeUpPermissionIssue? = null) : WakeUpCallPermissions {
    override fun firstIssue(): WakeUpPermissionIssue? = issue
}

/** Records what the push side and the controller asked for; [decision] answers every ring signal. */
class FakeWakeUpCallRepository : WakeUpCallRepository {
    var decision: WakeUpDecision = WakeUpDecision.Ignore
    val signals = mutableListOf<Triple<String, String?, Instant?>>()
    val stops = mutableListOf<String>()
    val ringingReports = mutableListOf<String>()
    val acknowledged = mutableListOf<String>()
    val expired = mutableListOf<String>()
    var stored = listOf<WakeUpCall>()
    val latest = MutableStateFlow<WakeUpOutcome?>(null)

    override fun observeLatest(): Flow<WakeUpOutcome?> = latest

    override suspend fun onRingSignal(callId: String, reason: String?, expiresAt: Instant?): WakeUpDecision {
        signals += Triple(callId, reason, expiresAt)
        return decision
    }

    override suspend fun onStopSignal(callId: String) {
        stops += callId
    }

    override suspend fun ringingCalls(): List<WakeUpCall> = stored

    override suspend fun reportRinging(callId: String) {
        ringingReports += callId
    }

    override suspend fun acknowledge(callId: String) {
        acknowledged += callId
    }

    override suspend fun expire(callId: String) {
        expired += callId
    }

    override suspend fun sendPending(): Boolean = true
}
