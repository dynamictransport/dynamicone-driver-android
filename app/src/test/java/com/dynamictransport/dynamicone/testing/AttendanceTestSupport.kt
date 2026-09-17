package com.dynamictransport.dynamicone.testing

import com.dynamictransport.dynamicone.core.common.DeviceUptime
import com.dynamictransport.dynamicone.core.database.AttendancePunchDao
import com.dynamictransport.dynamicone.core.database.AttendancePunchEntity
import com.dynamictransport.dynamicone.core.nfc.NfcAvailability
import com.dynamictransport.dynamicone.core.nfc.NfcStatusProvider
import com.dynamictransport.dynamicone.core.sync.AttendanceSyncScheduler
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendanceMonth
import com.dynamictransport.dynamicone.domain.model.AttendancePunch
import com.dynamictransport.dynamicone.domain.model.AttendancePunchSource
import com.dynamictransport.dynamicone.domain.model.AttendanceStatus
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.LocalPunchState
import com.dynamictransport.dynamicone.domain.model.PunchFailure
import com.dynamictransport.dynamicone.domain.model.PunchResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.repository.AttendanceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.YearMonth

/** Same queries as the Room DAO, for JVM tests without a database. AttendancePunchDaoTest covers the SQL. */
class InMemoryAttendancePunchDao : AttendancePunchDao {
    private val rows = MutableStateFlow<List<AttendancePunchEntity>>(emptyList())

    val all: List<AttendancePunchEntity> get() = rows.value

    override fun observe(userId: String): Flow<List<AttendancePunchEntity>> =
        rows.map { list -> list.filter { it.userId == userId }.sortedWith(compareByDescending<AttendancePunchEntity> { it.scannedAt }.thenBy { it.commandId }) }

    override suspend fun get(commandId: String): AttendancePunchEntity? = rows.value.firstOrNull { it.commandId == commandId }

    override suspend fun getUnsent(userId: String): List<AttendancePunchEntity> = rows.value
        .filter { it.userId == userId && (it.state == LocalPunchState.PENDING || it.state == LocalPunchState.SENDING) }
        .sortedWith(compareBy<AttendancePunchEntity> { it.scannedAt }.thenBy { it.commandId })

    override suspend fun insert(punch: AttendancePunchEntity) {
        check(rows.value.none { it.commandId == punch.commandId }) { "duplicate command id" }
        rows.update { it + punch }
    }

    override suspend fun updateState(commandId: String, state: LocalPunchState, failure: PunchFailure?, updatedAt: Instant) =
        rows.update { list -> list.map { if (it.commandId == commandId) it.copy(state = state, failure = failure, updatedAt = updatedAt) else it } }

    override suspend fun markSynced(commandId: String, occurredAt: Instant, pointName: String?, updatedAt: Instant) =
        rows.update { list ->
            list.map {
                if (it.commandId == commandId) {
                    it.copy(state = LocalPunchState.SYNCED, failure = null, occurredAt = occurredAt, pointName = pointName, updatedAt = updatedAt)
                } else {
                    it
                }
            }
        }

    override suspend fun delete(commandId: String) = rows.update { list -> list.filterNot { it.commandId == commandId } }

    override suspend fun deleteSyncedBefore(before: Instant) =
        rows.update { list -> list.filterNot { it.state == LocalPunchState.SYNCED && it.scannedAt.isBefore(before) } }

    override suspend fun deleteForOtherUsers(userId: String) = rows.update { list -> list.filter { it.userId == userId } }
}

class FakeDeviceUptime(var elapsedMillis: Long = 1_000_000L, var boot: Int? = 7) : DeviceUptime {
    override fun elapsedRealtimeMillis(): Long = elapsedMillis

    override fun bootCount(): Int? = boot
}

class RecordingAttendanceSyncScheduler : AttendanceSyncScheduler {
    var requests = 0

    override fun requestSend() {
        requests++
    }
}

class FakeNfcStatusProvider(var value: NfcAvailability = NfcAvailability.AVAILABLE) : NfcStatusProvider {
    override fun availability(): NfcAvailability = value
}

class FakeAttendanceRepository : AttendanceRepository {
    val status = MutableStateFlow<AttendanceStatus?>(null)
    val local = MutableStateFlow<List<LocalPunch>>(emptyList())
    val months = MutableStateFlow<Map<YearMonth, AttendanceMonth>>(emptyMap())
    var refreshResult = RefreshResult.SUCCESS
    var monthResult = RefreshResult.SUCCESS
    var punchResult: (AttendanceDirection) -> PunchResult = { direction ->
        PunchResult.Recorded(AttendancePunch("p-1", "cmd-1", direction, TEST_NOW, null, AttendancePunchSource.MOBILE_NFC, "Office"))
    }
    var punchGate: CompletableDeferred<Unit>? = null
    val recorded = mutableListOf<Pair<AttendanceDirection, String>>()
    val resent = mutableListOf<String>()
    val dismissed = mutableListOf<String>()
    val refreshedMonths = mutableListOf<YearMonth>()
    var statusRefreshes = 0

    override fun observeStatus(): Flow<AttendanceStatus?> = status

    override fun observeLocalPunches(): Flow<List<LocalPunch>> = local

    override suspend fun refreshStatus(): RefreshResult {
        statusRefreshes++
        return refreshResult
    }

    override suspend fun recordPunch(direction: AttendanceDirection, tagUid: String): PunchResult {
        recorded += direction to tagUid
        punchGate?.await()
        return punchResult(direction)
    }

    override suspend fun sendPending(): Boolean = true

    override suspend fun resend(commandId: String): PunchResult {
        resent += commandId
        return PunchResult.Saved(commandId)
    }

    override suspend fun dismiss(commandId: String) {
        dismissed += commandId
    }

    override fun observeMonth(month: YearMonth): Flow<AttendanceMonth?> = months.map { it[month] }

    override suspend fun refreshMonth(month: YearMonth): RefreshResult {
        refreshedMonths += month
        return monthResult
    }
}

/** Mobile API attendance bodies in the docs/15 contract shape. Invented samples for tests, not production data. */
object MobileApiJson {
    fun profile(modules: List<String>, driverId: String? = null, userId: String = "user-9") =
        """{"userId":"$userId","employeeId":"emp-9","driverId":${driverId?.let { "\"$it\"" } ?: "null"},"displayName":"Ayşe Yılmaz","preferredLanguageCode":"tr","modules":[${modules.joinToString(",") { "\"$it\"" }}]}"""

    fun punch(id: String, commandId: String?, direction: String, occurredAt: String, workDate: String = occurredAt.take(10)) =
        """{"id":"$id","commandId":${commandId?.let { "\"$it\"" } ?: "null"},"direction":"$direction","occurredAtUtc":"$occurredAt","receivedAtUtc":"$occurredAt","workDate":"$workDate","source":"MobileNfc","pointName":"Kraków office — entrance"}"""

    fun status(state: String, nextAction: String, workDate: String, lastPunch: String?) =
        """{"state":"$state","nextAction":"$nextAction","workDate":"$workDate","timeZoneId":"Europe/Warsaw","lastPunch":${lastPunch ?: "null"}}"""

    fun punchResult(punch: String, status: String) = """{"punch":$punch,"status":$status}"""
}
