package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.IdGenerator
import com.dynamictransport.dynamicone.core.network.DriverActionCommandDto
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.DriverErrorCodes
import com.dynamictransport.dynamicone.core.network.DriverTrailerCommandDto
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.ActionReceipt
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.PendingTripAction
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.TripExecution
import com.dynamictransport.dynamicone.domain.model.TripTrailerState
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import com.dynamictransport.dynamicone.domain.repository.TripExecutionRepository
import com.dynamictransport.dynamicone.domain.rules.DriverInputRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trip action workflow: only options of the current server plan are sent, each with a command id that is
 * stored before sending. An unknown outcome keeps the command for an exact resend; a 409 drops it and the
 * current plan is read so the driver chooses again. A queued command is never rewritten. A step the server
 * blocks for missing evidence (docs/18) is not sent; if the server refuses it anyway, nothing is marked done.
 */
@Singleton
class RealTripExecutionRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
    private val trips: RealTripRepository,
    private val store: PendingCommandStore,
    private val ids: IdGenerator,
    private val clock: Clock,
) : TripExecutionRepository {

    private val receipts = MutableStateFlow<Map<String, List<ActionReceipt>>>(emptyMap())
    private val trailers = MutableStateFlow<Map<String, TripTrailerState>>(emptyMap())
    private val sendMutex = Mutex()

    override fun observeExecution(tripId: String): Flow<TripExecution?> = trips.observeExecution(tripId)

    override fun observePendingAction(tripId: String): Flow<PendingTripAction?> = store.observeTripAction(tripId)

    override fun observeReceipts(tripId: String): Flow<List<ActionReceipt>> =
        receipts.map { it[tripId].orEmpty() }.distinctUntilChanged()

    override suspend fun refreshReceipts(tripId: String): RefreshResult = try {
        val page = tracker.call { api.actions(tripId, 1, RECEIPT_PAGE_SIZE) }
        receipts.update { it + (tripId to page.items.orEmpty().map(DriverApiMapping::receipt)) }
        tracker.markReadSucceeded()
        RefreshResult.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        e.toRefreshResult()
    }

    override fun observeTrailers(tripId: String): Flow<TripTrailerState?> =
        trailers.map { it[tripId] }.distinctUntilChanged()

    override suspend fun refreshTrailers(tripId: String): RefreshResult = try {
        val state = DriverApiMapping.trailerState(tripId, tracker.call { api.trailers(tripId) })
        trailers.update { it + (tripId to state) }
        tracker.markReadSucceeded()
        RefreshResult.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        e.toRefreshResult()
    }

    /**
     * The choice is sent with the version of the list that was read. The server records the same choice
     * again without a second history entry, so an unclear outcome is resolved by reading, never by a
     * second, different write.
     */
    override suspend fun confirmTrailer(tripId: String, trailerId: String): ActionResult = sendMutex.withLock {
        val state = trailers.value[tripId] ?: return@withLock ActionResult.Rejected(ActionRejection.PLAN_CHANGED)
        if (!state.canConfirm) return@withLock ActionResult.Rejected(ActionRejection.NOT_ALLOWED)
        if (state.trailers.none { it.id == trailerId }) return@withLock ActionResult.Rejected(ActionRejection.NOT_FOUND)
        val response = try {
            tracker.call { api.confirmTrailer(tripId, DriverTrailerCommandDto(trailerId, state.version)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            // A changed plan, a trailer that is no longer active or an unknown outcome: read the truth.
            if (e is RemoteException.Conflict || e is RemoteException.Validation || e.isOutcomeUnknown()) {
                refreshTrailers(tripId)
                trips.refreshTrip(tripId)
            }
            return@withLock ActionResult.Rejected(
                if (e is RemoteException.Conflict) ActionRejection.PLAN_CHANGED else e.toActionRejection(),
            )
        }
        // The confirmation changes the execution version: the plan and the list are read again before Start.
        refreshTrailers(tripId)
        trips.refreshTrip(tripId)
        if (response.succeeded) ActionResult.Queued else ActionResult.Rejected(ActionRejection.FAILED)
    }

    override suspend fun submit(tripId: String, action: ExecutionAction, note: String?): ActionResult {
        // A seal entry carries its own payload and goes through recordSealNumber (docs/27).
        if (action.code == ExecutionActionCode.RECORD_SEAL_NUMBER) return ActionResult.Rejected(ActionRejection.NOT_ALLOWED)
        DriverInputRules.validateActionNote(action.code, note)?.let { return ActionResult.Rejected(it) }
        return enqueue(tripId, action, DriverInputRules.normalizeNote(note), sealNumber = null)
    }

    override suspend fun recordSealNumber(tripId: String, action: ExecutionAction, sealNumber: String): ActionResult {
        if (action.code != ExecutionActionCode.RECORD_SEAL_NUMBER) return ActionResult.Rejected(ActionRejection.NOT_ALLOWED)
        DriverInputRules.validateSealNumber(sealNumber)?.let { return ActionResult.Rejected(it) }
        return enqueue(tripId, action, note = null, sealNumber = DriverInputRules.normalizeSealNumber(sealNumber))
    }

    private suspend fun enqueue(tripId: String, action: ExecutionAction, note: String?, sealNumber: String?): ActionResult =
        sendMutex.withLock {
            if (store.tripAction(tripId) != null) return@withLock ActionResult.Rejected(ActionRejection.PENDING_ACTION)
            val execution = trips.currentExecution(tripId)?.takeIf { it.workflow == TripWorkflow.TRIP_ACTIONS }
                ?: return@withLock ActionResult.Rejected(ActionRejection.PLAN_CHANGED)
            // The step is looked up in the current plan: its evidence state may have changed since it was shown.
            val current = execution.availableActions.firstOrNull { it.isSameStep(action) }
                ?: return@withLock ActionResult.Rejected(ActionRejection.PLAN_CHANGED)
            // The trailer step comes first; it is not an evidence problem (docs/20).
            if (current.blockedByTrailer) return@withLock ActionResult.Rejected(ActionRejection.NOT_ALLOWED)
            // An export loading stop is closed by the planner: the app never sends its completion (docs/27).
            if (current.code == ExecutionActionCode.COMPLETE_STOP && current.stopId == execution.exportLoadingStopId) {
                return@withLock ActionResult.Rejected(ActionRejection.NOT_ALLOWED)
            }
            if (current.isBlocked) return@withLock ActionResult.Rejected(ActionRejection.DOCUMENTS_REQUIRED)
            val command = PendingTripAction(
                commandId = ids.newId(),
                tripId = tripId,
                expectedVersion = execution.version,
                action = current,
                note = note,
                location = null,
                createdAt = clock.instant(),
                state = PendingCommandState.SENDING,
                sealNumber = sealNumber,
            )
            if (!store.save(command)) return@withLock ActionResult.Rejected(ActionRejection.NOT_LOGGED_IN)
            send(command)
        }

    override suspend fun resendPending(tripId: String): ActionResult = sendMutex.withLock {
        val command = store.tripAction(tripId) ?: return@withLock ActionResult.Rejected(ActionRejection.NOT_FOUND)
        send(command)
    }

    override suspend fun discardPending(tripId: String): RefreshResult {
        sendMutex.withLock { store.deleteTripActions(tripId) }
        return trips.refreshTrip(tripId)
    }

    fun clear() {
        receipts.value = emptyMap()
        trailers.value = emptyMap()
    }

    private suspend fun send(command: PendingTripAction): ActionResult {
        store.markState(command.commandId, PendingCommandState.SENDING)
        val response = try {
            tracker.call { api.executeAction(command.tripId, command.toDto()) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.markState(command.commandId, PendingCommandState.OUTCOME_UNKNOWN) }
            throw e
        } catch (e: RemoteException) {
            return failed(command, e)
        }
        store.delete(command.commandId)
        trips.refreshTrip(command.tripId)
        if (!response.succeeded) return ActionResult.Rejected(ActionRejection.FAILED)
        refreshReceipts(command.tripId)
        return ActionResult.Queued
    }

    private suspend fun failed(command: PendingTripAction, e: RemoteException): ActionResult = when {
        e.isOutcomeUnknown() -> {
            store.markState(command.commandId, PendingCommandState.OUTCOME_UNKNOWN)
            ActionResult.Rejected(ActionRejection.OUTCOME_UNKNOWN)
        }
        e is RemoteException.Conflict -> {
            // Not recorded. The new plan carries the evidence checklist that shows what is missing.
            store.delete(command.commandId)
            trips.refreshTrip(command.tripId)
            ActionResult.Rejected(
                when (e.problem?.code) {
                    DriverErrorCodes.REQUIRED_DOCUMENTS_MISSING -> ActionRejection.DOCUMENTS_REQUIRED
                    DriverErrorCodes.SEAL_NUMBER_REQUIRED -> ActionRejection.SEAL_NUMBER_REQUIRED
                    DriverErrorCodes.LOADING_CONFIRMATION_REQUIRED -> ActionRejection.LOADING_CONFIRMATION_REQUIRED
                    else -> ActionRejection.PLAN_CHANGED
                },
            )
        }
        e is RemoteException.NotFound && !e.isApiDisabled -> {
            store.delete(command.commandId)
            trips.refreshTrip(command.tripId)
            ActionResult.Rejected(ActionRejection.NOT_FOUND)
        }
        e is RemoteException.Validation && e.problem?.code == DriverErrorCodes.INVALID_SEAL_NUMBER -> {
            // The driver corrects the value; the next attempt is a new command.
            store.delete(command.commandId)
            ActionResult.Rejected(ActionRejection.SEAL_NUMBER_INVALID)
        }
        e is RemoteException.Validation || e is RemoteException.Rejected || e is RemoteException.PayloadTooLarge -> {
            store.delete(command.commandId)
            ActionResult.Rejected(ActionRejection.FAILED)
        }
        else -> {
            // Session, permission, rate limit or disabled API: not processed, safe to resend unchanged later.
            store.markState(command.commandId, PendingCommandState.NOT_SENT)
            ActionResult.Rejected(e.toActionRejection())
        }
    }

    private fun PendingTripAction.toDto() = DriverActionCommandDto(
        commandId = commandId,
        expectedVersion = expectedVersion,
        action = DriverApiMapping.actionName(action.code),
        stopId = action.stopId,
        containerId = action.containerId,
        note = note,
        observedAtUtc = location?.observedAt?.toString(),
        latitude = location?.latitude,
        longitude = location?.longitude,
        accuracyMeters = location?.accuracyMeters,
        sealNumber = sealNumber,
    )

    private companion object {
        const val RECEIPT_PAGE_SIZE = 25
    }
}
