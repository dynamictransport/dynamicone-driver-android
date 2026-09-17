package com.dynamictransport.driver.testing

import com.dynamictransport.driver.core.common.AppLogger
import com.dynamictransport.driver.core.common.IdGenerator
import com.dynamictransport.driver.core.sync.SyncScheduler
import com.dynamictransport.driver.domain.model.BreakState
import com.dynamictransport.driver.domain.model.DocumentSlot
import com.dynamictransport.driver.domain.model.DocumentSlotState
import com.dynamictransport.driver.domain.model.DocumentType
import com.dynamictransport.driver.domain.model.ExecutionAction
import com.dynamictransport.driver.domain.model.ExecutionActionCode
import com.dynamictransport.driver.domain.model.GeoPoint
import com.dynamictransport.driver.domain.model.Shift
import com.dynamictransport.driver.domain.model.ShiftStatus
import com.dynamictransport.driver.domain.model.StopActivity
import com.dynamictransport.driver.domain.model.StopStatus
import com.dynamictransport.driver.domain.model.SyncState
import com.dynamictransport.driver.domain.model.Trip
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.model.TripContainer
import com.dynamictransport.driver.domain.model.TripExecution
import com.dynamictransport.driver.domain.model.TripLocation
import com.dynamictransport.driver.domain.model.TripState
import com.dynamictransport.driver.domain.model.TripStop
import com.dynamictransport.driver.domain.model.TripWorkflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// Test fixtures: invented sample values for unit tests only. They are not production data.

val TEST_NOW: Instant = Instant.parse("2026-09-12T08:00:00Z")

val ACTIVE_SHIFT = Shift(
    id = "shift-1",
    status = ShiftStatus.ACTIVE,
    startedAt = TEST_NOW,
    endedAt = null,
    vehiclePlate = "WX 9234K",
    trailerPlate = "WGM 45T2",
)

fun testTrip(
    id: String = "trip-1",
    state: TripState = TripState.ASSIGNED,
    breakState: BreakState = BreakState.NONE,
    serverAllowedActions: Set<TripActionType>? = null,
    plannedStartAt: Instant? = Instant.parse("2026-09-12T10:15:00Z"),
    syncState: SyncState = SyncState.SYNCED,
    workflow: TripWorkflow = TripWorkflow.LOCAL_STATE_MACHINE,
    stops: List<TripStop> = emptyList(),
    containers: List<TripContainer> = emptyList(),
    nextStop: TripLocation? = null,
) = Trip(
    id = id,
    tripNo = "TRP-${id.uppercase()}",
    state = state,
    breakState = breakState,
    pickupName = "Rotterdam ECT Delta",
    deliveryName = "Düsseldorf Reisholz",
    plannedStartAt = plannedStartAt,
    containerNo = "MSKU 458921-7",
    pickupAddress = "Europaweg 875, Maasvlakte",
    deliveryAddress = "Am Trippelsberg 100, Düsseldorf",
    vehiclePlate = "WX 9234K",
    trailerPlate = "WGM 45T2",
    sealNo = "781392",
    instructions = "Check the seal number.",
    serverAllowedActions = serverAllowedActions,
    syncState = syncState,
    workflow = workflow,
    stops = stops,
    containers = containers,
    nextStop = nextStop,
)

fun testStop(
    id: String,
    sequence: Int,
    activity: StopActivity,
    name: String,
    status: StopStatus = StopStatus.PLANNED,
    coordinates: GeoPoint? = GeoPoint(51.9, 4.1),
) = TripStop(
    id = id,
    sequence = sequence,
    activity = activity,
    status = status,
    locationName = name,
    address = null,
    latitude = coordinates?.latitude,
    longitude = coordinates?.longitude,
    plannedArrivalAt = null,
    plannedDepartureAt = null,
    actualArrivalAt = null,
    actualDepartureAt = null,
    reference = null,
)

fun testAction(
    code: ExecutionActionCode,
    label: String? = null,
    stopId: String? = null,
    containerId: String? = null,
) = ExecutionAction(code, label, stopId, containerId)

fun testExecution(
    tripId: String = "trip-1",
    version: String = "v1",
    actions: List<ExecutionAction> = emptyList(),
    documents: List<DocumentSlot> = emptyList(),
    workflow: TripWorkflow = TripWorkflow.TRIP_ACTIONS,
) = TripExecution(tripId, version, "InProgress", workflow, actions, documents)

fun testSlot(
    key: String,
    type: DocumentType?,
    state: DocumentSlotState,
    required: Boolean? = true,
    stopId: String? = null,
    containerId: String? = null,
    documentId: String? = null,
    reviewNote: String? = null,
) = DocumentSlot(
    key = key,
    type = type,
    label = null,
    stopId = stopId,
    containerId = containerId,
    required = required,
    state = state,
    documentId = documentId,
    fileName = documentId?.let { "$it.jpg" },
    reviewNote = reviewNote,
    revision = null,
)

class MutableClock(
    var now: Instant = TEST_NOW,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
}

class SequenceIdGenerator(private val prefix: String = "id") : IdGenerator {
    private var counter = 0

    @Synchronized
    override fun newId(): String = "$prefix-${++counter}"
}

object NoOpLogger : AppLogger {
    override fun event(name: String, attributes: Map<String, String>) = Unit
    override fun warn(message: String, throwable: Throwable?) = Unit
}

class RecordingSyncScheduler : SyncScheduler {
    var requests = 0
        private set

    override fun requestSync() {
        requests++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Keeps a `WhileSubscribed` state flow active for the duration of the test. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.keepCollecting(flow: StateFlow<T>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
}
