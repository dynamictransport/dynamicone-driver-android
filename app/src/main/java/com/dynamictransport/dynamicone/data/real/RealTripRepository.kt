package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.DriverCommandResponseDto
import com.dynamictransport.dynamicone.core.network.DriverStatusNames
import com.dynamictransport.dynamicone.core.network.DriverStatusRequestDto
import com.dynamictransport.dynamicone.core.network.DriverStatusResponseDto
import com.dynamictransport.dynamicone.core.network.DriverTripSummaryResponseDto
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.data.real.DriverApiMapping.withDetailsFrom
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionRecord
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripExecution
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import com.dynamictransport.dynamicone.domain.repository.TripActionCommand
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.domain.statemachine.TripSnapshot
import com.dynamictransport.dynamicone.domain.statemachine.TripStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trips from the DynamicONE Driver API. The server resolves the driver from the token.
 *
 * The list holds the driver's open assignments (`includeCompleted=false`) followed by completed trips
 * (`includeCompleted=true`, paged on demand). A trip's next step comes from its execution plan; trips that
 * were started before trip actions existed keep the legacy status workflow. The two are never mixed.
 */
@Singleton
class RealTripRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
) : TripRepository {

    private val trips = MutableStateFlow<List<Trip>>(emptyList())
    private val executions = MutableStateFlow<Map<String, TripExecution>>(emptyMap())
    private val histories = MutableStateFlow<Map<String, List<TripActionRecord>>>(emptyMap())
    private val canLoadMore = MutableStateFlow(false)
    private val listMutex = Mutex()
    private val writeMutex = Mutex()

    /** Assignment and trip status that the stored workflow and legacy reports belong to. */
    private val serverStatuses = ConcurrentHashMap<String, Pair<String?, String?>>()
    private val legacyReports = ConcurrentHashMap<String, Set<String>>()

    @Volatile
    private var nextHistoryPage = 2

    override fun observeTrips(): Flow<List<Trip>> = trips

    override fun observeTrip(tripId: String): Flow<Trip?> =
        trips.map { list -> list.firstOrNull { it.id == tripId } }.distinctUntilChanged()

    override fun observeActionHistory(tripId: String): Flow<List<TripActionRecord>> =
        histories.map { it[tripId].orEmpty() }.distinctUntilChanged()

    /** No shift endpoints exist, so there is no shift-scoped count. */
    override fun observeFinishedTripCount(since: Instant): Flow<Int> = flowOf(0)

    override suspend fun currentTrips(): List<Trip> = trips.value

    override fun observeCanLoadMore(): Flow<Boolean> = canLoadMore

    fun observeExecution(tripId: String): Flow<TripExecution?> = executions.map { it[tripId] }.distinctUntilChanged()

    fun currentExecution(tripId: String): TripExecution? = executions.value[tripId]

    /** Every open assignment (up to [MAX_OPEN_PAGES] pages) and the first page of completed trips. */
    suspend fun refreshAll(): RefreshResult = listMutex.withLock {
        read {
            val open = mutableListOf<DriverTripSummaryResponseDto>()
            var page = 1
            do {
                val response = tracker.call { api.trips(page, PAGE_SIZE, includeCompleted = false) }
                open += response.items.orEmpty()
                page++
            } while (response.hasMore && page <= MAX_OPEN_PAGES)
            val history = tracker.call { api.trips(1, PAGE_SIZE, includeCompleted = true) }

            val known = trips.value.associateBy { it.id }
            val openTrips = open.distinctBy { it.id }.map { fromSummary(it, known[it.id]) }
            val openIds = openTrips.mapTo(HashSet()) { it.id }
            val completed = history.items.orEmpty()
                .filter { it.id !in openIds }
                .map { fromSummary(it, known[it.id]) }
                .filter { it.state.isTerminal }
            val current = openTrips + completed
            // Cancelled or no longer assigned trips are not returned any more: they are not kept locally.
            forgetAllExcept(current.mapTo(HashSet()) { it.id })
            trips.value = current
            nextHistoryPage = 2
            canLoadMore.value = history.hasMore
        }
    }

    /** Next page of completed trips. */
    override suspend fun loadMoreTrips(): RefreshResult = listMutex.withLock {
        if (!canLoadMore.value) return@withLock RefreshResult.SUCCESS
        read {
            val page = nextHistoryPage
            val response = tracker.call { api.trips(page, PAGE_SIZE, includeCompleted = true) }
            val known = trips.value.mapTo(HashSet()) { it.id }
            val added = response.items.orEmpty()
                .filter { it.id !in known }
                .map { fromSummary(it, null) }
                .filter { it.state.isTerminal }
            trips.update { (it + added).distinctBy { trip -> trip.id } }
            nextHistoryPage = page + 1
            canLoadMore.value = response.hasMore
        }
    }

    /** Detail, stops and containers plus the execution plan (or, for legacy trips, the status history). */
    override suspend fun refreshTrip(tripId: String): RefreshResult = read {
        try {
            val detail = tracker.call { api.trip(tripId) }
            val summary = detail.trip ?: throw RemoteException.InvalidResponse()
            val stops = (detail.stops ?: tracker.call { api.stops(tripId) }).map(DriverApiMapping::stop)
            // A new assignment has no plan yet: it has to be accepted first.
            val execution = if (summary.assignmentStatus == DriverApiMapping.ASSIGNMENT_CONFIRMED) {
                null
            } else {
                DriverApiMapping.execution(tripId, tracker.call { api.execution(tripId) })
            }
            val workflow = execution?.workflow ?: TripWorkflow.UNRESOLVED
            val statuses = if (workflow == TripWorkflow.LEGACY_STATUSES) loadStatuses(tripId) else emptyList()

            serverStatuses[tripId] = summary.assignmentStatus to summary.status
            if (workflow == TripWorkflow.LEGACY_STATUSES) {
                legacyReports[tripId] = statuses.mapNotNullTo(HashSet()) { it.status }
            } else {
                legacyReports.remove(tripId)
            }
            val trip = DriverApiMapping.trip(
                summary = summary,
                derived = derive(tripId, summary, workflow),
                workflow = workflow,
                instructions = detail.instructions,
                stops = stops,
                containers = detail.containers.orEmpty(),
            )
            upsert(trip)
            executions.update { if (execution == null) it - tripId else it + (tripId to execution) }
            histories.update { it + (tripId to DriverApiMapping.history(statuses)) }
        } catch (e: RemoteException.NotFound) {
            // Unknown, cancelled or no longer assigned to this driver: stop showing it.
            if (!e.isApiDisabled) remove(tripId)
            throw e
        }
    }

    /** Accept, and the steps of the legacy status workflow. Trip action steps are sent by [RealTripExecutionRepository]. */
    override suspend fun submitAction(command: TripActionCommand): ActionResult {
        val trip = trips.value.firstOrNull { it.id == command.tripId }
            ?: return ActionResult.Rejected(ActionRejection.NOT_FOUND)
        val allowed = TripStateMachine.allowedActions(TripSnapshot(trip.state, trip.breakState), trip.serverAllowedActions)
        if (command.action !in allowed) return ActionResult.Rejected(ActionRejection.NOT_ALLOWED)
        if (command.action == TripActionType.ACCEPT_TRIP) {
            return write(command.tripId) { api.accept(command.tripId) }
        }
        val status = DriverApiMapping.statusForAction(command.action)
            ?: return ActionResult.Rejected(ActionRejection.NOT_SUPPORTED)
        return reportStatus(command.tripId, status, null)
    }

    /** Legacy status report; refused for trips that use trip actions. */
    suspend fun reportStatus(tripId: String, status: String, note: String?): ActionResult {
        val trip = trips.value.firstOrNull { it.id == tripId } ?: return ActionResult.Rejected(ActionRejection.NOT_FOUND)
        if (trip.workflow != TripWorkflow.LEGACY_STATUSES) return ActionResult.Rejected(ActionRejection.NOT_ALLOWED)
        val result = write(tripId) { api.reportStatus(tripId, DriverStatusRequestDto(status, note)) }
        // The legacy refusal has no own code: a refused completion with open required evidence in the
        // re-read checklist is explained as missing documents instead of a changed state.
        val evidenceOpen = executions.value[tripId]?.documents?.any { it.needsUpload } == true
        return if (result == ActionResult.Rejected(ActionRejection.CONFLICT) && status in DriverStatusNames.EVIDENCE_GATED && evidenceOpen) {
            ActionResult.Rejected(ActionRejection.DOCUMENTS_REQUIRED)
        } else {
            result
        }
    }

    fun clear() {
        trips.value = emptyList()
        executions.value = emptyMap()
        histories.value = emptyMap()
        serverStatuses.clear()
        legacyReports.clear()
        canLoadMore.value = false
        nextHistoryPage = 2
    }

    /**
     * Legacy writes have no idempotency key: after a conflict or an unknown outcome the trip is read
     * again instead of resending.
     */
    private suspend fun write(tripId: String, request: suspend () -> DriverCommandResponseDto): ActionResult =
        writeMutex.withLock {
            val response = try {
                tracker.call(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteException) {
                if (e is RemoteException.Conflict || e.isOutcomeUnknown()) refreshTrip(tripId)
                return@withLock ActionResult.Rejected(e.toActionRejection())
            }
            refreshTrip(tripId)
            if (response.succeeded) ActionResult.Queued else ActionResult.Rejected(ActionRejection.FAILED)
        }

    private suspend fun read(block: suspend () -> Unit): RefreshResult = try {
        block()
        tracker.markReadSucceeded()
        RefreshResult.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        e.toRefreshResult()
    }

    /**
     * A list item keeps the workflow and details read earlier while its status is unchanged. A changed
     * status makes the plan unknown until the trip is read again.
     */
    private fun fromSummary(summary: DriverTripSummaryResponseDto, known: Trip?): Trip {
        val status = summary.assignmentStatus to summary.status
        val unchanged = known != null && serverStatuses[summary.id] == status
        if (!unchanged) {
            executions.update { it - summary.id }
            histories.update { it - summary.id }
            legacyReports.remove(summary.id)
            serverStatuses[summary.id] = status
        }
        val workflow = known?.takeIf { unchanged }?.workflow ?: TripWorkflow.UNRESOLVED
        return DriverApiMapping.trip(summary, derive(summary.id, summary, workflow), workflow).withDetailsFrom(known)
    }

    private fun derive(tripId: String, summary: DriverTripSummaryResponseDto, workflow: TripWorkflow) =
        if (workflow == TripWorkflow.LEGACY_STATUSES) {
            DriverApiMapping.deriveState(summary.assignmentStatus, summary.status, legacyReports[tripId].orEmpty())
        } else {
            DriverApiMapping.baseState(summary.assignmentStatus, summary.status)
        }

    private suspend fun loadStatuses(tripId: String): List<DriverStatusResponseDto> {
        val all = mutableListOf<DriverStatusResponseDto>()
        var page = 1
        do {
            val response = tracker.call { api.statuses(tripId, page, STATUS_PAGE_SIZE) }
            all += response.items.orEmpty()
            page++
        } while (response.hasMore && page <= MAX_STATUS_PAGES)
        return all
    }

    private fun upsert(trip: Trip) = trips.update { list ->
        if (list.any { it.id == trip.id }) list.map { if (it.id == trip.id) trip else it } else listOf(trip) + list
    }

    private fun remove(tripId: String) {
        trips.update { list -> list.filterNot { it.id == tripId } }
        forget(tripId)
    }

    private fun forget(tripId: String) {
        executions.update { it - tripId }
        histories.update { it - tripId }
        serverStatuses.remove(tripId)
        legacyReports.remove(tripId)
    }

    private fun forgetAllExcept(ids: Set<String>) {
        (serverStatuses.keys + executions.value.keys).filterNot { it in ids }.forEach(::forget)
    }

    companion object {
        const val PAGE_SIZE = 25
        const val MAX_OPEN_PAGES = 4
        const val STATUS_PAGE_SIZE = 100
        const val MAX_STATUS_PAGES = 5
    }
}
