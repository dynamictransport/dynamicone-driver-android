package com.dynamictransport.dynamicone.data.remote.fake

import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.AuthTokens
import com.dynamictransport.dynamicone.domain.model.BreakState
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.model.DocumentUploadMetadata
import com.dynamictransport.dynamicone.domain.model.Driver
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.model.IncidentReport
import com.dynamictransport.dynamicone.domain.model.RemoteDocument
import com.dynamictransport.dynamicone.domain.model.RemoteDocumentStatus
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.statemachine.TransitionResult
import com.dynamictransport.dynamicone.domain.statemachine.TripSnapshot
import com.dynamictransport.dynamicone.domain.statemachine.TripStateMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * In-app DEMO backend used until the DynamicONE Driver API is integrated. It enforces the same
 * state machine, answers 409 on illegal transitions and is idempotent per clientActionId, so the
 * offline queue and reconciliation run exactly as they will against the real server.
 * Its state is persisted to [stateFile] so an app restart does not reset the demo.
 */
class FakeDriverBackend(
    private val clock: Clock,
    private val json: Json,
    private val stateFile: File? = null,
    private val latencyMillis: Long = 0,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val mutex = Mutex()
    private var cached: FakeState? = null

    val simulateOffline = MutableStateFlow(false)

    suspend fun login(username: String, password: String): AuthTokens = call { state ->
        if (username.isBlank() || password != DEMO_PASSWORD) throw RemoteException.Unauthorized()
        state to AuthTokens(
            accessToken = "demo-access-${UUID.randomUUID()}",
            refreshToken = "demo-refresh-${UUID.randomUUID()}",
            expiresAt = clock.instant().plus(Duration.ofHours(1)),
        )
    }

    suspend fun profile(): Driver = call { state -> state to DEMO_DRIVER }

    suspend fun currentShift(): Shift = call { state -> state to state.shift.toDomain() }

    suspend fun startShift(clientActionId: String, at: Instant): Shift = call { state ->
        if (clientActionId in state.processedActionIds) return@call state to state.shift.toDomain()
        if (state.shift.status == ShiftStatus.ACTIVE) throw RemoteException.Conflict()
        val shift = FakeShift(
            id = "shf-${at.toEpochMilli()}",
            status = ShiftStatus.ACTIVE,
            startedAt = at.toEpochMilli(),
            endedAt = null,
            vehiclePlate = DEMO_VEHICLE,
            trailerPlate = DEMO_TRAILER,
        )
        state.copy(shift = shift, processedActionIds = state.processedActionIds + clientActionId) to shift.toDomain()
    }

    suspend fun endShift(clientActionId: String, at: Instant): Shift = call { state ->
        if (clientActionId in state.processedActionIds) return@call state to state.shift.toDomain()
        if (state.shift.status != ShiftStatus.ACTIVE) throw RemoteException.Conflict()
        if (state.trips.any { it.state.isInProgress }) throw RemoteException.Conflict()
        val shift = state.shift.copy(status = ShiftStatus.FINISHED, endedAt = at.toEpochMilli())
        state.copy(shift = shift, processedActionIds = state.processedActionIds + clientActionId) to shift.toDomain()
    }

    suspend fun trips(): List<FakeTrip> = call { state -> state to state.trips }

    suspend fun trip(tripId: String): Trip = call { state ->
        val trip = state.trips.firstOrNull { it.id == tripId } ?: throw RemoteException.Rejected(404)
        state to trip.toDetail()
    }

    suspend fun submitTripAction(
        tripId: String,
        clientActionId: String,
        action: TripActionType,
    ): Trip = call { state ->
        val trip = state.trips.firstOrNull { it.id == tripId } ?: throw RemoteException.Rejected(404)
        if (clientActionId in state.processedActionIds) return@call state to trip.toDetail()
        if (action == TripActionType.START_TRIP && state.shift.status != ShiftStatus.ACTIVE) {
            throw RemoteException.Conflict()
        }
        val applied = TripStateMachine.transition(TripSnapshot(trip.state, trip.breakState), action)
            as? TransitionResult.Applied ?: throw RemoteException.Conflict()
        val updated = trip.copy(state = applied.snapshot.state, breakState = applied.snapshot.breakState)
        val next = state.copy(
            trips = state.trips.map { if (it.id == tripId) updated else it },
            processedActionIds = state.processedActionIds + clientActionId,
        )
        next to updated.toDetail()
    }

    suspend fun documents(tripId: String): List<RemoteDocument> = call { state ->
        state to state.documents.filter { it.tripId == tripId }.map { it.toDomain() }
    }

    suspend fun uploadDocument(metadata: DocumentUploadMetadata): RemoteDocument = call { state ->
        state.documents.firstOrNull { it.clientActionId == metadata.clientActionId }
            ?.let { return@call state to it.toDomain() }
        if (state.trips.none { it.id == metadata.tripId }) throw RemoteException.Rejected(404)
        val document = FakeDocument(
            id = "doc-${UUID.randomUUID()}",
            tripId = metadata.tripId,
            type = metadata.type,
            capturedAt = metadata.capturedAt.toEpochMilli(),
            clientActionId = metadata.clientActionId,
        )
        state.copy(
            documents = state.documents + document,
            processedActionIds = state.processedActionIds + metadata.clientActionId,
        ) to document.toDomain()
    }

    suspend fun createIncident(report: IncidentReport) = call { state ->
        if (report.clientActionId in state.processedActionIds) return@call state to Unit
        val incident = FakeIncident(
            clientActionId = report.clientActionId,
            tripId = report.tripId,
            category = report.category,
            note = report.note,
            createdAt = report.createdAt.toEpochMilli(),
        )
        state.copy(
            incidents = state.incidents + incident,
            processedActionIds = state.processedActionIds + report.clientActionId,
        ) to Unit
    }

    // --- Planner-side helpers (demo and tests) ---

    suspend fun plannerCancelTrip(tripId: String) = mutate { state ->
        state.copy(trips = state.trips.map { if (it.id == tripId) it.copy(state = TripState.CANCELLED) else it })
    }

    suspend fun incidents(): List<FakeIncident> = mutex.withLock { load().incidents }

    suspend fun processedActionIds(): Set<String> = mutex.withLock { load().processedActionIds }

    suspend fun reset() = mutate { seedState(clock.instant(), zone) }

    private suspend fun mutate(block: (FakeState) -> FakeState) = mutex.withLock {
        val next = block(load())
        cached = next
        persist(next)
    }

    private suspend fun <T> call(block: (FakeState) -> Pair<FakeState, T>): T {
        if (latencyMillis > 0) delay(latencyMillis)
        if (simulateOffline.value) throw RemoteException.Offline()
        return mutex.withLock {
            val current = load()
            val (next, result) = block(current)
            if (next != current) {
                cached = next
                persist(next)
            }
            result
        }
    }

    private suspend fun load(): FakeState {
        cached?.let { return it }
        val restored = stateFile?.takeIf { it.exists() }?.let { file ->
            withContext(Dispatchers.IO) {
                runCatching { json.decodeFromString(FakeState.serializer(), file.readText()) }.getOrNull()
            }
        }
        return (restored ?: seedState(clock.instant(), zone)).also { cached = it }
    }

    private suspend fun persist(state: FakeState) {
        val file = stateFile ?: return
        withContext(Dispatchers.IO) {
            file.writeText(json.encodeToString(FakeState.serializer(), state))
        }
    }

    companion object {
        const val DEMO_PASSWORD = "demo"
        const val DEMO_VEHICLE = "WX 9234K"
        const val DEMO_TRAILER = "WGM 45T2"
        val DEMO_DRIVER = Driver(
            id = "drv-demo-1027",
            displayName = "Mehmet Demir",
            personnelNo = "1027",
            preferredLanguage = "tr",
        )
    }
}

@Serializable
data class FakeState(
    val shift: FakeShift,
    val trips: List<FakeTrip>,
    val processedActionIds: Set<String> = emptySet(),
    val documents: List<FakeDocument> = emptyList(),
    val incidents: List<FakeIncident> = emptyList(),
)

@Serializable
data class FakeShift(
    val id: String? = null,
    val status: ShiftStatus = ShiftStatus.NOT_STARTED,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val vehiclePlate: String? = null,
    val trailerPlate: String? = null,
) {
    fun toDomain() = Shift(
        id = id,
        status = status,
        startedAt = startedAt?.let(Instant::ofEpochMilli),
        endedAt = endedAt?.let(Instant::ofEpochMilli),
        vehiclePlate = vehiclePlate,
        trailerPlate = trailerPlate,
    )
}

@Serializable
data class FakeTrip(
    val id: String,
    val tripNo: String,
    val state: TripState,
    val breakState: BreakState = BreakState.NONE,
    val pickupName: String,
    val deliveryName: String,
    val plannedStartAt: Long?,
    val containerNo: String? = null,
    val pickupAddress: String? = null,
    val deliveryAddress: String? = null,
    val vehiclePlate: String? = null,
    val trailerPlate: String? = null,
    val sealNo: String? = null,
    val instructions: String? = null,
) {
    fun toDetail() = Trip(
        id = id,
        tripNo = tripNo,
        state = state,
        breakState = breakState,
        pickupName = pickupName,
        deliveryName = deliveryName,
        plannedStartAt = plannedStartAt?.let(Instant::ofEpochMilli),
        containerNo = containerNo,
        pickupAddress = pickupAddress,
        deliveryAddress = deliveryAddress,
        vehiclePlate = vehiclePlate,
        trailerPlate = trailerPlate,
        sealNo = sealNo,
        instructions = instructions,
        serverAllowedActions = TripStateMachine.legalActions(TripSnapshot(state, breakState)),
    )
}

@Serializable
data class FakeDocument(
    val id: String,
    val tripId: String,
    val type: DocumentType,
    val capturedAt: Long?,
    val clientActionId: String,
) {
    fun toDomain() = RemoteDocument(
        id = id,
        type = type,
        status = RemoteDocumentStatus.UPLOADED,
        capturedAt = capturedAt?.let(Instant::ofEpochMilli),
        thumbnailUrl = null,
    )
}

@Serializable
data class FakeIncident(
    val clientActionId: String,
    val tripId: String?,
    val category: IncidentCategory,
    val note: String?,
    val createdAt: Long,
)

internal fun seedState(now: Instant, zone: ZoneId): FakeState {
    val today = now.atZone(zone).toLocalDate()
    fun at(daysFromToday: Long, hour: Int, minute: Int): Long =
        today.plusDays(daysFromToday).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    val soon = now.plus(Duration.ofMinutes(45)).toEpochMilli().let { it - it % Duration.ofMinutes(5).toMillis() }

    return FakeState(
        shift = FakeShift(),
        trips = listOf(
            FakeTrip(
                id = "trp-261245",
                tripNo = "TRP-261245",
                state = TripState.ASSIGNED,
                pickupName = "Rotterdam ECT Delta",
                deliveryName = "Düsseldorf Reisholz",
                plannedStartAt = soon,
                containerNo = "MSKU 458921-7",
                pickupAddress = "Europaweg 875, 3199 LD Maasvlakte Rotterdam",
                deliveryAddress = "Am Trippelsberg 100, 40589 Düsseldorf",
                vehiclePlate = FakeDriverBackend.DEMO_VEHICLE,
                trailerPlate = FakeDriverBackend.DEMO_TRAILER,
                sealNo = "781392",
                instructions = "Check the seal number before leaving the terminal. Gate-in slot 10:00–10:30.",
            ),
            FakeTrip(
                id = "trp-261310",
                tripNo = "TRP-261310",
                state = TripState.ACCEPTED,
                pickupName = "Antwerp MPET",
                deliveryName = "Duisburg DIT",
                plannedStartAt = at(1, 7, 30),
                containerNo = "CMAU 771203-4",
                pickupAddress = "Blikken 1, 9130 Beveren",
                deliveryAddress = "Hafenstraße 33, 47119 Duisburg",
                vehiclePlate = FakeDriverBackend.DEMO_VEHICLE,
                trailerPlate = FakeDriverBackend.DEMO_TRAILER,
            ),
            FakeTrip(
                id = "trp-261198",
                tripNo = "TRP-261198",
                state = TripState.FINISHED,
                pickupName = "Rotterdam RWG",
                deliveryName = "Venlo ECT",
                plannedStartAt = at(-1, 6, 45),
                containerNo = "HLXU 330912-1",
            ),
            FakeTrip(
                id = "trp-261177",
                tripNo = "TRP-261177",
                state = TripState.FINISHED,
                pickupName = "Moerdijk",
                deliveryName = "Duisburg Logport",
                plannedStartAt = at(-2, 8, 15),
                containerNo = "TGHU 902114-6",
            ),
        ),
    )
}
