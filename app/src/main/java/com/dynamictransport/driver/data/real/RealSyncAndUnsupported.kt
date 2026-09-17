package com.dynamictransport.driver.data.real

import com.dynamictransport.driver.core.common.ApplicationScope
import com.dynamictransport.driver.core.network.DriverStatusNames
import com.dynamictransport.driver.core.network.RemoteException
import com.dynamictransport.driver.core.sync.SyncRunResult
import com.dynamictransport.driver.core.sync.SyncRunner
import com.dynamictransport.driver.data.remote.DriverActionRequest
import com.dynamictransport.driver.data.remote.DriverRemoteDataSource
import com.dynamictransport.driver.data.remote.RemoteTripSummary
import com.dynamictransport.driver.data.remote.TripActionRequest
import com.dynamictransport.driver.domain.model.ActionRejection
import com.dynamictransport.driver.domain.model.ActionResult
import com.dynamictransport.driver.domain.model.AuthTokens
import com.dynamictransport.driver.domain.model.DocumentUploadMetadata
import com.dynamictransport.driver.domain.model.Driver
import com.dynamictransport.driver.domain.model.ExecutionActionCode
import com.dynamictransport.driver.domain.model.IncidentCategory
import com.dynamictransport.driver.domain.model.IncidentReport
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.model.RemoteDocument
import com.dynamictransport.driver.domain.model.Shift
import com.dynamictransport.driver.domain.model.SyncOverview
import com.dynamictransport.driver.domain.model.Trip
import com.dynamictransport.driver.domain.model.TripState
import com.dynamictransport.driver.domain.model.TripWorkflow
import com.dynamictransport.driver.domain.repository.IncidentRepository
import com.dynamictransport.driver.domain.repository.ShiftRepository
import com.dynamictransport.driver.domain.repository.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real mode has no background queue: every write is confirmed by the server before the UI moves on.
 * "Sync" therefore means reading the current server state; "last sync" is the last successful read.
 * Commands with an idempotent id whose outcome is not confirmed are counted as waiting.
 */
@Singleton
class RealSyncRepository @Inject constructor(
    private val trips: RealTripRepository,
    private val messages: RealMessageRepository,
    private val tracker: ApiActivityTracker,
    private val commands: PendingCommandStore,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : SyncRepository {

    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)
    private val mutex = Mutex()

    override fun observeOverview(): Flow<SyncOverview> =
        combine(tracker.lastSuccessfulReadAt, commands.observeUnconfirmedCount()) { lastRead, unconfirmed ->
            SyncOverview(pendingCount = unconfirmed, failures = emptyList(), lastSuccessfulSyncAt = lastRead)
        }

    override fun observeLastRefresh(): Flow<RefreshResult?> = lastRefresh

    override fun requestSync() {
        appScope.launch { refreshFromServer() }
    }

    /** Stored commands are resent only by the driver, from the trip or conversation they belong to. */
    override suspend fun retryFailed() = Unit

    override suspend fun acknowledgeFailure(clientActionId: String) = Unit

    override suspend fun refreshFromServer(): RefreshResult = mutex.withLock {
        val result = trips.refreshAll()
        if (result == RefreshResult.SUCCESS) {
            // Stops, containers and plans of the trips the driver can still work on.
            trips.currentTrips()
                .filterNot { it.state.isTerminal }
                .take(MAX_DETAILED_TRIPS)
                .forEach { trips.refreshTrip(it.id) }
            // Conversation list and unread counts; a failed read leaves the trip result unchanged.
            messages.refreshConversations()
        }
        lastRefresh.value = result
        result
    }

    fun reset() {
        lastRefresh.value = null
    }

    private companion object {
        const val MAX_DETAILED_TRIPS = 5
    }
}

/**
 * "Sorun Bildir": the `Problem` action of a trip that uses trip actions, or the `ProblemReported` status of a
 * legacy trip. The preset category is always part of the note, so it is never blank.
 */
@Singleton
class RealIncidentRepository @Inject constructor(
    private val trips: RealTripRepository,
    private val executions: RealTripExecutionRepository,
) : IncidentRepository {

    override suspend fun report(tripId: String?, category: IncidentCategory, note: String?): ActionResult {
        var trip = findTrip(tripId) ?: return ActionResult.Rejected(ActionRejection.TRIP_REQUIRED)
        if (trip.workflow == TripWorkflow.UNRESOLVED && trip.state != TripState.ASSIGNED) {
            trips.refreshTrip(trip.id)
            trip = findTrip(trip.id) ?: return ActionResult.Rejected(ActionRejection.TRIP_REQUIRED)
        }
        val text = problemNote(category, note)
        return when (trip.workflow) {
            TripWorkflow.LEGACY_STATUSES ->
                if (trip.state == TripState.ACCEPTED || trip.state.isInProgress) {
                    trips.reportStatus(trip.id, DriverStatusNames.PROBLEM_REPORTED, text)
                } else {
                    ActionResult.Rejected(ActionRejection.TRIP_REQUIRED)
                }
            else -> {
                val problem = trips.currentExecution(trip.id)?.availableActions?.firstOrNull { it.code == ExecutionActionCode.PROBLEM }
                    ?: return ActionResult.Rejected(ActionRejection.TRIP_REQUIRED)
                executions.submit(trip.id, problem, text)
            }
        }
    }

    private suspend fun findTrip(tripId: String?): Trip? = tripId?.let { id -> trips.currentTrips().firstOrNull { it.id == id } }

    companion object {
        const val MAX_NOTE_LENGTH = 1000

        fun problemNote(category: IncidentCategory, note: String?): String {
            val text = note?.trim()?.takeIf { it.isNotEmpty() }
            val prefix = "[${category.name}]"
            return (if (text == null) prefix else "$prefix $text").take(MAX_NOTE_LENGTH)
        }
    }
}

/** The Driver API has no shift/attendance endpoints: a controlled unavailable state, never a fake success. */
@Singleton
class RealShiftRepository @Inject constructor() : ShiftRepository {
    override fun observeShift(): Flow<Shift> = flowOf(Shift.Unavailable)

    override suspend fun currentShift(): Shift = Shift.Unavailable

    override suspend fun startShift(): ActionResult = ActionResult.Rejected(ActionRejection.NOT_SUPPORTED)

    override suspend fun endShift(): ActionResult = ActionResult.Rejected(ActionRejection.NOT_SUPPORTED)
}

/** Real mode keeps no background queue. */
object NoOpSyncRunner : SyncRunner {
    override suspend fun sync(): SyncRunResult = SyncRunResult.COMPLETED
}

/**
 * Satisfies the demo data-source contract in real mode. It never returns data, so a real-mode
 * failure can never fall back to demo content.
 */
object UnavailableDriverRemoteDataSource : DriverRemoteDataSource {
    private fun unavailable(): Nothing = throw RemoteException.NotSupported("demo_remote")

    override suspend fun login(username: String, password: String): AuthTokens = unavailable()
    override suspend fun getProfile(): Driver = unavailable()
    override suspend fun getCurrentShift(): Shift = unavailable()
    override suspend fun startShift(request: DriverActionRequest): Shift? = unavailable()
    override suspend fun endShift(request: DriverActionRequest): Shift? = unavailable()
    override suspend fun getTrips(): List<RemoteTripSummary> = unavailable()
    override suspend fun getTrip(tripId: String): Trip = unavailable()
    override suspend fun submitTripAction(tripId: String, request: TripActionRequest): Trip = unavailable()
    override suspend fun getDocuments(tripId: String): List<RemoteDocument> = unavailable()
    override suspend fun uploadDocument(metadata: DocumentUploadMetadata, file: File): RemoteDocument? = unavailable()
    override suspend fun createIncident(report: IncidentReport) = unavailable()
}
