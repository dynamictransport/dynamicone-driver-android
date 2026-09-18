package com.dynamictransport.dynamicone.testing

import com.dynamictransport.dynamicone.core.alarm.DataStoreRestAlarmSettings
import com.dynamictransport.dynamicone.core.alarm.RestAlarmController
import com.dynamictransport.dynamicone.core.alarm.RestAlarmNotifier
import com.dynamictransport.dynamicone.core.alarm.RestAlarmPermissionState
import com.dynamictransport.dynamicone.core.alarm.RestAlarmPermissions
import com.dynamictransport.dynamicone.core.alarm.RestAlarmScheduler
import com.dynamictransport.dynamicone.core.database.CheckPhotoDao
import com.dynamictransport.dynamicone.core.database.CheckPhotoEntity
import com.dynamictransport.dynamicone.core.database.DriverCacheDao
import com.dynamictransport.dynamicone.core.database.DriverCacheEntity
import com.dynamictransport.dynamicone.core.database.ShiftCommandDao
import com.dynamictransport.dynamicone.core.database.ShiftCommandEntity
import com.dynamictransport.dynamicone.core.sync.DriverDayWorkScheduler
import com.dynamictransport.dynamicone.core.sync.ShiftSyncScheduler
import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import com.dynamictransport.dynamicone.domain.model.DailyRest
import com.dynamictransport.dynamicone.domain.model.DailyRestState
import com.dynamictransport.dynamicone.domain.model.DriverActivity
import com.dynamictransport.dynamicone.domain.model.DriverTimes
import com.dynamictransport.dynamicone.domain.model.DriverTimesAvailability
import com.dynamictransport.dynamicone.domain.model.LocalShiftCommand
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.model.ShiftCommandFailure
import com.dynamictransport.dynamicone.domain.model.ShiftCommandResult
import com.dynamictransport.dynamicone.domain.model.ShiftCommandState
import com.dynamictransport.dynamicone.domain.model.ShiftStartDraft
import com.dynamictransport.dynamicone.domain.model.VehicleInfo
import com.dynamictransport.dynamicone.domain.model.VehicleLookup
import com.dynamictransport.dynamicone.domain.repository.CheckPhotoStore
import com.dynamictransport.dynamicone.domain.repository.DailyShiftRepository
import com.dynamictransport.dynamicone.domain.repository.DriverTimesRepository
import com.dynamictransport.dynamicone.domain.repository.ShiftRepository
import com.dynamictransport.dynamicone.domain.repository.VehicleCheckRepository
import com.dynamictransport.dynamicone.domain.rules.RestAlarmMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Duration
import java.time.Instant

// Driver daily shift test support (docs/16). Invented sample values for tests only, not production data.

/** A daily rest the tachograph shows, ending at [end]. */
fun restingTimes(
    end: Instant,
    start: Instant? = end.minus(Duration.ofHours(9)),
    reference: Instant = TEST_NOW,
    minimum: Duration? = Duration.ofHours(9),
) = DriverTimes(
    availability = DriverTimesAvailability.AVAILABLE,
    referenceTime = reference,
    activity = DriverActivity.BREAK,
    dailyRest = DailyRest(DailyRestState.RESTING, start, end, minimum),
    remainingDrivingToday = Duration.ZERO,
)

fun workingTimes(reference: Instant = TEST_NOW, drivingLeft: Duration? = Duration.ofMinutes(125)) = DriverTimes(
    availability = DriverTimesAvailability.AVAILABLE,
    referenceTime = reference,
    activity = DriverActivity.DRIVING,
    dailyRest = DailyRest(DailyRestState.WORKING, null, null, null),
    remainingDrivingToday = drivingLeft,
    nextBreakDueAt = reference.plus(Duration.ofMinutes(90)),
    nextBreakMinimum = Duration.ofMinutes(45),
)

class FakeDriverTimesRepository(initial: DriverTimes? = null) : DriverTimesRepository {
    val times = MutableStateFlow(initial)
    var refreshes = 0
    var refreshResult = RefreshResult.SUCCESS

    override fun observeDriverTimes(): Flow<DriverTimes?> = times

    override suspend fun current(): DriverTimes? = times.value

    override suspend fun refresh(): RefreshResult {
        refreshes++
        return refreshResult
    }
}

class RecordingRestAlarmScheduler : RestAlarmScheduler {
    data class Alarm(val triggerAt: Instant, val rest: RestWindow, val snooze: Boolean)

    var scheduled: Alarm? = null
        private set
    val history = mutableListOf<Alarm>()
    var cancels = 0

    override fun schedule(triggerAt: Instant, rest: RestWindow, snooze: Boolean) {
        scheduled = Alarm(triggerAt, rest, snooze).also { history += it }
    }

    override fun cancel() {
        cancels++
        scheduled = null
    }
}

class RecordingRestAlarmNotifier : RestAlarmNotifier {
    data class Ring(val rest: RestWindow, val mode: RestAlarmMode, val test: Boolean)

    val rings = mutableListOf<Ring>()
    var ringing = false
        private set
    var cancels = 0

    override fun ensureChannels() = Unit

    override fun ring(rest: RestWindow, mode: RestAlarmMode, test: Boolean) {
        rings += Ring(rest, mode, test)
        ringing = true
    }

    override fun cancel() {
        cancels++
        ringing = false
    }
}

class FakeRestAlarmPermissions(var state: RestAlarmPermissionState = RestAlarmPermissionState()) : RestAlarmPermissions {
    override fun read(): RestAlarmPermissionState = state
}

/** The controller with its Android parts replaced; settings in an in-memory DataStore. */
class RestAlarmHarness(
    val times: FakeDriverTimesRepository = FakeDriverTimesRepository(),
    val shifts: ShiftRepository = FakeShiftRepository(),
    val clock: MutableClock = MutableClock(),
    scope: CoroutineScope,
) {
    val settings = DataStoreRestAlarmSettings(InMemoryPreferencesDataStore())
    val scheduler = RecordingRestAlarmScheduler()
    val notifier = RecordingRestAlarmNotifier()
    val permissions = FakeRestAlarmPermissions()
    val controller = RestAlarmController(times, shifts, settings, scheduler, notifier, permissions, clock, scope)
}

class RecordingShiftSyncScheduler : ShiftSyncScheduler {
    val delaysSeconds = mutableListOf<Long>()

    override fun requestSend(delaySeconds: Long) {
        delaysSeconds += delaySeconds
    }
}

class RecordingDriverDayWorkScheduler : DriverDayWorkScheduler {
    var periodicStarts = 0
    var stops = 0
    val delayedRefreshes = mutableListOf<Long>()

    override fun startPeriodic() {
        periodicStarts++
    }

    override fun stop() {
        stops++
    }

    override fun refreshAfter(delayMinutes: Long) {
        delayedRefreshes += delayMinutes
    }
}

/** Same queries as the Room DAO, for JVM tests without a database. DailyShiftDaoTest covers the SQL. */
class InMemoryShiftCommandDao : ShiftCommandDao {
    private val rows = MutableStateFlow<List<ShiftCommandEntity>>(emptyList())

    val all: List<ShiftCommandEntity> get() = rows.value

    private fun List<ShiftCommandEntity>.newestFirst() =
        sortedWith(compareByDescending<ShiftCommandEntity> { it.occurredAt }.thenBy { it.commandId })

    override fun observe(driverId: String): Flow<List<ShiftCommandEntity>> = rows.map { list -> list.filter { it.driverId == driverId }.newestFirst() }

    override suspend fun getAll(driverId: String): List<ShiftCommandEntity> = rows.value.filter { it.driverId == driverId }.newestFirst()

    override suspend fun get(commandId: String): ShiftCommandEntity? = rows.value.firstOrNull { it.commandId == commandId }

    override suspend fun getUnsent(driverId: String): List<ShiftCommandEntity> = rows.value
        .filter { it.driverId == driverId && (it.state == ShiftCommandState.PENDING || it.state == ShiftCommandState.SENDING) }
        .sortedWith(compareBy<ShiftCommandEntity> { it.occurredAt }.thenBy { it.commandId })

    override suspend fun insert(command: ShiftCommandEntity) {
        check(rows.value.none { it.commandId == command.commandId }) { "duplicate command id" }
        rows.update { it + command }
    }

    override suspend fun updateState(commandId: String, state: ShiftCommandState, failure: ShiftCommandFailure?, updatedAt: Instant) =
        rows.update { list -> list.map { if (it.commandId == commandId) it.copy(state = state, failure = failure, updatedAt = updatedAt) else it } }

    override suspend fun markSynced(commandId: String, checkId: String?, updatedAt: Instant) =
        rows.update { list ->
            list.map {
                if (it.commandId == commandId) it.copy(state = ShiftCommandState.SYNCED, failure = null, checkId = checkId, updatedAt = updatedAt) else it
            }
        }

    override suspend fun delete(commandId: String) = rows.update { list -> list.filterNot { it.commandId == commandId } }

    override suspend fun deleteSyncedBefore(before: Instant) =
        rows.update { list -> list.filterNot { it.state == ShiftCommandState.SYNCED && it.occurredAt.isBefore(before) } }

    override suspend fun deleteForOtherDrivers(driverId: String) = rows.update { list -> list.filter { it.driverId == driverId } }

    /** Keeps the photo DAO's orphan query in step with this table. */
    fun commandIds(): Set<String> = rows.value.map { it.commandId }.toSet()
}

class InMemoryCheckPhotoDao(private val commands: InMemoryShiftCommandDao) : CheckPhotoDao {
    private val rows = MutableStateFlow<List<CheckPhotoEntity>>(emptyList())

    val all: List<CheckPhotoEntity> get() = rows.value

    override fun observeAll(): Flow<List<CheckPhotoEntity>> = rows.map { list -> list.sortedBy { it.photoId } }

    override suspend fun forCommand(commandId: String): List<CheckPhotoEntity> = rows.value.filter { it.commandId == commandId }.sortedBy { it.photoId }

    override suspend fun insertAll(photos: List<CheckPhotoEntity>) =
        rows.update { list -> list.filterNot { row -> photos.any { it.photoId == row.photoId } } + photos }

    override suspend fun allFilePaths(): List<String> = rows.value.map { it.filePath }

    override suspend fun updateState(photoId: String, state: ShiftCommandState, updatedAt: Instant) =
        rows.update { list -> list.map { if (it.photoId == photoId) it.copy(state = state, updatedAt = updatedAt) else it } }

    override suspend fun deleteForCommand(commandId: String) = rows.update { list -> list.filterNot { it.commandId == commandId } }

    override suspend fun getOrphans(): List<CheckPhotoEntity> = rows.value.filter { it.commandId !in commands.commandIds() }

    override suspend fun delete(photoId: String) = rows.update { list -> list.filterNot { it.photoId == photoId } }
}

class InMemoryDriverCacheDao : DriverCacheDao {
    private val rows = MutableStateFlow<Map<Pair<String, String>, DriverCacheEntity>>(emptyMap())

    val all: Collection<DriverCacheEntity> get() = rows.value.values

    override suspend fun get(driverId: String, key: String): DriverCacheEntity? = rows.value[driverId to key]

    override suspend fun upsert(entry: DriverCacheEntity) = rows.update { it + ((entry.driverId to entry.key) to entry) }

    override suspend fun deleteForOtherDrivers(driverId: String) = rows.update { map -> map.filterKeys { it.first == driverId } }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

/** Photo files in a temporary folder, named like the app's (`vehicle_check_media`). */
class FakeCheckPhotoStore(private val directory: File) : CheckPhotoStore {
    var enoughSpace = true
    private var counter = 0
    val discarded = mutableListOf<String>()

    override fun hasEnoughSpace(): Boolean = enoughSpace

    override suspend fun createPhotoFile(): String = File(directory.apply { mkdirs() }, "check_${++counter}.jpg").apply { createNewFile() }.absolutePath

    override suspend fun discard(filePath: String) {
        discarded += filePath
        File(filePath).delete()
    }
}

class FakeDailyShiftRepository : DailyShiftRepository {
    val planned = MutableStateFlow<VehicleInfo?>(null)
    val commands = MutableStateFlow<List<LocalShiftCommand>>(emptyList())
    val started = mutableListOf<ShiftStartDraft>()
    val discarded = mutableListOf<String>()
    var startResult: ShiftCommandResult = ShiftCommandResult.Recorded
    var startGate: CompletableDeferred<Unit>? = null

    override fun observePlannedVehicle(): Flow<VehicleInfo?> = planned

    override fun observeCommands(): Flow<List<LocalShiftCommand>> = commands

    override suspend fun refresh(): RefreshResult = RefreshResult.SUCCESS

    override suspend fun startShift(draft: ShiftStartDraft): ShiftCommandResult {
        started += draft
        startGate?.await()
        return startResult
    }

    override suspend fun sendPending(): Boolean = true

    override suspend fun resend(commandId: String): ShiftCommandResult = ShiftCommandResult.Saved()

    override suspend fun discard(commandId: String) {
        discarded += commandId
    }
}

class FakeVehicleCheckRepository(template: CheckTemplate = CheckTemplate.Default) : VehicleCheckRepository {
    val template = MutableStateFlow(template)
    var lookup: (String) -> VehicleLookup = { VehicleLookup.Found(VehicleInfo("veh-1", "WX 12345", "DAF XF"), isPlanned = true) }
    val lookedUp = mutableListOf<String>()
    var templateRefreshes = 0

    override fun observeTemplate(): Flow<CheckTemplate> = template

    override suspend fun refreshTemplate(): RefreshResult {
        templateRefreshes++
        return RefreshResult.SUCCESS
    }

    override suspend fun lookupVehicle(tagUid: String): VehicleLookup {
        lookedUp += tagUid
        return lookup(tagUid)
    }
}

/** Driver API daily shift bodies in the docs/16 contract shape. Invented samples for tests, not production data. */
object DriverShiftJson {
    fun shift(
        id: String = "shift-9",
        commandId: String? = "start-1",
        startedAt: String = "2026-09-12T08:00:00Z",
        endedAt: String? = null,
        endedBy: String? = null,
        plate: String = "WX 12345",
        defects: Int = 0,
    ) = """{"id":"$id","commandId":${commandId?.let { "\"$it\"" } ?: "null"},"startedAtUtc":"$startedAt","endedAtUtc":${endedAt?.let { "\"$it\"" } ?: "null"},"endedBy":${endedBy?.let { "\"$it\"" } ?: "null"},"vehicle":{"vehicleId":"veh-1","registrationNumber":"$plate","identifiedBy":"Nfc"},"trailerRegistrationNumber":"WGM 1234A","startedBeforeRestEnd":false,"vehicleCheck":{"id":"check-1","result":"${if (defects == 0) "Ok" else "DefectsReported"}","defectCount":$defects,"photosExpected":$defects,"photosReceived":0}}"""

    fun state(active: String? = null, lastEnded: String? = null, planned: Boolean = true) =
        """{"active":${active ?: "null"},"lastEnded":${lastEnded ?: "null"},"plannedVehicle":${if (planned) """{"vehicleId":"veh-1","registrationNumber":"WX 12345","trailerRegistrationNumber":"WGM 1234A"}""" else "null"}}"""

    fun startResult(shift: String, checkId: String = "check-1", pendingPhotos: List<String> = emptyList()) =
        """{"shift":$shift,"vehicleCheck":{"id":"$checkId","pendingPhotoIds":[${pendingPhotos.joinToString(",") { "\"$it\"" }}]}}"""

    fun endResult(shift: String) = """{"shift":$shift}"""

    fun resting(start: String, end: String, reference: String = "2026-09-12T08:00:00Z") =
        """{"status":"Available","referenceTimeUtc":"$reference","receivedAtUtc":"$reference","currentActivity":"Break","dailyRest":{"state":"Resting","startedAtUtc":"$start","endsAtUtc":"$end","minimumDurationMinutes":540},"remainingDrivingTodayMinutes":0,"remainingWorkingTodayMinutes":null,"nextBreakDueUtc":null,"nextBreakMinimumMinutes":null,"latestEndCurrentShiftUtc":null,"hasInfringement":false}"""
}
